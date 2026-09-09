import Foundation
import CPodJSSync

/// Borrows a runtime; never destroys it or executes a guest frame. The host must
/// call all methods on the runtime's single executor, outside its guest IO gate,
/// and close this pump before destroying the runtime. Not Sendable intentionally.
/// Construction does not authorize services or advertise a sync capability.
public final class SyncGuestRuntimePump {
    private var runtime: OpaquePointer?
    private let queue: SyncGuestServiceQueue
    private let incomingFiles: SyncIncomingFileServices?
    private var pendingEffect: Data?
    private var pumping = false
    public convenience init(runtime: OpaquePointer, incomingFiles: SyncIncomingFileServices) throws {
        guard "companion.sync.file".withCString({ pod_runtime_has_capability(runtime, $0) }) else { throw SyncIncomingFileServiceError.unsupported }
        self.init(runtime: runtime, queue: SyncGuestServiceQueue(incomingFiles: incomingFiles), incomingFiles: incomingFiles)
    }
    public convenience init(runtime: OpaquePointer, state: SyncStateServices) throws {
        guard "companion.sync.state".withCString({ pod_runtime_has_capability(runtime, $0) }) else { throw SyncStateServiceError.unsupported }
        self.init(runtime: runtime, queue: SyncGuestServiceQueue(state: state))
    }
    init(runtime: OpaquePointer, queue: SyncGuestServiceQueue, incomingFiles: SyncIncomingFileServices? = nil) {
        self.runtime = runtime; self.queue = queue; self.incomingFiles = incomingFiles
    }
    deinit { close() }
    public func setFileEventsActive(_ active: Bool) { if runtime != nil { incomingFiles?.setEventsActive(active) } }
    public func close() {
        runtime = nil; pendingEffect = nil; queue.close(); incomingFiles?.setEventsActive(false)
    }
    /// At most 64 effects, replies and file events each per call. The host still
    /// drives frames to drain native events. FIFO backpressure can delay a cancel
    /// behind a blocked request; lifecycle shutdown must call close directly.
    public func pump(otherEffect: (Data) -> Void = { _ in }) throws {
        guard runtime != nil, !pumping else { return }
        pumping = true; defer { pumping = false }
        var replyBudget = 64
        flushReplies(budget: &replyBudget)
        for _ in 0..<64 {
            guard let runtime else { return }
            let raw: Data
            if let pendingEffect { raw = pendingEffect }
            else {
                guard let value = pod_runtime_poll_effect(runtime) else { break }
                raw = Data(String(cString: value).utf8) // Copy before next poll.
            }
            let object = try? JSONSerialization.jsonObject(with: raw) as? [String: Any]
            if let kind = object?["t"] as? String, ["service.request", "service.cancel"].contains(kind) {
                pendingEffect = raw
                guard try queue.submit(raw) else { break }
                pendingEffect = nil
            } else {
                pendingEffect = nil; otherEffect(raw)
            }
        }
        flushReplies(budget: &replyBudget)
        guard runtime != nil, let incomingFiles else { return }
        for _ in 0..<64 {
            guard let event = try incomingFiles.nextEvent() else { break }
            guard post(event.json) else { break }
            _ = incomingFiles.acknowledgeEvent(event)
        }
    }
    private func flushReplies(budget: inout Int) {
        while budget > 0 {
            guard let reply = queue.nextReply(), post(reply.json) else { return }
            _ = queue.acknowledgeReply(reply)
            budget -= 1
        }
    }
    private func post(_ data: Data) -> Bool {
        guard let runtime, let json = String(data: data, encoding: .utf8), !json.utf8.contains(0) else { return false }
        return json.withCString { pod_runtime_post_event(runtime, $0) == 0 }
    }
}
