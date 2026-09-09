import Foundation
import XCTest
@testable import PodJSCompanion

private final class OutboxStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false; var conflict = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }; if conflict || bytes != expected { return false }; bytes = desired; return true
    }
}
final class SyncMessageOutboxTests: XCTestCase {
    func testQueuePayloadQuotaRejectsWithoutEvictingLiveMessages() throws {
        let store = OutboxStore(), box = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: store)
        let payload = Data(repeating: 42, count: 262144)
        for index in 0..<31 { try box.enqueue(peer: "phone", messageID: "m\(index)", payload: payload, ttlMilliseconds: 1000, highPriority: false, nowMilliseconds: 0) }
        let before = store.bytes
        XCTAssertThrowsError(try box.enqueue(peer: "phone", messageID: "overflow", payload: payload, ttlMilliseconds: 1000, highPriority: true, nowMilliseconds: 0)) {
            XCTAssertEqual($0 as? SyncMessageOutboxError, .quota)
        }
        XCTAssertEqual(store.bytes, before); XCTAssertEqual(try box.pending(peer: "phone", nowMilliseconds: 0, limit: 1000).count, 31)
    }
    func testFirstExpiryAndAcknowledgedRetryIdentitySurviveReopen() throws {
        let store = OutboxStore(), box = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: store), payload = Data([0,255])
        XCTAssertEqual(try box.enqueue(peer: "phone", messageID: "one", payload: payload, ttlMilliseconds: 1000, highPriority: true, nowMilliseconds: 100),1100)
        XCTAssertEqual(try box.enqueue(peer: "phone", messageID: "one", payload: payload, ttlMilliseconds: 1000, highPriority: true, nowMilliseconds: 200),1100)
        let pending = try XCTUnwrap(box.pending(peer: "phone", nowMilliseconds: 200).first)
        XCTAssertThrowsError(try box.acknowledgeAuthenticated(peer: "phone", messageID: "one", digest: Data(repeating: 0, count: 32)))
        store.fail = true; XCTAssertThrowsError(try box.acknowledgeAuthenticated(peer: "phone", messageID: "one", digest: pending.digest)); store.fail = false
        XCTAssertEqual(try box.pending(peer: "phone", nowMilliseconds: 200).count,1)
        XCTAssertTrue(try box.acknowledgeAuthenticated(peer: "phone", messageID: "one", digest: pending.digest))
        let reopened = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: store)
        XCTAssertEqual(try reopened.enqueue(peer: "phone", messageID: "one", payload: payload, ttlMilliseconds: 1000, highPriority: true, nowMilliseconds: 300),1100)
        XCTAssertTrue(try reopened.pending(peer: "phone", nowMilliseconds: 300).isEmpty)
        XCTAssertThrowsError(try reopened.enqueue(peer: "phone", messageID: "one", payload: Data(), ttlMilliseconds: 1000, highPriority: true, nowMilliseconds: 300))
        XCTAssertThrowsError(try reopened.enqueue(peer: "phone", messageID: "one", payload: payload, ttlMilliseconds: 1000, highPriority: true, nowMilliseconds: 1100))
    }
    func testPriorityExpiryConflictsAndNativeDigestVector() throws {
        XCTAssertEqual(try messageDigest(Data()).map { String(format: "%02x", $0) }.joined(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        let store = OutboxStore(), box = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: store)
        for (id,high) in [("low",false),("high1",true),("high2",true)] {
            try box.enqueue(peer: "phone", messageID: id, payload: Data(), ttlMilliseconds: 100, highPriority: high, nowMilliseconds: 0)
        }
        XCTAssertEqual(try box.pending(peer: "phone", nowMilliseconds: 0, limit: 10).map(\.messageID), ["high1","high2","low"])
        store.conflict = true; XCTAssertThrowsError(try box.expire(nowMilliseconds: 100)); store.conflict = false
        XCTAssertEqual(try box.expire(nowMilliseconds: 100),3)
        XCTAssertTrue(try box.pending(peer: "phone", nowMilliseconds: 100).isEmpty)
        XCTAssertThrowsError(try SyncMessageOutbox(appID: "other", deviceID: "watch", store: store).pending(peer: "phone", nowMilliseconds: 0))
    }
    func testRealFileQueueAndIntentReopen() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-outbox-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("messages"), payload = Data(repeating: 255, count: 262144)
        let store = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024)
        let box = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: store)
        try box.enqueue(peer: "phone", messageID: "one", payload: payload, ttlMilliseconds: 1000, highPriority: false, nowMilliseconds: 0); store.close()
        let reopened = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024); defer { reopened.close() }
        let restored = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: reopened)
        XCTAssertEqual(try restored.pending(peer: "phone", nowMilliseconds: 10).first?.envelope.payload, payload)
    }
}
