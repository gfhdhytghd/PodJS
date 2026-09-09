import Foundation

public struct SyncGuestServiceReply: Equatable, Sendable {
    public let json: Data
    fileprivate let id: Int32
    fileprivate let token: UUID
}

/// Bounded foreground service scheduling, without touching a runtime pointer.
/// The host serializes runtime poll/post calls itself and retains an effect when
/// submit returns false. Reply acknowledgement means queued, not guest execution.
public final class SyncGuestServiceQueue: @unchecked Sendable {
    typealias Handler = @Sendable (String, Data, SyncCancellation) throws -> Data
    private let lock = NSLock()
    private let worker: DispatchQueue
    private let handler: Handler
    private var closed = false
    private var jobs: [UUID: SyncCancellation] = [:]
    private var active: [Int32: UUID] = [:]
    private var replies: [Int32: SyncGuestServiceReply] = [:]

    public convenience init(incomingFiles: SyncIncomingFileServices) {
        self.init(worker: DispatchQueue(label: "dev.podjs.guest-services")) { method, args, token in
            try incomingFiles.handle(method: method, arguments: args, cancellation: token)
        }
    }
    public convenience init(state: SyncStateServices) {
        self.init(worker: DispatchQueue(label: "dev.podjs.guest-state-services")) { method, args, token in
            try state.handle(method: method, arguments: args, cancellation: token)
        }
    }
    init(worker: DispatchQueue, handler: @escaping Handler) { self.worker = worker; self.handler = handler }
    deinit { close() }
    /// Unrecognized/malformed effects are consumed here; route non-service
    /// effects before calling this method. No guest handler executes inline.
    @discardableResult public func submit(_ raw: Data) throws -> Bool {
        guard raw.count <= 128 * 1024, let envelope = try? JSONDecoder().decode(Envelope.self, from: raw), envelope.id > 0,
              ["service.request", "service.cancel"].contains(envelope.t) else { return true }
        lock.lock(); defer { lock.unlock() }
        guard !closed else { return true }
        let id = envelope.id
        if envelope.t == "service.cancel" {
            if let key = active.removeValue(forKey: id) { jobs[key]?.cancel() }
            replies.removeValue(forKey: id)
            return true
        }
        if active[id] != nil || replies[id] != nil { return true }
        // Cancelled queued work still occupies capacity until the worker drains
        // it; repeated cancel/submit cannot build an unbounded dispatch backlog.
        guard jobs.count + replies.count < 64 else { return false }
        let key = UUID()
        guard envelope.version == 1 else {
            replies[id] = failure(id: id, token: key, code: "unsupported"); return true
        }
        guard let method = envelope.method, !method.isEmpty,
              let object = try? JSONSerialization.jsonObject(with: raw) as? [String: Any],
              let args = object["args"] as? [String: Any] else {
            replies[id] = failure(id: id, token: key, code: "invalid_argument"); return true
        }
        let arguments = try JSONSerialization.data(withJSONObject: args)
        let cancellation = try SyncCancellation(), operation = handler
        jobs[key] = cancellation; active[id] = key
        worker.async { [weak self] in
            let result: Result<Data, Error>
            do { try cancellation.check(); result = .success(try operation(method, arguments, cancellation)) }
            catch { result = .failure(error) }
            self?.finish(id: id, token: key, result: result)
        }
        return true
    }
    public func nextReply() -> SyncGuestServiceReply? {
        lock.lock(); defer { lock.unlock() }
        return replies.keys.min().flatMap { replies[$0] }
    }
    @discardableResult public func acknowledgeReply(_ reply: SyncGuestServiceReply) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !closed, replies[reply.id] == reply else { return false }
        replies.removeValue(forKey: reply.id); return true
    }
    public func close() {
        lock.lock(); defer { lock.unlock() }; closed = true
        for token in jobs.values { token.cancel() }
        active.removeAll(); replies.removeAll()
    }
    private func finish(id: Int32, token: UUID, result: Result<Data, Error>) {
        lock.lock(); defer { lock.unlock() }; jobs.removeValue(forKey: token)
        guard !closed, active[id] == token else { return }
        active.removeValue(forKey: id)
        switch result {
        case .success(let value):
            guard value.count <= 1024 * 1024,
                  let parsed = try? JSONSerialization.jsonObject(with: value, options: [.fragmentsAllowed]),
                  let bytes = try? JSONSerialization.data(withJSONObject: ["t": "service.result", "id": id, "ok": true, "value": parsed]), bytes.count <= 1024 * 1024 else {
                replies[id] = failure(id: id, token: token, code: "host_error"); return
            }
            replies[id] = SyncGuestServiceReply(json: bytes, id: id, token: token)
        case .failure(let error):
            let code: String
            switch error {
            case SyncIncomingFileServiceError.unsupported: code = "unsupported"
            case SyncIncomingFileServiceError.argument: code = "invalid_argument"
            case SyncStateServiceError.unsupported: code = "unsupported"
            case SyncStateServiceError.argument: code = "invalid_argument"
            case SyncGuestFilesError.busy: code = "busy"
            case SyncCancellationError.cancelled: code = "cancelled"
            default: code = "host_error"
            }
            replies[id] = failure(id: id, token: token, code: code)
        }
    }
    private func failure(id: Int32, token: UUID, code: String) -> SyncGuestServiceReply {
        // All interpolated values are host-owned constants or bounded integers.
        SyncGuestServiceReply(json: Data("{\"t\":\"service.result\",\"id\":\(id),\"ok\":false,\"code\":\"\(code)\",\"message\":\"Host operation failed\"}".utf8), id: id, token: token)
    }
}
private struct Envelope: Decodable {
    let t: String
    let id: Int32
    let version: Int?
    let method: String?
    private enum CodingKeys: String, CodingKey { case t, id, version, method }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        t = try values.decode(String.self, forKey: .t); id = try values.decode(Int32.self, forKey: .id)
        version = try? values.decode(Int.self, forKey: .version)
        method = try? values.decode(String.self, forKey: .method)
    }
}
