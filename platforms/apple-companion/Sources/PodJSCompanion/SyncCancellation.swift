import Foundation
import CPodJSSync

public enum SyncCancellationError: Error, Equatable, Sendable { case cancelled, unavailable }
/// One-way cancellation shared safely with native IO workers. Already committed
/// publication/registration is retained; cancellation cannot undo that commit.
public final class SyncCancellation: @unchecked Sendable {
    private let handle: OpaquePointer
    public init() throws {
        guard let handle = pod_sync_cancellation_new() else { throw SyncCancellationError.unavailable }; self.handle = handle
    }
    deinit { pod_sync_cancellation_free(handle) }
    public func cancel() { withNative { pod_sync_cancellation_cancel($0) } }
    public var isCancelled: Bool { withNative { pod_sync_cancellation_is_cancelled($0) } }
    func check() throws { if isCancelled { throw SyncCancellationError.cancelled } }
    func withNative<T>(_ body: (OpaquePointer?) throws -> T) rethrows -> T { try withExtendedLifetime(self) { try body(handle) } }
}
func withSyncCancellation<T>(_ value: SyncCancellation?, _ body: (OpaquePointer?) throws -> T) rethrows -> T {
    if let value { return try value.withNative(body) }; return try body(nil)
}
