import Foundation
import XCTest
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif
@testable import PodJSCompanion

final class SyncSnapshotStoreTests: XCTestCase {
    private func temporary() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-swift-journal-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        return root
    }
    func testAbsentEmptyConflictAndDurableReopen() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("state"), data = Data("状态".utf8)
        let store = try FileSyncSnapshotStore(privateRoot: root)
        XCTAssertNil(try store.read()); XCTAssertTrue(try store.compareExchange(expected: nil, desired: Data()))
        XCTAssertEqual(try store.read(), Data()); XCTAssertFalse(try store.compareExchange(expected: nil, desired: data))
        XCTAssertTrue(try store.compareExchange(expected: Data(), desired: data)); store.close()
        let reopened = try FileSyncSnapshotStore(privateRoot: root); defer { reopened.close() }
        XCTAssertEqual(try reopened.read(), data)
        XCTAssertThrowsError(try reopened.compareExchange(expected: data, desired: Data(repeating: 0, count: 4 * 1024 * 1024 + 1)))
        XCTAssertEqual(try reopened.read(), data)
    }
    func testLifetimeLeaseAndClosedOperations() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("state"), first = try FileSyncSnapshotStore(privateRoot: parent.appendingPathComponent("state"))
        XCTAssertThrowsError(try FileSyncSnapshotStore(privateRoot: root)); first.close(); first.close()
        let next = try FileSyncSnapshotStore(privateRoot: root); next.close()
        XCTAssertThrowsError(try first.read()) { XCTAssertEqual($0 as? SyncSnapshotError, .closed) }
    }
    func testStagingRecoveryKeepsCommittedSnapshotAndRefusesSymlinks() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("state"), data = Data("committed".utf8)
        let first = try FileSyncSnapshotStore(privateRoot: root)
        XCTAssertTrue(try first.compareExchange(expected: nil, desired: data)); first.close()
        let staging = root.appendingPathComponent("snapshot.tmp")
        try Data("partial".utf8).write(to: staging)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: staging.path)
        let next = try FileSyncSnapshotStore(privateRoot: root)
        XCTAssertEqual(try next.read(), data); XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path)); next.close()
        let snapshot = root.appendingPathComponent("snapshot")
        try FileManager.default.createSymbolicLink(at: staging, withDestinationURL: snapshot)
        XCTAssertThrowsError(try FileSyncSnapshotStore(privateRoot: root)); XCTAssertEqual(try Data(contentsOf: snapshot), data)
    }
    func testUnsafeSnapshotCannotBeReadOrOverwritten() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("state"), store = try FileSyncSnapshotStore(privateRoot: parent.appendingPathComponent("state"))
        defer { store.close() }
        let other = parent.appendingPathComponent("other"), bytes = Data("keep".utf8)
        try bytes.write(to: other)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("snapshot"), withDestinationURL: other)
        XCTAssertThrowsError(try store.read()); XCTAssertThrowsError(try store.compareExchange(expected: nil, desired: Data()))
        XCTAssertEqual(try Data(contentsOf: other), bytes)
    }
    func testConcurrentCASDoesNotLoseUpdates() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let store = try FileSyncSnapshotStore(privateRoot: parent.appendingPathComponent("state")); defer { store.close() }
        XCTAssertTrue(try store.compareExchange(expected: nil, desired: Data("0".utf8)))
        DispatchQueue.concurrentPerform(iterations: 4) { _ in
            do {
                for _ in 0..<10 {
                    while true {
                        let before = try XCTUnwrap(store.read())
                        let count = try XCTUnwrap(Int(String(decoding: before, as: UTF8.self)))
                        if try store.compareExchange(expected: before, desired: Data(String(count + 1).utf8)) { break }
                    }
                }
            } catch { XCTFail("CAS failed: \(error)") }
        }
        XCTAssertEqual(try store.read(), Data("40".utf8))
    }
    func testUnexpectedFIFOIsRejectedWithoutWaitingForAWriter() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("state"), store = try FileSyncSnapshotStore(privateRoot: parent.appendingPathComponent("state"))
        defer { store.close() }
        XCTAssertEqual(root.appendingPathComponent("snapshot").path.withCString { mkfifo($0, 0o600) }, 0)
        XCTAssertThrowsError(try store.read())
        XCTAssertThrowsError(try store.compareExchange(expected: nil, desired: Data()))
    }
}
