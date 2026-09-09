import Foundation

public enum SyncMessageInboxError: Error, Equatable, Sendable {
    case identity, time, corruptSnapshot, conflict, capacity, changed, unknown, expired
}
public struct SyncIncomingMessage: Sendable {
    public enum Status: String, Sendable { case pending, applied, expired }
    public let peer: String
    public let messageID: String
    public let envelope: SyncMessageEnvelope
    public let digest: Data
    public let status: Status
}
/// Independent inbox namespace with durable pending effects and applied receipts.
/// The host must make its business effect idempotent by authenticated peer/ID:
/// crashing after the effect but before markApplied can repeat that effect.
/// Do not ACK a pending record as applied just because receiving it was durable.
public final class SyncMessageInbox: @unchecked Sendable {
    private let lock = NSLock()
    private let store: any SyncSnapshotStore
    private let app: String
    private let local: String
    public init(appID: String, deviceID: String, store: any SyncSnapshotStore) throws {
        try Self.identity(appID); try Self.identity(deviceID); self.app = appID; self.local = deviceID; self.store = store
    }
    func matchesIdentity(app: String, device: String) -> Bool { self.app == app && local == device }
    private static func identity(_ value: String) throws {
        guard !value.isEmpty, value.utf8.count <= 128, value.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
        }) else { throw SyncMessageInboxError.identity }
    }
    private func peer(_ value: String) throws { try Self.identity(value); guard value != local else { throw SyncMessageInboxError.identity } }
    private static func time(_ value: UInt64) throws { guard value <= 9_007_199_254_740_991 else { throw SyncMessageInboxError.time } }
    private func load(_ raw: Data?) throws -> MessageInboxSnapshot {
        guard let raw else { return MessageInboxSnapshot(kind: "message-inbox", schema: 1, app: app, local: local, records: []) }
        guard raw.count <= 18 * 1024 * 1024 else { throw SyncMessageInboxError.capacity }
        let snapshot = try JSONDecoder().decode(MessageInboxSnapshot.self, from: raw)
        guard snapshot.kind == "message-inbox", snapshot.schema == 1, snapshot.app == app, snapshot.local == local,
              snapshot.records.count <= 1000 else { throw SyncMessageInboxError.corruptSnapshot }
        var keys = Set<String>(), cost = 0
        for row in snapshot.records {
            try peer(row.peer); try Self.identity(row.id)
            let envelope = try SyncMessageEnvelope.decode(row.wire)
            guard keys.insert(row.peer + "/" + row.id).inserted, try messageDigest(row.wire) == row.digest else { throw SyncMessageInboxError.corruptSnapshot }
            cost += envelope.payload.count + 1024; guard cost <= 8 * 1024 * 1024 else { throw SyncMessageInboxError.capacity }
        }
        return snapshot
    }
    private func save(_ snapshot: MessageInboxSnapshot, expected: Data?) throws {
        let bytes = try JSONEncoder().encode(snapshot)
        guard bytes.count <= 18 * 1024 * 1024 else { throw SyncMessageInboxError.capacity }
        guard try store.compareExchange(expected: expected, desired: bytes) else { throw SyncMessageInboxError.conflict }
    }
    private func delivery(_ row: MessageInboxRow) throws -> SyncIncomingMessage {
        SyncIncomingMessage(peer: row.peer, messageID: row.id, envelope: try SyncMessageEnvelope.decode(row.wire), digest: row.digest, status: row.applied ? .applied : .pending)
    }
    /// Only authenticated outer-frame identities/payload may enter this method.
    /// Expired envelopes return no persisted effect; live duplicates retain the
    /// original pending/applied status and reject any changed content.
    public func receiveAuthenticated(peer: String, messageID: String, envelopeBytes: Data, nowMilliseconds: UInt64) throws -> SyncIncomingMessage {
        try self.peer(peer); try Self.identity(messageID); try Self.time(nowMilliseconds)
        let envelope = try SyncMessageEnvelope.decode(envelopeBytes), digest = try messageDigest(envelopeBytes)
        lock.lock(); defer { lock.unlock() }
        if envelope.expiresAt <= nowMilliseconds {
            return SyncIncomingMessage(peer: peer, messageID: messageID, envelope: envelope, digest: digest, status: .expired)
        }
        let raw = try store.read(); var snapshot = try load(raw)
        if let row = snapshot.records.first(where: { $0.peer == peer && $0.id == messageID }) {
            guard row.wire == envelopeBytes, row.digest == digest else { throw SyncMessageInboxError.changed }; return try delivery(row)
        }
        var alive: [MessageInboxRow] = []; var cost = envelope.payload.count + 1024
        for row in snapshot.records {
            let existing = try SyncMessageEnvelope.decode(row.wire)
            if existing.expiresAt > nowMilliseconds { alive.append(row); cost += existing.payload.count + 1024 }
        }
        guard alive.count < 1000, cost <= 8 * 1024 * 1024 else { throw SyncMessageInboxError.capacity }
        let row = MessageInboxRow(peer: peer, id: messageID, wire: envelopeBytes, digest: digest, applied: false)
        alive.append(row); snapshot.records = alive; try save(snapshot, expected: raw); return try delivery(row)
    }
    /// Invoke only after the host effect was durably/idempotently handled.
    /// The digest is the delivery token; storage failure leaves it retryable.
    public func markApplied(peer: String, messageID: String, digest: Data, nowMilliseconds: UInt64) throws -> SyncIncomingMessage {
        try self.peer(peer); try Self.identity(messageID); try Self.time(nowMilliseconds)
        guard digest.count == 32 else { throw SyncMessageInboxError.changed }
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var snapshot = try load(raw)
        guard let index = snapshot.records.firstIndex(where: { $0.peer == peer && $0.id == messageID }) else { throw SyncMessageInboxError.unknown }
        let row = snapshot.records[index], envelope = try SyncMessageEnvelope.decode(row.wire)
        guard envelope.expiresAt > nowMilliseconds else { throw SyncMessageInboxError.expired }
        guard row.digest == digest else { throw SyncMessageInboxError.changed }
        if !row.applied { snapshot.records[index].applied = true; try save(snapshot, expected: raw) }
        return try delivery(snapshot.records[index])
    }
    /// Read-only priority/FIFO pagination of unexpired pending effects.
    public func pending(nowMilliseconds: UInt64, limit: Int = 100, offset: Int = 0) throws -> [SyncIncomingMessage] {
        try Self.time(nowMilliseconds)
        guard (1...100).contains(limit), (0..<1000).contains(offset) else { throw SyncMessageInboxError.capacity }
        lock.lock(); defer { lock.unlock() }; let snapshot = try load(store.read())
        var items: [(Int, SyncIncomingMessage)] = []
        for (index,row) in snapshot.records.enumerated() where !row.applied {
            let item = try delivery(row); if item.envelope.expiresAt > nowMilliseconds { items.append((index,item)) }
        }
        return items.sorted { $0.1.envelope.highPriority != $1.1.envelope.highPriority ? $0.1.envelope.highPriority : $0.0 < $1.0 }
            .dropFirst(offset).prefix(limit).map { $0.1 }
    }
}
private struct MessageInboxRow: Codable { let peer: String; let id: String; let wire: Data; let digest: Data; var applied: Bool }
private struct MessageInboxSnapshot: Codable { let kind: String; let schema: Int; let app: String; let local: String; var records: [MessageInboxRow] }
