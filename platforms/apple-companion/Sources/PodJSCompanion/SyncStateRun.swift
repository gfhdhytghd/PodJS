import Foundation
import Dispatch

public enum SyncStateRunError: Error, Equatable, Sendable { case duration, stopped, deadline }
/// Explicit foreground state run. Call step after local changes and periodically
/// for deadline enforcement; feed complete received frames to receive. This is
/// synchronous IO-worker code, not a main-thread or background service.
///
/// write must be bounded, non-reentrant, and finish sending the entire frame in
/// order or throw. closeIO must be non-reentrant and release the transport.
/// All signing, writes, receive processing and close share one serialization
/// lock. Consequently close/deadline cannot interrupt a blocking host write;
/// the host must impose its own IO timeout. No implicit reconnect is attempted.
/// A synchronous state subscriber must not call this run inline: enqueue that
/// wakeup on the host IO worker, as receive may currently hold the run lock.
public final class SyncStateRun: @unchecked Sendable {
    private let lock = NSLock()
    private let pump: SyncStatePump
    private let state: SyncState
    private let peer: String
    private let write: @Sendable (Data) throws -> Void
    private let closeIO: @Sendable () -> Void
    private let now: @Sendable () -> UInt64
    private let deadline: UInt64
    private var stopped = false
    private var inFlight = false
    public init(session: SyncSession, state: SyncState, durationMilliseconds: UInt64,
                write: @escaping @Sendable (Data) throws -> Void,
                closeIO: @escaping @Sendable () -> Void,
                now: @escaping @Sendable () -> UInt64 = { DispatchTime.now().uptimeNanoseconds }) throws {
        guard (100...120_000).contains(durationMilliseconds) else { throw SyncStateRunError.duration }
        let start = now(), interval = durationMilliseconds * 1_000_000
        guard start <= UInt64.max - interval else { throw SyncStateRunError.duration }
        self.deadline = start + interval; self.now = now; self.write = write; self.closeIO = closeIO
        self.pump = try SyncStatePump(session: session, state: state); self.state = state; self.peer = session.peerID
    }
    deinit { close() }
    public func close() { lock.lock(); defer { lock.unlock() }; stop() }
    private func stop() {
        guard !stopped else { return }; stopped = true; pump.close(); closeIO()
    }
    private func check() throws {
        guard !stopped else { throw SyncStateRunError.stopped }
        guard now() < deadline else { throw SyncStateRunError.deadline }
    }
    private func perform<T>(_ operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        do { try check(); return try operation() } catch { stop(); throw error }
    }
    private func sendNext() throws {
        guard !inFlight else { return }
        if let frame = try pump.sendNext() { inFlight = true; try write(frame); try check() }
    }
    /// Send at most one pending state batch. True is an observed durable local
    /// ACK barrier at this instant; a later local/remote edit invalidates it.
    @discardableResult public func step() throws -> Bool {
        try perform { try sendNext(); return try state.acknowledgement(peer: peer).synchronized }
    }
    /// Returns the receive result and schedules the next state batch only after
    /// any reply has been written. ACKs cannot overtake an earlier signed frame.
    @discardableResult public func receive(frameJSON: Data) throws -> SyncStatePumpResult.Status {
        try perform {
            let result = try pump.receive(frameJSON: frameJSON)
            if let reply = result.reply { try write(reply); try check() }
            if result.status == .ack { inFlight = false }
            try sendNext(); return result.status
        }
    }
}
