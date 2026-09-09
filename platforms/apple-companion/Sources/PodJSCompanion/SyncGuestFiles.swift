import Foundation
import CPodJSSync

public enum SyncGuestFilesError: Error, Equatable, Sendable { case identity, root, closed, busy, publish }
/// Host-approved runtime data root and app identity, never guest-supplied paths.
/// Runtime files/ must already exist. Every runtime/host filesystem writer must
/// share this gate; watch host source does so, but Apple SDK acceptance is pending.
public final class SyncGuestFiles: @unchecked Sendable {
    private let lock = NSLock()
    private let app: String
    private var gate: OpaquePointer?
    public init(appID: String, runtimeDataRoot: URL) throws {
        guard !appID.isEmpty, appID.utf8.count <= 128, appID.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
        }) else { throw SyncGuestFilesError.identity }
        guard runtimeDataRoot.isFileURL, !runtimeDataRoot.path.utf8.contains(0) else { throw SyncGuestFilesError.root }
        guard let gate = runtimeDataRoot.path.withCString({ pod_guest_io_open($0) }) else { throw SyncGuestFilesError.root }
        self.app = appID; self.gate = gate
    }
    deinit { close() }
    public func close() { lock.lock(); defer { lock.unlock() }; if let gate { pod_guest_io_close(gate); self.gate = nil } }
    func matches(app: String) -> Bool { self.app == app }
    func publish(source: URL, manifest: SyncFileManifest, path: String, cancellation: SyncCancellation? = nil) throws {
        try cancellation?.check()
        guard source.isFileURL, !source.path.utf8.contains(0), !path.utf8.contains(0), path.utf8.count <= 1024 else { throw SyncGuestFilesError.publish }
        lock.lock(); defer { lock.unlock() }; guard let gate else { throw SyncGuestFilesError.closed }
        let entered = pod_guest_io_try_enter(gate)
        guard entered == 1 else { throw entered == 0 ? SyncGuestFilesError.busy : SyncGuestFilesError.publish }
        defer { pod_guest_io_leave(gate) }
        let result = withSyncCancellation(cancellation) { token in source.path.withCString { source in path.withCString { path in manifest.sha256.withCString { hash in
            pod_guest_publish_cancellable(gate, source, path, manifest.size, hash, token)
        } } } }
        guard result == 0 else { try cancellation?.check(); throw SyncGuestFilesError.publish }
    }
}
