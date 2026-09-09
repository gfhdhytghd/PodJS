import Foundation
import CPodJSSync

public enum SyncMessageOutboxError: Error, Equatable, Sendable {
    case invalidIdentity, invalidTime, corruptSnapshot, identityConflict, expired, quota, conflict, digest
}
public struct SyncOutgoingMessage: Sendable {
    public let peer: String
    public let messageID: String
    public let envelope: SyncMessageEnvelope
    public let digest: Data
}
func messageDigest(_ data: Data) throws -> Data {
    var digest = Data(count: 32)
    let result = data.withUnsafeBytes { input in digest.withUnsafeMutableBytes { output in
        pod_sync_message_sha256(input.bindMemory(to: UInt8.self).baseAddress, input.count, output.bindMemory(to: UInt8.self).baseAddress)
    } }
    guard result == 0 else { throw SyncMessageOutboxError.digest }; return digest
}
/// Host-only TTL outbox. Use an independent store namespace configured for
/// 18 MiB snapshots; active payload cost is capped at 8 MiB including 1 KiB/row.
/// Successful enqueue/CAS is local acceptance, not remote delivery. Retained
/// retry intents prevent an acknowledged message from being recreated by a
/// lost service reply. Each operation rereads and validates the durable journal.
public final class SyncMessageOutbox: @unchecked Sendable {
    private let lock = NSLock()
    private let store: any SyncSnapshotStore
    private let app: String
    private let local: String
    public init(appID: String, deviceID: String, store: any SyncSnapshotStore) throws {
        try Self.identity(appID); try Self.identity(deviceID)
        self.app = appID; self.local = deviceID; self.store = store
    }
    func matchesIdentity(app: String, device: String) -> Bool { self.app == app && local == device }
    private static func identity(_ value: String) throws {
        guard !value.isEmpty, value.utf8.count <= 128, value.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
        }) else { throw SyncMessageOutboxError.invalidIdentity }
    }
    private func peer(_ value: String) throws { try Self.identity(value); guard value != local else { throw SyncMessageOutboxError.invalidIdentity } }
    private static func time(_ value: UInt64) throws { guard value <= 9_007_199_254_740_991 else { throw SyncMessageOutboxError.invalidTime } }
    private func load(_ raw: Data?) throws -> MessageOutboxSnapshot {
        guard let raw else { return MessageOutboxSnapshot(schema: 1, app: app, local: local, messages: [], intents: []) }
        guard raw.count <= 18 * 1024 * 1024 else { throw SyncMessageOutboxError.quota }
        let snapshot = try JSONDecoder().decode(MessageOutboxSnapshot.self, from: raw)
        guard snapshot.schema == 1, snapshot.app == app, snapshot.local == local,
              snapshot.messages.count <= 1000, snapshot.intents.count <= 10000 else { throw SyncMessageOutboxError.corruptSnapshot }
        var intents: [String: MessageIntent] = [:]
        for intent in snapshot.intents {
            try peer(intent.peer); try Self.identity(intent.id); try Self.time(intent.expires); try Self.time(intent.ttl)
            let key = intent.peer + "/" + intent.id
            guard intent.ttl > 0, intent.expires >= intent.ttl, intent.digest.count == 32, intents[key] == nil else { throw SyncMessageOutboxError.corruptSnapshot }
            intents[key] = intent
        }
        var keys = Set<String>(), cost = 0
        for row in snapshot.messages {
            try peer(row.peer); try Self.identity(row.id)
            let key = row.peer + "/" + row.id
            let envelope = try SyncMessageEnvelope(expiresAt: row.expires, highPriority: row.high, payload: row.payload)
            guard keys.insert(key).inserted, let intent = intents[key], intent.expires == row.expires, intent.high == row.high,
                  try messageDigest(row.payload) == intent.digest, try messageDigest(envelope.encoded()) == row.digest else { throw SyncMessageOutboxError.corruptSnapshot }
            cost += row.payload.count + 1024
            guard cost <= 8 * 1024 * 1024 else { throw SyncMessageOutboxError.quota }
        }
        return snapshot
    }
    private func save(_ snapshot: MessageOutboxSnapshot, expected: Data?) throws {
        let bytes = try JSONEncoder().encode(snapshot)
        guard bytes.count <= 18 * 1024 * 1024 else { throw SyncMessageOutboxError.quota }
        guard try store.compareExchange(expected: expected, desired: bytes) else { throw SyncMessageOutboxError.conflict }
    }
    /// Repeating the same peer/ID/content/TTL returns the original expiry even
    /// after an ACK removed the queue row. Reusing that ID with new content fails.
    @discardableResult public func enqueue(peer: String, messageID: String, payload: Data, ttlMilliseconds: UInt64,
                                          highPriority: Bool, nowMilliseconds: UInt64) throws -> UInt64 {
        try self.peer(peer); try Self.identity(messageID); try Self.time(nowMilliseconds); try Self.time(ttlMilliseconds)
        guard ttlMilliseconds > 0, ttlMilliseconds <= 9_007_199_254_740_991 - nowMilliseconds else { throw SyncMessageOutboxError.invalidTime }
        let envelope = try SyncMessageEnvelope(expiresAt: nowMilliseconds + ttlMilliseconds, highPriority: highPriority, payload: payload)
        let digest = try messageDigest(payload)
        lock.lock(); defer { lock.unlock() }
        let raw = try store.read(); var snapshot = try load(raw)
        if let prior = snapshot.intents.first(where: { $0.peer == peer && $0.id == messageID }) {
            guard prior.ttl == ttlMilliseconds, prior.high == highPriority, prior.digest == digest else { throw SyncMessageOutboxError.identityConflict }
            guard prior.expires > nowMilliseconds else { throw SyncMessageOutboxError.expired }; return prior.expires
        }
        snapshot.messages.removeAll { $0.expires <= nowMilliseconds }; snapshot.intents.removeAll { $0.expires <= nowMilliseconds }
        let cost = snapshot.messages.reduce(payload.count + 1024) { $0 + $1.payload.count + 1024 }
        guard snapshot.messages.count < 1000, snapshot.intents.count < 10000, cost <= 8 * 1024 * 1024 else { throw SyncMessageOutboxError.quota }
        snapshot.messages.append(MessageRow(peer: peer, id: messageID, expires: envelope.expiresAt, high: highPriority, payload: payload, digest: try messageDigest(envelope.encoded())))
        snapshot.intents.append(MessageIntent(peer: peer, id: messageID, ttl: ttlMilliseconds, expires: envelope.expiresAt, high: highPriority, digest: digest))
        try save(snapshot, expected: raw); return envelope.expiresAt
    }
    /// Read-only, high priority first, FIFO within equal priority. Does not mark sent.
    public func pending(peer: String, nowMilliseconds: UInt64, limit: Int = 1) throws -> [SyncOutgoingMessage] {
        try self.peer(peer); try Self.time(nowMilliseconds); guard (1...1000).contains(limit) else { throw SyncMessageOutboxError.quota }
        lock.lock(); defer { lock.unlock() }; let snapshot = try load(store.read())
        return try snapshot.messages.enumerated().filter { $0.element.peer == peer && $0.element.expires > nowMilliseconds }
            .sorted { $0.element.high != $1.element.high ? $0.element.high : $0.offset < $1.offset }.prefix(limit).map {
                let row = $0.element
                return SyncOutgoingMessage(peer: row.peer, messageID: row.id, envelope: try SyncMessageEnvelope(expiresAt: row.expires, highPriority: row.high, payload: row.payload), digest: row.digest)
            }
    }
    /// Call only with an authenticated peer/ACK frame. A mismatched digest fails.
    public func acknowledgeAuthenticated(peer: String, messageID: String, digest: Data) throws -> Bool {
        try self.peer(peer); try Self.identity(messageID); guard digest.count == 32 else { throw SyncMessageOutboxError.digest }
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var snapshot = try load(raw)
        guard let index = snapshot.messages.firstIndex(where: { $0.peer == peer && $0.id == messageID }) else { return false }
        guard snapshot.messages[index].digest == digest else { throw SyncMessageOutboxError.identityConflict }
        snapshot.messages.remove(at: index); try save(snapshot, expected: raw); return true
    }
    @discardableResult public func expire(nowMilliseconds: UInt64) throws -> Int {
        try Self.time(nowMilliseconds); lock.lock(); defer { lock.unlock() }
        let raw = try store.read(); var snapshot = try load(raw); let before = snapshot.messages.count
        snapshot.messages.removeAll { $0.expires <= nowMilliseconds }
        let removed = before - snapshot.messages.count
        if removed > 0 { try save(snapshot, expected: raw) }; return removed
    }
}
private struct MessageRow: Codable { let peer: String; let id: String; let expires: UInt64; let high: Bool; let payload: Data; let digest: Data }
private struct MessageIntent: Codable { let peer: String; let id: String; let ttl: UInt64; let expires: UInt64; let high: Bool; let digest: Data }
private struct MessageOutboxSnapshot: Codable { let schema: Int; let app: String; let local: String; var messages: [MessageRow]; var intents: [MessageIntent] }
