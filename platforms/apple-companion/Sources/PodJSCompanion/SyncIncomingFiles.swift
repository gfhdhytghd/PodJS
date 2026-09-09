import Foundation

public enum SyncIncomingFileError: Error, Equatable, Sendable { case identity, manifest, journal, quota, conflict, unknown, consent, cancelled, closed }
public struct SyncIncomingFile: Codable, Sendable {
    public enum Phase: String, Codable, Sendable { case offered, accepting, accepted, completing, complete, cancelling, cancelled }
    public let peer: String
    public let manifest: SyncFileManifest
    public fileprivate(set) var phase: Phase
    /// Transfer cancellation is separate from retaining a completed local copy.
    public fileprivate(set) var remoteCancelled: Bool? = nil
}
/// Owns both journal and native receiver leases for the entire consent workflow.
/// No transfer allocation before local acceptance. All IDs, including terminal
/// ones, remain reserved across peers. This is host-only, not a guest save API.
public final class SyncIncomingFiles: @unchecked Sendable {
    // The request transaction holds this across existing local operation helpers.
    // No external callbacks run under it; nested calls remain on the same thread.
    private let lock = NSRecursiveLock()
    private let journal: FileSyncSnapshotStore
    private let receiver: SyncFileReceiver
    private let app: String
    private let local: String
    private let transfers: SyncOutgoingTransfers?
    private let limits: SyncFileLimits
    private var closed = false
    func matchesIdentity(app: String, device: String) -> Bool { self.app == app && local == device }
    func matchesStorage(_ storage: SyncGuestFiles) -> Bool { storage.matches(app: app) }
    public init(appID: String, deviceID: String, privateRoot: URL, outgoingTransfers: SyncOutgoingTransfers? = nil, limits: SyncFileLimits = .defaults) throws {
        try Self.identity(appID); try Self.identity(deviceID); self.app = appID; self.local = deviceID
        guard outgoingTransfers == nil || outgoingTransfers!.matchesIdentity(app: appID, device: deviceID) else { throw SyncIncomingFileError.identity }
        self.transfers = outgoingTransfers
        self.limits = try SyncFileLimits(maximumFileBytes: limits.maximumFileBytes, maximumAppBytes: limits.maximumAppBytes, maximumTransfers: limits.maximumTransfers)
        self.journal = try FileSyncSnapshotStore(privateRoot: privateRoot)
        if let bytes = try journal.read() {
            let stored = try JSONDecoder().decode(IncomingFileJournal.self, from: bytes)
            guard [1,2,3,4,5,6,7].contains(stored.schema), stored.app == appID, stored.local == deviceID else { throw SyncIncomingFileError.identity }
            guard (stored.limits ?? .defaults) == limits, (stored.schema >= 7) == (stored.limits != nil) else { throw SyncFileLimitsError.changed }
        } else if limits != .defaults {
            let initial = IncomingFileJournal(schema: 7, app: appID, local: deviceID, offers: [], limits: limits)
            guard try journal.compareExchange(expected: nil, desired: JSONEncoder().encode(initial)) else { throw SyncIncomingFileError.conflict }
        }
        self.receiver = try SyncFileReceiver(privateRoot: privateRoot.appendingPathComponent("data"))
    }
    deinit { close() }
    public func close() { lock.lock(); defer { lock.unlock() }; closed = true; receiver.close(); journal.close() }
    private static func identity(_ value: String) throws {
        guard !value.isEmpty, value.utf8.count <= 128, value.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
        }) else { throw SyncIncomingFileError.identity }
    }
    private func validate(_ manifest: SyncFileManifest) throws {
        func hash(_ value: String) -> Bool { value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) } }
        guard !manifest.transferID.isEmpty, manifest.transferID.utf8.count <= 128, manifest.transferID.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,45].contains($0)
        }), manifest.size <= limits.maximumFileBytes, hash(manifest.sha256), manifest.chunkHashes.count == Int((manifest.size + 65535) / 65536),
        manifest.chunkHashes.allSatisfy(hash), manifest.mime.utf8.count <= 128,
        !manifest.mime.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw SyncIncomingFileError.manifest }
    }
    private func load() throws -> (Data?, IncomingFileJournal) {
        guard !closed else { throw SyncIncomingFileError.closed }
        let raw = try journal.read()
        guard let raw else { return (nil, IncomingFileJournal(schema: 1, app: app, local: local, offers: [])) }
        let value = try JSONDecoder().decode(IncomingFileJournal.self, from: raw)
        guard [1,2,3,4,5,6,7].contains(value.schema), value.app == app, value.local == local,
            value.offers.count + (value.sources?.count ?? 0) <= limits.maximumTransfers,
            value.schema >= 4 || value.sources == nil else { throw SyncIncomingFileError.journal }
        guard (value.limits ?? .defaults) == limits, (value.schema >= 7) == (value.limits != nil) else { throw SyncFileLimitsError.changed }
        var ids = Set<String>()
        for offer in value.offers { try Self.identity(offer.peer); try validate(offer.manifest)
            guard offer.peer != local, ids.insert(offer.manifest.transferID).inserted else { throw SyncIncomingFileError.identity }
            guard value.schema >= 2 || offer.remoteCancelled == nil,
                offer.remoteCancelled != true || [.complete,.cancelling,.cancelled].contains(offer.phase) else { throw SyncIncomingFileError.journal }
        }
        for source in value.sources ?? [] {
            try validate(source.manifest)
            guard value.schema >= 5 || source.phase != .importing else { throw SyncIncomingFileError.journal }
            guard value.schema >= 6 || (source.phase != .registering && source.peer == nil) else { throw SyncIncomingFileError.journal }
            if let peer = source.peer { try Self.identity(peer); guard peer != local else { throw SyncIncomingFileError.identity } }
            guard source.phase != .registering || source.peer != nil else { throw SyncIncomingFileError.journal }
            guard ids.insert(source.manifest.transferID).inserted else { throw SyncIncomingFileError.identity }
        }
        let reserved = value.offers.reduce(sourceCost(value)) { cost, offer in
            cost + ([.offered,.cancelled].contains(offer.phase) ? 0 : offer.manifest.size * (offer.phase == .complete ? 1 : 2))
        }
        guard reserved <= limits.maximumAppBytes else { throw SyncIncomingFileError.quota }
        guard value.schema >= 3 || value.requests == nil, (value.requests?.count ?? 0) <= 128 else { throw SyncIncomingFileError.journal }
        var peers = Set<String>()
        for receipt in value.requests ?? [] {
            try Self.identity(receipt.peer); try Self.identity(receipt.id)
            guard receipt.peer != local, peers.insert(receipt.peer).inserted else { throw SyncIncomingFileError.identity }
            let request = try SyncFileRequest.decode(receipt.request)
            if let reply = receipt.reply { _ = try SyncFileReply.decode(reply, request: request) }
        }
        guard raw.count + (value.requests ?? []).filter({ $0.reply == nil }).count * 6144 <= 4 * 1024 * 1024 else { throw SyncIncomingFileError.quota }
        return (raw,value)
    }
    private func save(_ value: IncomingFileJournal, raw: inout Data?) throws {
        var upgraded = value; upgraded.schema = 7; upgraded.limits = limits
        let bytes = try JSONEncoder().encode(upgraded)
        // Reserve room for each pending request's maximum base64 reply before
        // executing any effect; other journal writes preserve that reservation.
        let pending = (upgraded.requests ?? []).filter { $0.reply == nil }.count
        guard bytes.count + pending * 6144 <= 4 * 1024 * 1024 else { throw SyncIncomingFileError.quota }
        guard try journal.compareExchange(expected: raw, desired: bytes) else { throw SyncIncomingFileError.conflict }; raw = bytes
    }
    private func index(_ value: IncomingFileJournal, peer: String, id: String) throws -> Int {
        try Self.identity(peer)
        guard let index = value.offers.firstIndex(where: { $0.peer == peer && $0.manifest.transferID == id }) else { throw SyncIncomingFileError.unknown }; return index
    }
    private func recover(_ value: inout IncomingFileJournal, raw: inout Data?, index: Int) throws {
        let offer = value.offers[index]
        switch offer.phase {
        case .accepting:
            try receiver.prepareAccepted(offer.manifest); value.offers[index].phase = .accepted; try save(value, raw: &raw)
        case .completing:
            _ = try receiver.finish(transferID: offer.manifest.transferID); value.offers[index].phase = .complete; try save(value, raw: &raw)
        case .cancelling:
            try receiver.removeHostCopy(transferID: offer.manifest.transferID); value.offers[index].phase = .cancelled; try save(value, raw: &raw)
        default: break
        }
    }
    public func recover() throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load()
        for index in (value.sources ?? []).indices where value.sources?[index].phase == .removing { try recoverSource(&value, raw: &raw, index: index) }
        for index in value.offers.indices where value.offers[index].phase == .cancelling { try recover(&value, raw: &raw, index: index) }
        for index in value.offers.indices { try recover(&value, raw: &raw, index: index) }
        for index in (value.sources ?? []).indices { try recoverSource(&value, raw: &raw, index: index) }
    }
    public func listLocal() throws -> [SyncIncomingFile] { lock.lock(); defer { lock.unlock() }; return try load().1.offers }
    public func statusLocal(peer: String, transferID: String) throws -> SyncIncomingFileStatus {
        lock.lock(); defer { lock.unlock() }
        var (raw, value) = try load(); let index = try index(value, peer: peer, id: transferID)
        try recover(&value, raw: &raw, index: index)
        let offer = value.offers[index], manifest = offer.manifest
        var state: SyncIncomingFileStatus.State
        var received: UInt64 = 0
        switch offer.phase {
        case .offered: state = .offered
        case .cancelled, .cancelling: state = .cancelled
        case .complete:
            if try receiver.verifiedComplete(transferID: transferID) { state = .complete; received = manifest.size }
            else { state = .failed }
        case .accepted, .accepting, .completing:
            state = .transferring
            let missing = try receiver.missing(transferID: transferID)
            let absent = missing.reduce(UInt64(0)) { $0 + min(65536, manifest.size - UInt64($1) * 65536) }
            received = manifest.size - absent
        }
        return SyncIncomingFileStatus(transferId: transferID, state: state, receivedBytes: received, totalBytes: manifest.size)
    }
    /// Explicit host delivery to approved guest storage. Only incoming completed
    /// artifacts qualify, including a retained copy after remote cancellation.
    /// Does not return/expose a private source path or remove the received copy.
    public func saveCompleteLocal(peer: String, transferID: String, path: String, storage: SyncGuestFiles, cancellation: SyncCancellation? = nil) throws {
        try cancellation?.check()
        guard storage.matches(app: app) else { throw SyncGuestFilesError.identity }
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        try recover(&value, raw: &raw, index: index)
        guard value.offers[index].phase == .complete, try receiver.verifiedComplete(transferID: transferID) else { throw SyncIncomingFileError.consent }
        let source = try receiver.finish(transferID: transferID)
        try storage.publish(source: source, manifest: value.offers[index].manifest, path: path, cancellation: cancellation)
    }
    @discardableResult public func offerAuthenticated(peer: String, manifest: SyncFileManifest) throws -> SyncIncomingFile {
        try Self.identity(peer); try validate(manifest); guard peer != local else { throw SyncIncomingFileError.identity }
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load()
        if let existing = value.offers.first(where: { $0.manifest.transferID == manifest.transferID }) {
            guard existing.peer == peer, existing.manifest == manifest else { throw SyncIncomingFileError.identity }; return existing
        }
        guard !(value.sources ?? []).contains(where: { $0.manifest.transferID == manifest.transferID }) else { throw SyncIncomingFileError.identity }
        guard value.offers.count + (value.sources?.count ?? 0) < limits.maximumTransfers else { throw SyncIncomingFileError.quota }
        let offer = SyncIncomingFile(peer: peer, manifest: manifest, phase: .offered); value.offers.append(offer); try save(value, raw: &raw); return offer
    }
    /// Only local explicit consent calls this method; no remote accept operation.
    public func acceptLocal(peer: String, transferID: String) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        guard ![.cancelled,.cancelling].contains(value.offers[index].phase) else { throw SyncIncomingFileError.cancelled }
        if value.offers[index].phase == .offered {
            // Reserve temporary chunks plus final file, matching native quota.
            let cost = value.offers.reduce(sourceCost(value) + value.offers[index].manifest.size * 2) { cost, offer in
                cost + ([.offered,.cancelled].contains(offer.phase) ? 0 : offer.manifest.size * (offer.phase == .complete ? 1 : 2))
            }
            guard cost <= limits.maximumAppBytes else { throw SyncIncomingFileError.quota }
            value.offers[index].phase = .accepting; try save(value, raw: &raw)
        }
        try recover(&value, raw: &raw, index: index)
    }
    public func writeAuthenticated(peer: String, transferID: String, index chunk: Int, bytes: Data) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        guard value.offers[index].remoteCancelled != true else { throw SyncIncomingFileError.cancelled }
        try recover(&value, raw: &raw, index: index)
        guard [.accepted,.complete].contains(value.offers[index].phase) else { throw SyncIncomingFileError.consent }
        try admitRepair(&value, raw: &raw, index: index)
        try receiver.writeChunk(transferID: transferID, index: chunk, bytes: bytes)
    }
    public func missingAuthenticated(peer: String, transferID: String) throws -> [Int] {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        if value.offers[index].remoteCancelled == true { return [] }
        try recover(&value, raw: &raw, index: index)
        guard [.accepted,.complete].contains(value.offers[index].phase) else { throw SyncIncomingFileError.consent }
        return try receiver.missing(transferID: transferID)
    }
    /// Verifies bytes; returns a host-private artifact, never a guest path.
    public func finishAuthenticated(peer: String, transferID: String) throws -> URL {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        guard value.offers[index].remoteCancelled != true else { throw SyncIncomingFileError.cancelled }
        try recover(&value, raw: &raw, index: index)
        guard [.accepted,.complete].contains(value.offers[index].phase) else { throw SyncIncomingFileError.consent }
        try admitRepair(&value, raw: &raw, index: index)
        if value.offers[index].phase != .complete {
            // Check missing before committing completion intent to keep incomplete
            // transfers writable; finish additionally checks whole-file hash.
            guard try receiver.missing(transferID: transferID).isEmpty else { throw SyncIncomingFileError.consent }
            value.offers[index].phase = .completing; try save(value, raw: &raw); try recover(&value, raw: &raw, index: index)
        }
        return try receiver.finish(transferID: transferID)
    }
    private func admitRepair(_ value: inout IncomingFileJournal, raw: inout Data?, index: Int) throws {
        let offer = value.offers[index]
        guard offer.phase == .complete, try !receiver.verifiedComplete(transferID: offer.manifest.transferID) else { return }
        let cost = value.offers.reduce(sourceCost(value) + offer.manifest.size) {
            $0 + ([.offered,.cancelled].contains($1.phase) ? 0 : $1.manifest.size * ($1.phase == .complete ? 1 : 2))
        }
        guard cost <= limits.maximumAppBytes else { throw SyncIncomingFileError.quota }
        // Persist renewed two-copy reservation before native repair adds chunks.
        value.offers[index].phase = .accepted; try save(value, raw: &raw)
    }
    /// Guest/remote cancellation never removes a completed host artifact.
    public func cancelUnfinished(peer: String, transferID: String) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        if value.offers[index].phase == .completing { try recover(&value, raw: &raw, index: index) }
        if [.complete,.cancelled].contains(value.offers[index].phase) { return }
        value.offers[index].phase = value.offers[index].phase == .offered ? .cancelled : .cancelling
        try save(value, raw: &raw); try recover(&value, raw: &raw, index: index)
    }
    /// Explicit local host removal, INCLUDING completed artifacts. Never expose
    /// this as guest/remote cancel; it also permits cleanup of corrupt completion.
    public func removeLocal(peer: String, transferID: String) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        if value.offers[index].phase == .cancelled { return }
        value.offers[index].phase = value.offers[index].phase == .offered ? .cancelled : .cancelling
        try save(value, raw: &raw); try recover(&value, raw: &raw, index: index)
    }
    /// End the authenticated remote transfer without deleting a completed local
    /// copy. The persisted flag makes future wire status cancelled, while local
    /// history can still show the retained complete artifact.
    public func cancelAuthenticated(peer: String, transferID: String) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        if value.offers[index].phase == .completing { try recover(&value, raw: &raw, index: index) }
        if value.offers[index].phase != .complete && value.offers[index].phase != .cancelled {
            value.offers[index].phase = value.offers[index].phase == .offered ? .cancelled : .cancelling
        }
        value.offers[index].remoteCancelled = true; try save(value, raw: &raw); try recover(&value, raw: &raw, index: index)
    }
    public func wirePhase(peer: String, transferID: String) throws -> SyncFileWirePhase {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try index(value, peer: peer, id: transferID)
        try recover(&value, raw: &raw, index: index)
        if value.offers[index].remoteCancelled == true { return .cancelled }
        if value.offers[index].phase == .complete, try !receiver.missing(transferID: transferID).isEmpty { return .accepted }
        guard let phase = SyncFileWirePhase(rawValue: value.offers[index].phase.rawValue) else { throw SyncIncomingFileError.journal }; return phase
    }
    /// Host authenticated request executor. Does not grant consent or publish a
    /// path. A separate request-ID replay journal/connection owns retransmission.
    public func executeAuthenticated(peer: String, request: SyncFileRequest) throws -> SyncFileReply {
        let id = request.transferID
        if request.method == .offer {
            guard let manifest = request.manifest else { throw SyncIncomingFileError.manifest }
            _ = try offerAuthenticated(peer: peer, manifest: manifest)
        } else if request.method == .cancel {
            try cancelAuthenticated(peer: peer, transferID: id)
        } else if request.method == .chunk {
            if try wirePhase(peer: peer, transferID: id) != .cancelled {
                guard let index = request.index, let bytes = request.chunk else { throw SyncIncomingFileError.manifest }
                try writeAuthenticated(peer: peer, transferID: id, index: index, bytes: bytes)
            }
        } else if request.method == .finish {
            if try wirePhase(peer: peer, transferID: id) != .cancelled { _ = try finishAuthenticated(peer: peer, transferID: id) }
        }
        let phase = try wirePhase(peer: peer, transferID: id)
        let missing: [Int]? = request.method == .missing ? (phase == .cancelled ? [] : try missingAuthenticated(peer: peer, transferID: id)) : nil
        return try SyncFileReply.make(request: request, phase: phase, missing: missing)
    }
    /// One retained latest request per peer, for sequential request/response
    /// transports. Caller binds peer/ID and duplicateFrame to a verified frame.
    /// Unknown older transport duplicates never reexecute. New connections may
    /// replay the retained request; senders must never reuse consumed IDs.
    public func receiveAuthenticatedRequest(peer: String, requestID: String, request: SyncFileRequest,
                                            duplicateFrame: Bool = false) throws -> SyncFileReply {
        try Self.identity(peer); try Self.identity(requestID); guard peer != local else { throw SyncIncomingFileError.identity }
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load()
        var receipts = value.requests ?? []
        let position: Int
        if let index = receipts.firstIndex(where: { $0.peer == peer }) {
            let previous = receipts[index]
            if previous.id == requestID {
                guard previous.request == request.originalJSON else { throw SyncIncomingFileError.identity }
                if let reply = previous.reply { return try SyncFileReply.decode(reply, request: request) }
            } else {
                guard !duplicateFrame, previous.reply != nil else { throw SyncIncomingFileError.conflict }
                receipts[index] = IncomingFileReceipt(peer: peer, id: requestID, request: request.originalJSON, reply: nil)
            }
            position = index
        } else {
            guard !duplicateFrame, receipts.count < 128 else { throw SyncIncomingFileError.quota }
            position = receipts.count; receipts.append(IncomingFileReceipt(peer: peer, id: requestID, request: request.originalJSON, reply: nil))
        }
        value.requests = receipts; try save(value, raw: &raw)
        // Operations are idempotent with their persisted consent/finish/cancel
        // intent. On error the pending request remains and blocks a new ID.
        let reply = try executeAuthenticated(peer: peer, request: request)
        var (latestRaw,latest) = try load()
        guard let current = latest.requests, current.indices.contains(position), current[position].id == requestID,
              current[position].request == request.originalJSON else { throw SyncIncomingFileError.conflict }
        latest.requests?[position].reply = reply.originalJSON; try save(latest, raw: &latestRaw)
        return reply
    }
    // Sources and incoming files share the same physical receiver, journal,
    // lease, ID namespace and admission lock. Reserve two copies for all live
    // sources, including completed ones, so corruption cannot undercount repair.
    private func sourceCost(_ value: IncomingFileJournal) -> UInt64 {
        (value.sources ?? []).reduce(0) { $0 + ($1.phase == .removed ? 0 : $1.manifest.size * 2) }
    }
    private func sourceIndex(_ value: IncomingFileJournal, id: String) throws -> Int {
        guard let index = value.sources?.firstIndex(where: { $0.manifest.transferID == id }) else { throw SyncIncomingFileError.unknown }; return index
    }
    private func recoverSource(_ value: inout IncomingFileJournal, raw: inout Data?, index: Int) throws {
        guard let source = value.sources?[index] else { throw SyncIncomingFileError.journal }
        switch source.phase {
        case .registering:
            guard let transfers, let peer = source.peer else { throw SyncIncomingFileError.journal }
            let records = try transfers.list().filter { $0.manifest.transferID == source.manifest.transferID }
            if let record = records.first {
                guard records.count == 1, record.peer == peer, record.manifest == source.manifest else { throw SyncIncomingFileError.identity }
                value.sources?[index].phase = .complete
            } else {
                value.sources?[index].phase = .removing; try save(value, raw: &raw)
                try receiver.removeHostCopy(transferID: source.manifest.transferID); value.sources?[index].phase = .removed
            }
        case .importing:
            value.sources?[index].phase = .removing; try save(value, raw: &raw)
            try receiver.removeHostCopy(transferID: source.manifest.transferID); value.sources?[index].phase = .removed
        case .preparing:
            try receiver.prepareAccepted(source.manifest); value.sources?[index].phase = .staging
        case .completing:
            _ = try receiver.finish(transferID: source.manifest.transferID); value.sources?[index].phase = .complete
        case .removing:
            try receiver.removeHostCopy(transferID: source.manifest.transferID); value.sources?[index].phase = .removed
        default: return
        }
        try save(value, raw: &raw)
    }
    func listSources() throws -> [SyncOutgoingSource] { lock.lock(); defer { lock.unlock() }; return try load().1.sources ?? [] }
    func recoverOutgoingImports() throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load()
        for index in (value.sources ?? []).indices where [.importing,.registering,.removing].contains(value.sources![index].phase) {
            try recoverSource(&value, raw: &raw, index: index)
        }
    }
    func registerSource(peer: String, id: String, transfers: SyncOutgoingTransfers) throws -> SyncOutgoingTransfer {
        guard transfers.matchesIdentity(app: app, device: local) else { throw SyncOutgoingTransferError.identity }
        lock.lock(); defer { lock.unlock() }; let (_,value) = try load(); let index = try sourceIndex(value, id: id)
        guard let source = value.sources?[index], source.phase == .complete else { throw SyncIncomingFileError.consent }
        // Hold shared source ownership until peer binding is durably registered.
        _ = try receiver.finish(transferID: id)
        return try transfers.register(peer: peer, manifest: source.manifest)
    }
    func prepareSource(_ manifest: SyncFileManifest) throws {
        try validate(manifest); lock.lock(); defer { lock.unlock() }; var (raw,value) = try load()
        if let existing = (value.sources ?? []).first(where: { $0.manifest.transferID == manifest.transferID }) {
            guard existing.manifest == manifest, ![.importing,.removed,.removing].contains(existing.phase) else { throw SyncIncomingFileError.identity }
            try recoverSource(&value, raw: &raw, index: sourceIndex(value, id: manifest.transferID)); return
        }
        guard !value.offers.contains(where: { $0.manifest.transferID == manifest.transferID }) else { throw SyncIncomingFileError.identity }
        let cost = value.offers.reduce(sourceCost(value) + manifest.size * 2) {
            $0 + ([.offered,.cancelled].contains($1.phase) ? 0 : $1.manifest.size * ($1.phase == .complete ? 1 : 2))
        }
        guard cost <= limits.maximumAppBytes, value.offers.count + (value.sources?.count ?? 0) < limits.maximumTransfers else { throw SyncIncomingFileError.quota }
        var sources = value.sources ?? []; sources.append(SyncOutgoingSource(manifest: manifest, phase: .preparing)); value.sources = sources
        try save(value, raw: &raw); try recoverSource(&value, raw: &raw, index: sources.count - 1)
    }
    func writeSource(id: String, index: Int, bytes: Data) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let position = try sourceIndex(value, id: id)
        try recoverSource(&value, raw: &raw, index: position)
        guard value.sources?[position].phase == .staging else { throw SyncIncomingFileError.consent }
        try receiver.writeChunk(transferID: id, index: index, bytes: bytes)
    }
    func finishSource(id: String) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try sourceIndex(value, id: id)
        try recoverSource(&value, raw: &raw, index: index)
        guard let phase = value.sources?[index].phase, [.staging,.complete].contains(phase) else { throw SyncIncomingFileError.consent }
        guard try receiver.missing(transferID: id).isEmpty else { throw SyncIncomingFileError.consent }
        if phase == .staging { value.sources?[index].phase = .completing; try save(value, raw: &raw); try recoverSource(&value, raw: &raw, index: index) }
        else { _ = try receiver.finish(transferID: id) }
    }
    func readSource(id: String, index: Int) throws -> Data {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let position = try sourceIndex(value, id: id)
        try recoverSource(&value, raw: &raw, index: position)
        guard value.sources?[position].phase == .complete else { throw SyncIncomingFileError.consent }
        return try receiver.readCompleteChunk(transferID: id, index: index)
    }
    func removeSource(id: String) throws {
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load(); let index = try sourceIndex(value, id: id)
        if value.sources?[index].phase == .removed { return }
        value.sources?[index].phase = .removing; try save(value, raw: &raw); try recoverSource(&value, raw: &raw, index: index)
    }
    func importSource(_ source: SyncHostFileSource, peer: String? = nil) throws -> SyncFileManifest {
        try source.check()
        let manifest = source.manifest; try validate(manifest)
        if let peer { try Self.identity(peer); guard peer != local, transfers != nil else { throw SyncIncomingFileError.identity } }
        lock.lock(); defer { lock.unlock() }; var (raw,value) = try load()
        // Cancellation may arrive while waiting for the shared owner or reading
        // its journal. Do not allocate a retained ID for that cancelled import.
        try source.check()
        // Acquisition of the shared lease/lock proves no importer is still
        // executing against these abandoned intents. Release their space first.
        for index in (value.sources ?? []).indices where [.importing,.registering,.removing].contains(value.sources![index].phase) {
            try source.check()
            try recoverSource(&value, raw: &raw, index: index)
        }
        guard !value.offers.contains(where: { $0.manifest.transferID == manifest.transferID }),
              !(value.sources ?? []).contains(where: { $0.manifest.transferID == manifest.transferID }) else { throw SyncIncomingFileError.identity }
        let cost = value.offers.reduce(sourceCost(value) + manifest.size * 2) {
            $0 + ([.offered,.cancelled].contains($1.phase) ? 0 : $1.manifest.size * ($1.phase == .complete ? 1 : 2))
        }
        guard cost <= limits.maximumAppBytes, value.offers.count + (value.sources?.count ?? 0) < limits.maximumTransfers else { throw SyncIncomingFileError.quota }
        try source.check()
        var sources = value.sources ?? []; let index = sources.count
        sources.append(SyncOutgoingSource(manifest: manifest, phase: .importing)); value.sources = sources
        do {
            try save(value, raw: &raw); try receiver.prepareAccepted(manifest)
            for block in manifest.chunkHashes.indices {
                try receiver.writeChunk(transferID: manifest.transferID, index: block, bytes: source.read(index: block))
            }
            try source.check()
            _ = try receiver.finish(transferID: manifest.transferID)
            try source.check()
            if let peer, let transfers {
                value.sources?[index].peer = peer; value.sources?[index].phase = .registering; try save(value, raw: &raw)
                try source.check()
                _ = try transfers.register(peer: peer, manifest: manifest)
            }
            value.sources?[index].phase = .complete; try save(value, raw: &raw)
            return manifest
        } catch {
            // Re-read an uncertain commit. Never remove a completed source whose
            // durable publication succeeded, or overwrite an unrelated record.
            if var (latestRaw,latest) = try? load(),
               let position = latest.sources?.firstIndex(where: { $0.manifest == manifest && [.importing,.registering].contains($0.phase) }) {
                try? recoverSource(&latest, raw: &latestRaw, index: position)
            }
            throw error
        }
    }
}
private struct IncomingFileJournal: Codable { var schema: Int; let app: String; let local: String; var offers: [SyncIncomingFile]; var requests: [IncomingFileReceipt]? = nil; var sources: [SyncOutgoingSource]? = nil; var limits: SyncFileLimits? = nil }
private struct IncomingFileReceipt: Codable { let peer: String; let id: String; let request: Data; var reply: Data? }
