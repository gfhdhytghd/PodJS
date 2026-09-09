import Foundation

public enum SyncIncomingFileServiceError: Error, Equatable { case unsupported, argument, notExposed, unknown, identity }

public struct SyncIncomingFileEvent: Equatable, Sendable {
    public let json: Data
    fileprivate let token: UUID
    fileprivate let peer: String
    fileprivate let status: SyncIncomingFileStatus
}
private struct IncomingEventEnvelope: Encodable {
    let t = "sync.file.changed"
    let value: SyncIncomingFileStatus
}

/// Incoming guest service adapter. The host authorizes the installed app before
/// constructing this object, supplies its own files/storage, and dispatches off
/// the runtime thread (save acquires that runtime's guest IO gate).
/// Recreate on guest restart: consent operations require status exposure in this
/// guest lifetime. Does not grant a capability or implement OS request scheduling.
public final class SyncIncomingFileServices: @unchecked Sendable {
    private let lock = NSLock()
    private let files: SyncIncomingFiles
    private let storage: SyncGuestFiles
    private var exposed: [String: String] = [:]
    private var eventsActive = false
    private var eventCursor = ""
    private var emitted: [String: SyncIncomingFileStatus] = [:]
    private var pendingEvent: SyncIncomingFileEvent?
    public init(files: SyncIncomingFiles, storage: SyncGuestFiles) throws {
        guard files.matchesStorage(storage) else { throw SyncIncomingFileServiceError.identity }
        self.files = files; self.storage = storage
    }
    public func setEventsActive(_ active: Bool) {
        lock.lock(); defer { lock.unlock() }
        guard active != eventsActive else { return }
        eventsActive = active; eventCursor = ""; emitted.removeAll(); pendingEvent = nil
    }
    /// One outstanding delivery; repeat calls return exactly the same bytes until
    /// acknowledged. Scans at most 64 retained IDs per call. Nil can mean a quiet
    /// page, so the host must continue bounded polling while subscribed.
    public func nextEvent() throws -> SyncIncomingFileEvent? {
        lock.lock(); defer { lock.unlock() }
        guard eventsActive else { return nil }
        if let pendingEvent { return pendingEvent }
        let offers = try files.listLocal().sorted { $0.manifest.transferID < $1.manifest.transferID }
        let page = offers.filter { $0.manifest.transferID > eventCursor }.prefix(64)
        for offer in page {
            let id = offer.manifest.transferID
            let status = try files.statusLocal(peer: offer.peer, transferID: id)
            if emitted[id] != status {
                let event = SyncIncomingFileEvent(json: try JSONEncoder().encode(IncomingEventEnvelope(value: status)), token: UUID(), peer: offer.peer, status: status)
                pendingEvent = event
                return event
            }
            eventCursor = id
        }
        if page.count < 64 || eventCursor == offers.last?.manifest.transferID { eventCursor = "" }
        return nil
    }
    /// Call only after the host event queue accepted these bytes. Never call on
    /// backpressure. Stale deliveries after subscription reset cannot expose IDs.
    @discardableResult public func acknowledgeEvent(_ event: SyncIncomingFileEvent) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard eventsActive, pendingEvent == event else { return false }
        let id = event.status.transferId
        emitted[id] = event.status; exposed[id] = event.peer
        eventCursor = id; pendingEvent = nil
        return true
    }
    /// Returns the service value JSON; cancel returns JSON null. The host owns
    /// request IDs, error envelopes, cancellation token lifetime and reply delivery.
    public func handle(method: String, arguments: Data, cancellation: SyncCancellation? = nil) throws -> Data {
        guard ["sync.files.status", "sync.files.accept", "sync.files.cancel", "sync.files.save"].contains(method) else { throw SyncIncomingFileServiceError.unsupported }
        guard arguments.count <= 4096,
              let args = try JSONSerialization.jsonObject(with: arguments) as? [String: Any],
              Set(args.keys) == (method == "sync.files.save" ? Set(["transferId", "path"]) : Set(["transferId"])),
              let id = args["transferId"] as? String, !id.isEmpty, id.utf8.count <= 128,
              id.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,45].contains($0) }) else { throw SyncIncomingFileServiceError.argument }
        let path = args["path"] as? String
        if method == "sync.files.save" {
            guard let path, !path.isEmpty, path.utf8.count <= 1024, !path.utf8.contains(0) else { throw SyncIncomingFileServiceError.argument }
        }
        lock.lock(); defer { lock.unlock() }; try cancellation?.check()
        let matches = try files.listLocal().filter { $0.manifest.transferID == id }
        guard matches.count == 1 else { throw SyncIncomingFileServiceError.unknown }
        let offer = matches[0]
        if method != "sync.files.status", exposed[id] != offer.peer { throw SyncIncomingFileServiceError.notExposed }
        switch method {
        case "sync.files.accept": try files.acceptLocal(peer: offer.peer, transferID: id)
        case "sync.files.cancel":
            try files.cancelUnfinished(peer: offer.peer, transferID: id); return Data("null".utf8)
        case "sync.files.save":
            try files.saveCompleteLocal(peer: offer.peer, transferID: id, path: path!, storage: storage, cancellation: cancellation)
            return try JSONSerialization.data(withJSONObject: ["path": path!, "size": offer.manifest.size])
        default: break
        }
        let status = try files.statusLocal(peer: offer.peer, transferID: id)
        let result = try JSONEncoder().encode(status)
        exposed[id] = offer.peer
        return result
    }
}
