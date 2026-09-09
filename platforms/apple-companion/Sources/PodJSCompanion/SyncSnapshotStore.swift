import Foundation
import CPodJSJournal

public protocol SyncSnapshotStore: Sendable {
    func read() throws -> Data?
    func compareExchange(expected: Data?, desired: Data) throws -> Bool
}
public enum SyncSnapshotError: Error, Equatable, Sendable {
    case invalidRoot, unavailableOrOwned, closed, tooLarge, storageFailure
}

/// One host-selected OS-private namespace; existing parent must be trusted.
/// The lifetime lease serializes cooperating processes. CAS still rejects stale
/// callers within that owner. Errors after rename may mean an uncertain commit:
/// re-read and reconcile instead of assuming the old snapshot is still present.
public final class FileSyncSnapshotStore: SyncSnapshotStore, @unchecked Sendable {
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private let maximumBytes: Int
    /// Default state snapshots remain 4 MiB. A separate message namespace may
    /// opt into up to 18 MiB for encoded 8 MiB payload queues plus metadata.
    public init(privateRoot: URL, maximumBytes: Int = 4 * 1024 * 1024) throws {
        guard (1...18 * 1024 * 1024).contains(maximumBytes) else { throw SyncSnapshotError.tooLarge }
        self.maximumBytes = maximumBytes
        let root = privateRoot.standardizedFileURL
        guard privateRoot.isFileURL, root.path != "/", !root.path.utf8.contains(0) else { throw SyncSnapshotError.invalidRoot }
        let lockURL = root.deletingLastPathComponent().appendingPathComponent(".podjs-journal-" + root.lastPathComponent + ".lock")
        handle = root.path.withCString { path in lockURL.path.withCString { pod_apple_journal_open_bounded(path, $0, maximumBytes) } }
        guard handle != nil else { throw SyncSnapshotError.unavailableOrOwned }
    }
    deinit { close() }
    public func close() {
        lock.lock(); defer { lock.unlock() }
        if let value = handle { handle = nil; pod_apple_journal_close(value) }
    }
    public func read() throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard let handle else { throw SyncSnapshotError.closed }
        var bytes: UnsafeMutablePointer<UInt8>?, length = 0
        let result = pod_apple_journal_read(handle, &bytes, &length)
        defer { pod_apple_journal_free(bytes) }
        if result == 0 { return nil }
        guard result == 1, let bytes else { throw SyncSnapshotError.storageFailure }
        return Data(bytes: bytes, count: length)
    }
    public func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        guard desired.count <= maximumBytes, (expected?.count ?? 0) <= maximumBytes else { throw SyncSnapshotError.tooLarge }
        lock.lock(); defer { lock.unlock() }
        guard let handle else { throw SyncSnapshotError.closed }
        let expectedBytes = expected ?? Data()
        let result = expectedBytes.withUnsafeBytes { old in desired.withUnsafeBytes { next in
            pod_apple_journal_cas(handle, expected == nil ? 0 : 1, old.bindMemory(to: UInt8.self).baseAddress, old.count,
                next.bindMemory(to: UInt8.self).baseAddress, next.count)
        } }
        guard result >= 0 else { throw SyncSnapshotError.storageFailure }; return result == 1
    }
}
