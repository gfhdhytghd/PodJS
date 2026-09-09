import Foundation
import XCTest
@testable import PodJSCompanion

private final class InboxStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }; guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncMessageInboxTests: XCTestCase {
    private func wire(_ payload: Data = Data([0,255]), high: Bool = false) throws -> Data {
        try SyncMessageEnvelope(expiresAt: 1000, highPriority: high, payload: payload).encoded()
    }
    func testPendingAppliedDedupAndFailureRecovery() throws {
        let store = InboxStore(), inbox = try SyncMessageInbox(appID: "app", deviceID: "watch", store: store), bytes = try wire()
        store.fail = true
        XCTAssertThrowsError(try inbox.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: bytes, nowMilliseconds: 0)); XCTAssertNil(store.bytes)
        store.fail = false
        let pending = try inbox.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: bytes, nowMilliseconds: 0)
        XCTAssertEqual(pending.status,.pending)
        XCTAssertEqual(try inbox.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: bytes, nowMilliseconds: 1).status,.pending)
        XCTAssertEqual(try inbox.pending(nowMilliseconds: 1).count,1)
        XCTAssertThrowsError(try inbox.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: wire(Data([1])), nowMilliseconds: 1))
        store.fail = true
        XCTAssertThrowsError(try inbox.markApplied(peer: "phone", messageID: "one", digest: pending.digest, nowMilliseconds: 1)); store.fail = false
        XCTAssertEqual(try inbox.pending(nowMilliseconds: 1).count,1)
        XCTAssertEqual(try inbox.markApplied(peer: "phone", messageID: "one", digest: pending.digest, nowMilliseconds: 1).status,.applied)
        let reopened = try SyncMessageInbox(appID: "app", deviceID: "watch", store: store)
        XCTAssertTrue(try reopened.pending(nowMilliseconds: 1).isEmpty)
        XCTAssertEqual(try reopened.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: bytes, nowMilliseconds: 2).status,.applied)
    }
    func testExpiryPriorityPaginationAndTokens() throws {
        let store = InboxStore(), inbox = try SyncMessageInbox(appID: "app", deviceID: "watch", store: store)
        XCTAssertEqual(try inbox.receiveAuthenticated(peer: "phone", messageID: "expired", envelopeBytes: wire(), nowMilliseconds: 1000).status,.expired)
        XCTAssertNil(store.bytes)
        for (id,high) in [("low",false),("high1",true),("high2",true)] {
            _ = try inbox.receiveAuthenticated(peer: "phone", messageID: id, envelopeBytes: wire(high: high), nowMilliseconds: 0)
        }
        XCTAssertEqual(try inbox.pending(nowMilliseconds: 0, limit: 1, offset: 1).map(\.messageID), ["high2"])
        XCTAssertThrowsError(try inbox.markApplied(peer: "phone", messageID: "low", digest: Data(repeating: 0, count: 32), nowMilliseconds: 0))
        let pending = try XCTUnwrap(inbox.pending(nowMilliseconds: 0).first)
        XCTAssertThrowsError(try inbox.markApplied(peer: "phone", messageID: pending.messageID, digest: pending.digest, nowMilliseconds: 1000))
        XCTAssertTrue(try inbox.pending(nowMilliseconds: 1000).isEmpty)
        XCTAssertThrowsError(try SyncMessageInbox(appID: "other", deviceID: "watch", store: store).pending(nowMilliseconds: 0))
    }
    func testPendingEffectAndAppliedReceiptSurviveActualFileReopen() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-inbox-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("inbox"), bytes = try wire()
        let store = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024)
        let inbox = try SyncMessageInbox(appID: "app", deviceID: "watch", store: store)
        _ = try inbox.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: bytes, nowMilliseconds: 0); store.close()
        let next = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024)
        let restored = try SyncMessageInbox(appID: "app", deviceID: "watch", store: next)
        let pending = try XCTUnwrap(restored.pending(nowMilliseconds: 1).first)
        _ = try restored.markApplied(peer: "phone", messageID: pending.messageID, digest: pending.digest, nowMilliseconds: 1); next.close()
        let finalStore = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024); defer { finalStore.close() }
        let finalInbox = try SyncMessageInbox(appID: "app", deviceID: "watch", store: finalStore)
        XCTAssertEqual(try finalInbox.receiveAuthenticated(peer: "phone", messageID: "one", envelopeBytes: bytes, nowMilliseconds: 2).status,.applied)
    }
}
