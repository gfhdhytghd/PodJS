import Foundation

public enum SyncFileRequestsError: Error, Equatable, Sendable { case identity, journal, quota, peerBusy, conflict, changed, unknown }
public struct SyncPendingFileRequest: Sendable {
    public let peer: String
    public let messageID: String
    public let request: SyncFileRequest
    public let reply: SyncFileReply?
}
/// Independent durable request namespace, at most one pending operation per
/// peer. No record eviction: the host consumes exact completed observations.
/// This is not the cross-direction transfer registry or source snapshot store.
public final class SyncFileRequests: @unchecked Sendable {
    public enum Receipt: Sendable { case received, duplicate }
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
        }) else { throw SyncFileRequestsError.identity }
    }
    private func peer(_ value: String) throws { try Self.identity(value); guard value != local else { throw SyncFileRequestsError.identity } }
    private func decoded(_ record: FileRequestRecord) throws -> SyncPendingFileRequest {
        let request = try SyncFileRequest.decode(record.payload)
        let reply = try record.reply.map { try SyncFileReply.decode($0, request: request) }
        return SyncPendingFileRequest(peer: record.peer, messageID: record.id, request: request, reply: reply)
    }
    private func load(_ raw: Data?) throws -> FileRequestsJournal {
        guard let raw else { return FileRequestsJournal(schema: 1, kind: "file-requests", app: app, local: local, records: []) }
        guard raw.count <= 18 * 1024 * 1024 else { throw SyncFileRequestsError.quota }
        let journal = try JSONDecoder().decode(FileRequestsJournal.self, from: raw)
        guard journal.schema == 1, journal.kind == "file-requests", journal.app == app, journal.local == local, journal.records.count <= 128 else { throw SyncFileRequestsError.journal }
        var ids = Set<String>(), pending = Set<String>(), cost = 0
        for record in journal.records {
            try peer(record.peer); try Self.identity(record.id)
            guard ids.insert(record.id).inserted, try messageDigest(record.payload) == record.digest else { throw SyncFileRequestsError.journal }
            _ = try decoded(record)
            if record.reply == nil { guard pending.insert(record.peer).inserted else { throw SyncFileRequestsError.journal } }
            cost += record.payload.count + 4352; guard cost <= 8 * 1024 * 1024 else { throw SyncFileRequestsError.quota }
        }
        return journal
    }
    private func save(_ journal: FileRequestsJournal, expected: Data?) throws {
        let bytes = try JSONEncoder().encode(journal)
        guard bytes.count <= 18 * 1024 * 1024 else { throw SyncFileRequestsError.quota }
        guard try store.compareExchange(expected: expected, desired: bytes) else { throw SyncFileRequestsError.conflict }
    }
    /// Host supplies/reuses a stable request ID for uncertain commit recovery.
    /// Repeated same ID/peer/exact bytes returns the existing pending or reply.
    public func enqueue(peer: String, messageID: String, request: SyncFileRequest) throws -> SyncPendingFileRequest {
        try self.peer(peer); try Self.identity(messageID)
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var journal = try load(raw)
        if let prior = journal.records.first(where: { $0.id == messageID }) {
            guard prior.peer == peer, prior.payload == request.originalJSON else { throw SyncFileRequestsError.changed }; return try decoded(prior)
        }
        guard !journal.records.contains(where: { $0.peer == peer && $0.reply == nil }) else { throw SyncFileRequestsError.peerBusy }
        let cost = journal.records.reduce(request.originalJSON.count + 4352) { $0 + $1.payload.count + 4352 }
        guard journal.records.count < 128, cost <= 8 * 1024 * 1024 else { throw SyncFileRequestsError.quota }
        let row = FileRequestRecord(peer: peer, id: messageID, payload: request.originalJSON, digest: try messageDigest(request.originalJSON), reply: nil)
        journal.records.append(row); try save(journal, expected: raw); return try decoded(row)
    }
    public func next(peer: String) throws -> SyncPendingFileRequest? {
        try self.peer(peer); lock.lock(); defer { lock.unlock() }
        return try load(store.read()).records.first(where: { $0.peer == peer && $0.reply == nil }).map { try decoded($0) }
    }
    public func completed(peer: String) throws -> [SyncPendingFileRequest] {
        try self.peer(peer); lock.lock(); defer { lock.unlock() }
        return try load(store.read()).records.filter { $0.peer == peer && $0.reply != nil }.map { try decoded($0) }
    }
    public func records(transferID: String) throws -> [SyncPendingFileRequest] {
        lock.lock(); defer { lock.unlock() }
        return try load(store.read()).records.map { try decoded($0) }.filter { $0.request.transferID == transferID }
    }
    /// Only after authenticating peer/outer ID. Unknown replies are rejected,
    /// not silently applied to another operation. Exact duplicates are read-only.
    public func receiveAuthenticated(peer: String, messageID: String, replyBytes: Data) throws -> Receipt {
        try self.peer(peer); try Self.identity(messageID)
        guard replyBytes.count <= 4096 else { throw SyncFileRequestsError.quota }
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var journal = try load(raw)
        guard let index = journal.records.firstIndex(where: { $0.peer == peer && $0.id == messageID }) else { throw SyncFileRequestsError.unknown }
        let request = try SyncFileRequest.decode(journal.records[index].payload)
        _ = try SyncFileReply.decode(replyBytes, request: request)
        if let previous = journal.records[index].reply {
            guard previous == replyBytes else { throw SyncFileRequestsError.changed }; return .duplicate
        }
        journal.records[index].reply = replyBytes; try save(journal, expected: raw); return .received
    }
    /// Consume only an exact observed completed reply; never removes source
    /// bytes, remote files, pending operations or another peer's observation.
    public func consumeCompleted(_ observation: SyncPendingFileRequest) throws -> Bool {
        guard let reply = observation.reply else { return false }
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var journal = try load(raw)
        guard let index = journal.records.firstIndex(where: { $0.peer == observation.peer && $0.id == observation.messageID &&
            $0.payload == observation.request.originalJSON && $0.reply == reply.originalJSON }) else { return false }
        journal.records.remove(at: index); try save(journal, expected: raw); return true
    }
}
private struct FileRequestRecord: Codable { let peer: String; let id: String; let payload: Data; let digest: Data; var reply: Data? }
private struct FileRequestsJournal: Codable { let schema: Int; let kind: String; let app: String; let local: String; var records: [FileRequestRecord] }
