import Foundation
import XCTest
@testable import PodJSCompanion

private final class StateMemoryStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false; var conflict = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }
        if conflict || bytes != expected { return false }; bytes = desired; return true
    }
}
private final class StateNotifications: @unchecked Sendable {
    var snapshots: [Data] = []; var token: UUID?; var readSucceeded = false
}
final class SyncStateTests: XCTestCase {
    func testSubscriptionsRunAfterCommitOutsideLockAndIgnoreReceiptOnlyWrites() throws {
        let store = StateMemoryStore(), state = try SyncState(appID: "app", deviceID: "watch", store: store)
        let notifications = StateNotifications()
        let token = state.subscribe { snapshot in
            notifications.snapshots.append(snapshot)
            notifications.readSucceeded = (try? state.get("key")) != nil
        }
        store.fail = true; XCTAssertThrowsError(try state.set("key", valueJSON: Data("true".utf8)))
        XCTAssertTrue(notifications.snapshots.isEmpty); store.fail = false
        _ = try state.set("key", valueJSON: Data("true".utf8))
        XCTAssertEqual(notifications.snapshots.count, 1); XCTAssertTrue(notifications.readSucceeded)
        let payload = batch("false", counter: 0)
        _ = try state.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: payload)
        _ = try state.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: payload)
        let outgoing = try XCTUnwrap(state.prepare(peer: "phone"))
        _ = try state.acknowledgeAuthenticated(peer: "phone", messageID: outgoing.messageId, cursor: outgoing.to, digest: outgoing.digest)
        XCTAssertEqual(notifications.snapshots.count, 1)
        state.unsubscribe(token)
        notifications.token = state.subscribe { snapshot in
            notifications.snapshots.append(snapshot)
            if let token = notifications.token { state.unsubscribe(token) }
        }
        _ = try state.delete("key"); _ = try state.set("key", valueJSON: Data("null".utf8))
        XCTAssertEqual(notifications.snapshots.count, 2)
    }
    func testOutgoingReopenMatchingAckAndEditDuringCycle() throws {
        let store = StateMemoryStore(), state = try SyncState(appID: "app", deviceID: "watch", store: store)
        let receiver = try SyncState(appID: "app", deviceID: "phone", store: StateMemoryStore())
        _ = try state.set("key", valueJSON: Data("1".utf8))
        let first = try XCTUnwrap(state.prepare(peer: "phone"))
        _ = try state.set("key", valueJSON: Data("2".utf8))
        let reopened = try SyncState(appID: "app", deviceID: "watch", store: store)
        let retry = try XCTUnwrap(reopened.prepare(peer: "phone"))
        XCTAssertEqual(retry.messageId, first.messageId); XCTAssertEqual(retry.payload, first.payload)
        let receipt = try receiver.receiveAuthenticated(peer: "watch", messageID: first.messageId, payloadJSON: Data(first.payload.utf8))
        XCTAssertFalse(try reopened.acknowledgeAuthenticated(peer: "other", messageID: first.messageId, cursor: receipt.cursor, digest: receipt.digest))
        XCTAssertFalse(try reopened.acknowledgeAuthenticated(peer: "phone", messageID: "wrong", cursor: receipt.cursor, digest: receipt.digest))
        store.fail = true
        XCTAssertThrowsError(try reopened.acknowledgeAuthenticated(peer: "phone", messageID: first.messageId, cursor: receipt.cursor, digest: receipt.digest))
        store.fail = false
        XCTAssertEqual(try reopened.prepare(peer: "phone")?.payload, first.payload)
        XCTAssertTrue(try reopened.acknowledgeAuthenticated(peer: "phone", messageID: first.messageId, cursor: receipt.cursor, digest: receipt.digest))
        let second = try XCTUnwrap(reopened.prepare(peer: "phone")); XCTAssertEqual(second.from, 1)
        let next = try receiver.receiveAuthenticated(peer: "watch", messageID: second.messageId, payloadJSON: Data(second.payload.utf8))
        XCTAssertTrue(try reopened.acknowledgeAuthenticated(peer: "phone", messageID: second.messageId, cursor: next.cursor, digest: next.digest))
        XCTAssertNil(try reopened.prepare(peer: "phone")); XCTAssertEqual(try receiver.get("key"), Data("2.0".utf8))
    }
    func testOutgoingPrepareFailureAndEmptyCycle() throws {
        let store = StateMemoryStore(), state = try SyncState(appID: "app", deviceID: "watch", store: store)
        store.fail = true; XCTAssertThrowsError(try state.prepare(peer: "phone")); XCTAssertNil(store.bytes)
        store.fail = false
        let batch = try XCTUnwrap(state.prepare(peer: "phone"))
        XCTAssertEqual(batch.from, 0); XCTAssertEqual(batch.to, 1)
        XCTAssertTrue(try state.acknowledgeAuthenticated(peer: "phone", messageID: batch.messageId, cursor: batch.to, digest: batch.digest))
        XCTAssertNil(try state.prepare(peer: "phone")); XCTAssertThrowsError(try state.prepare(peer: "watch"))
    }
    private func batch(_ value: String, counter: Int = 1, device: String = "phone", from: Int = 0) -> Data {
        Data("{\"version\":1,\"from\":\(from),\"to\":\(from + 1),\"entries\":[{\"key\":\"key\",\"value\":\(value),\"counter\":\(counter),\"deviceId\":\"\(device)\",\"deleted\":false}]}".utf8)
    }
    func testLocalNullTombstoneAndReopen() throws {
        let store = StateMemoryStore(), state = try SyncState(appID: "app", deviceID: "watch", store: StateMemoryStore())
        XCTAssertNil(try state.get("key"))
        let saved = try SyncState(appID: "app", deviceID: "watch", store: store)
        XCTAssertEqual(try saved.set("key", valueJSON: Data("null".utf8)).counter, 1)
        XCTAssertEqual(try saved.get("key"), Data("null".utf8))
        XCTAssertEqual(try saved.delete("key").counter, 2); XCTAssertNil(try saved.get("key"))
        let reopened = try SyncState(appID: "app", deviceID: "watch", store: store)
        XCTAssertNil(try reopened.get("key")); XCTAssertEqual(try reopened.set("key", valueJSON: Data("true".utf8)).counter, 3)
        XCTAssertThrowsError(try SyncState(appID: "other", deviceID: "watch", store: store).get("key"))
    }
    func testDeterministicMergeReceiptReplayAndCASFailure() throws {
        let store = StateMemoryStore(), state = try SyncState(appID: "app", deviceID: "watch", store: store)
        _ = try state.set("key", valueJSON: Data("\"local\"".utf8))
        let payload = batch("\"remote\"")
        let receipt = try state.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: payload)
        XCTAssertEqual(receipt.cursor, 1); XCTAssertEqual(try state.get("key"), Data("\"local\"".utf8))
        XCTAssertTrue(try state.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: payload).duplicate)
        XCTAssertThrowsError(try state.receiveAuthenticated(peer: "phone", messageID: "other", payloadJSON: payload))
        let previous = store.bytes; store.fail = true
        XCTAssertThrowsError(try state.receiveAuthenticated(peer: "phone", messageID: "two", payloadJSON: batch("42", counter: 2, from: 1)))
        XCTAssertEqual(store.bytes, previous); store.fail = false
        XCTAssertEqual(try state.receiveAuthenticated(peer: "phone", messageID: "two", payloadJSON: batch("42", counter: 2, from: 1)).cursor, 2)
        store.conflict = true; XCTAssertThrowsError(try state.delete("key")) { XCTAssertEqual($0 as? SyncStateError, .conflict) }
    }
    func testEqualRevisionConflictsPreserveStateAndUnicodeObjectKeys() throws {
        let state = try SyncState(appID: "app", deviceID: "watch", store: StateMemoryStore())
        _ = try state.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: batch("1"))
        // JSON 1 and 1.0 are the same ECMAScript number, not a false conflict.
        _ = try state.receiveAuthenticated(peer: "phone", messageID: "two", payloadJSON: batch("1.0", from: 1))
        XCTAssertThrowsError(try state.receiveAuthenticated(peer: "phone", messageID: "three", payloadJSON: batch("2", from: 2)))
        let json = Data("{\"é\":1,\"é\":2}".utf8)
        _ = try state.set("unicode", valueJSON: json)
        let returned = String(decoding: try XCTUnwrap(state.get("unicode")), as: UTF8.self)
        XCTAssertTrue(returned.utf8.contains(0xCC)); XCTAssertTrue(returned.utf8.contains(0xC3))
        XCTAssertThrowsError(try state.set("key", valueJSON: Data("NaN".utf8)))
        XCTAssertThrowsError(try state.set("../bad", valueJSON: Data("0".utf8)))
    }
    func testRealFileStoreRetainsStateAndReceiptAcrossReopen() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-swift-state-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("state"), store = try FileSyncSnapshotStore(privateRoot: parent.appendingPathComponent("state"))
        let state = try SyncState(appID: "app", deviceID: "watch", store: store), payload = batch("true")
        _ = try state.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: payload)
        let pending = try XCTUnwrap(state.prepare(peer: "phone")); store.close()
        let next = try FileSyncSnapshotStore(privateRoot: root); defer { next.close() }
        let reopened = try SyncState(appID: "app", deviceID: "watch", store: next)
        XCTAssertEqual(try reopened.get("key"), Data("true".utf8))
        XCTAssertTrue(try reopened.receiveAuthenticated(peer: "phone", messageID: "one", payloadJSON: payload).duplicate)
        let retry = try XCTUnwrap(reopened.prepare(peer: "phone"))
        XCTAssertEqual(retry.payload, pending.payload); XCTAssertEqual(retry.messageId, pending.messageId)
        XCTAssertTrue(try reopened.acknowledgeAuthenticated(peer: "phone", messageID: retry.messageId, cursor: retry.to, digest: retry.digest))
        XCTAssertNil(try reopened.prepare(peer: "phone"))
    }
}
