import Foundation

public enum SyncOutgoingTransferError: Error, Equatable, Sendable { case identity, journal, conflict, unknown, terminal, receipt }
public struct SyncOutgoingTransfer: Codable, Equatable, Sendable {
    public enum Phase: String, Codable, Sendable { case queued, cancelRequested, complete, cancelled }
    public let peer: String
    public let manifest: SyncFileManifest
    public fileprivate(set) var phase: Phase
    public fileprivate(set) var progressKnown: Bool = false
    public fileprivate(set) var acknowledgedChunks: [Int] = []
}
/// Durable host intent/observations, separate from the wire request queue.
/// Registration is internal: the client first verifies its immutable source.
/// Terminal identities are retained. This journal does not itself send frames.
public final class SyncOutgoingTransfers: @unchecked Sendable {
    private let lock = NSLock()
    private let app: String
    private let local: String
    private let store: any SyncSnapshotStore
    public init(appID: String, deviceID: String, store: any SyncSnapshotStore) throws {
        try Self.identity(appID); try Self.identity(deviceID)
        app = appID; local = deviceID; self.store = store
    }
    func matchesIdentity(app: String, device: String) -> Bool { self.app == app && local == device }
    private static func identity(_ value: String) throws {
        guard !value.isEmpty, value.utf8.count <= 128, value.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
        }) else { throw SyncOutgoingTransferError.identity }
    }
    private func load(_ raw: Data?) throws -> OutgoingTransferJournal {
        guard let raw else { return OutgoingTransferJournal(schema: 1, kind: "outgoing-transfers", app: app, local: local, records: []) }
        guard raw.count <= 4 * 1024 * 1024 else { throw SyncOutgoingTransferError.journal }
        let value = try JSONDecoder().decode(OutgoingTransferJournal.self, from: raw)
        guard value.schema == 1, value.kind == "outgoing-transfers", value.app == app, value.local == local, value.records.count <= 128 else { throw SyncOutgoingTransferError.journal }
        var ids = Set<String>()
        for record in value.records {
            try Self.identity(record.peer); _ = try SyncFileRequest.offer(record.manifest)
            guard record.peer != local, ids.insert(record.manifest.transferID).inserted,
                  record.progressKnown || record.acknowledgedChunks.isEmpty else { throw SyncOutgoingTransferError.journal }
            var previous = -1
            for index in record.acknowledgedChunks {
                guard index > previous, index < record.manifest.chunkHashes.count else { throw SyncOutgoingTransferError.journal }; previous = index
            }
            if record.phase == .complete {
                guard record.progressKnown, record.acknowledgedChunks.count == record.manifest.chunkHashes.count else { throw SyncOutgoingTransferError.journal }
            }
        }
        return value
    }
    private func save(_ value: OutgoingTransferJournal, expected: Data?) throws {
        let bytes = try JSONEncoder().encode(value)
        guard bytes.count <= 4 * 1024 * 1024 else { throw SyncOutgoingTransferError.journal }
        guard try store.compareExchange(expected: expected, desired: bytes) else { throw SyncOutgoingTransferError.conflict }
    }
    @discardableResult func register(peer: String, manifest: SyncFileManifest) throws -> SyncOutgoingTransfer {
        try Self.identity(peer); guard peer != local else { throw SyncOutgoingTransferError.identity }
        _ = try SyncFileRequest.offer(manifest)
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var value = try load(raw)
        if let record = value.records.first(where: { $0.manifest.transferID == manifest.transferID }) {
            guard record.peer == peer, record.manifest == manifest else { throw SyncOutgoingTransferError.identity }; return record
        }
        guard value.records.count < 128 else { throw SyncOutgoingTransferError.journal }
        let record = SyncOutgoingTransfer(peer: peer, manifest: manifest, phase: .queued)
        value.records.append(record); try save(value, expected: raw); return record
    }
    public func list() throws -> [SyncOutgoingTransfer] { lock.lock(); defer { lock.unlock() }; return try load(store.read()).records }
    /// Persists intent only; does not claim that the remote transfer is cancelled.
    public func requestCancel(peer: String, transferID: String) throws {
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var value = try load(raw)
        guard let index = value.records.firstIndex(where: { $0.peer == peer && $0.manifest.transferID == transferID }) else { throw SyncOutgoingTransferError.unknown }
        if [.complete,.cancelled,.cancelRequested].contains(value.records[index].phase) { return }
        value.records[index].phase = .cancelRequested; try save(value, expected: raw)
    }
    /// Host supplies an authenticated, durably stored request-queue observation.
    /// Persist this result before consuming that exact completed queue record.
    public func observeCompleted(_ observation: SyncPendingFileRequest) throws {
        guard let reply = observation.reply else { throw SyncOutgoingTransferError.receipt }
        let request = observation.request
        _ = try SyncFileReply.decode(reply.originalJSON, request: request)
        lock.lock(); defer { lock.unlock() }; let raw = try store.read(); var value = try load(raw)
        guard let index = value.records.firstIndex(where: { $0.peer == observation.peer && $0.manifest.transferID == request.transferID }) else { throw SyncOutgoingTransferError.unknown }
        var record = value.records[index]
        if request.method == .offer { guard request.manifest == record.manifest else { throw SyncOutgoingTransferError.identity } }
        if request.method == .chunk {
            guard let chunk = request.index, chunk < record.manifest.chunkHashes.count, let bytes = request.chunk,
                  bytes.count == Int(min(65536, record.manifest.size - UInt64(chunk) * 65536)),
                  try messageDigest(bytes).map({ String(format: "%02x", $0) }).joined() == record.manifest.chunkHashes[chunk] else { throw SyncOutgoingTransferError.receipt }
        }
        if let missing = reply.missing {
            guard missing.allSatisfy({ $0 < record.manifest.chunkHashes.count }), reply.phase != .complete || missing.isEmpty else { throw SyncOutgoingTransferError.receipt }
        }
        if [.complete,.cancelled].contains(record.phase) {
            guard (record.phase == .complete && reply.phase == .complete) || (record.phase == .cancelled && reply.phase == .cancelled) else { throw SyncOutgoingTransferError.terminal }; return
        }
        if reply.phase == .complete {
            record.phase = .complete; record.progressKnown = true; record.acknowledgedChunks = Array(record.manifest.chunkHashes.indices)
        } else if reply.phase == .cancelled { record.phase = .cancelled }
        else if request.method == .missing, let missing = reply.missing {
            guard missing.allSatisfy({ $0 < record.manifest.chunkHashes.count }) else { throw SyncOutgoingTransferError.receipt }
            record.progressKnown = true; record.acknowledgedChunks = record.manifest.chunkHashes.indices.filter { !missing.contains($0) }
        } else if request.method == .chunk, let chunk = request.index {
            guard chunk < record.manifest.chunkHashes.count else { throw SyncOutgoingTransferError.receipt }
            if record.progressKnown, !record.acknowledgedChunks.contains(chunk) { record.acknowledgedChunks.append(chunk); record.acknowledgedChunks.sort() }
        }
        // A repeated observation is safe and does not need a storage write.
        if value.records[index] == record { return }
        value.records[index] = record
        try save(value, expected: raw)
    }
}
private struct OutgoingTransferJournal: Codable { let schema: Int; let kind: String; let app: String; let local: String; var records: [SyncOutgoingTransfer] }
