import Foundation
import XCTest
@testable import PodJSCompanion

private final class FileQueueStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }; guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncFileRequestsTests: XCTestCase {
    func testExactRequestIdentityPendingPeerAndDurableReplyConsumption() throws {
        let store = FileQueueStore(), queue = try SyncFileRequests(appID: "app", deviceID: "watch", store: store)
        let request = try SyncFileRequest.operation(.status, transferID: "file")
        store.fail = true; XCTAssertThrowsError(try queue.enqueue(peer: "phone", messageID: "one", request: request)); XCTAssertNil(store.bytes); store.fail = false
        let first = try queue.enqueue(peer: "phone", messageID: "one", request: request)
        XCTAssertEqual(try queue.enqueue(peer: "phone", messageID: "one", request: request).request.originalJSON,first.request.originalJSON)
        XCTAssertThrowsError(try queue.enqueue(peer: "phone", messageID: "two", request: request))
        XCTAssertThrowsError(try queue.enqueue(peer: "other", messageID: "one", request: request))
        let reply = try SyncFileReply.make(request: request, phase: .offered)
        store.fail = true; XCTAssertThrowsError(try queue.receiveAuthenticated(peer: "phone", messageID: "one", replyBytes: reply.originalJSON)); store.fail = false
        XCTAssertNotNil(try queue.next(peer: "phone"))
        XCTAssertEqual(try queue.receiveAuthenticated(peer: "phone", messageID: "one", replyBytes: reply.originalJSON),.received)
        XCTAssertEqual(try queue.receiveAuthenticated(peer: "phone", messageID: "one", replyBytes: reply.originalJSON),.duplicate)
        XCTAssertNil(try queue.next(peer: "phone"))
        let observation = try XCTUnwrap(queue.completed(peer: "phone").first)
        XCTAssertEqual(try queue.records(transferID: "file").count,1)
        XCTAssertTrue(try queue.consumeCompleted(observation)); XCTAssertFalse(try queue.consumeCompleted(observation))
        XCTAssertThrowsError(try queue.receiveAuthenticated(peer: "phone", messageID: "one", replyBytes: reply.originalJSON))
    }
    func testChangedCompletedReplyCannotReplaceObservation() throws {
        let store = FileQueueStore(), queue = try SyncFileRequests(appID: "app", deviceID: "watch", store: store)
        let request = try SyncFileRequest.operation(.status, transferID: "file")
        _ = try queue.enqueue(peer: "phone", messageID: "one", request: request)
        let original = try SyncFileReply.make(request: request, phase: .offered), changed = try SyncFileReply.make(request: request, phase: .accepted)
        _ = try queue.receiveAuthenticated(peer: "phone", messageID: "one", replyBytes: original.originalJSON)
        XCTAssertThrowsError(try queue.receiveAuthenticated(peer: "phone", messageID: "one", replyBytes: changed.originalJSON))
        XCTAssertEqual(try queue.completed(peer: "phone").first?.reply?.phase,.offered)
    }
    func testClientFileQueueActualReopenKeepsExactPendingAndReceipt() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-file-queue-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: parent) }
        let client = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: parent)
        let request = try SyncFileRequest.decode(Data("{ \"version\":1,\"method\":\"finish\",\"transfer_id\":\"one\" }".utf8))
        _ = try client.fileRequests.enqueue(peer: "phone", messageID: "request-one", request: request); client.close()
        let next = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: parent)
        XCTAssertEqual(try next.fileRequests.next(peer: "phone")?.request.originalJSON,request.originalJSON)
        let reply = try SyncFileReply.make(request: request, phase: .complete)
        _ = try next.fileRequests.receiveAuthenticated(peer: "phone", messageID: "request-one", replyBytes: reply.originalJSON); next.close()
        let restored = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: parent); defer { restored.close() }
        XCTAssertNil(try restored.fileRequests.next(peer: "phone"))
        XCTAssertEqual(try restored.fileRequests.completed(peer: "phone").first?.reply?.originalJSON,reply.originalJSON)
    }
}
