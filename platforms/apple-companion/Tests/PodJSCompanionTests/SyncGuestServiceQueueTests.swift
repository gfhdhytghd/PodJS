import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncGuestServiceQueueTests: XCTestCase {
    private func request(_ id: Int, method: String = "sync.files.status") -> Data {
        Data("{\"t\":\"service.request\",\"version\":1,\"id\":\(id),\"method\":\"\(method)\",\"args\":{}}".utf8)
    }
    private func cancel(_ id: Int) -> Data { Data("{\"t\":\"service.cancel\",\"id\":\(id)}".utf8) }
    func testReplyBackpressureErrorsAndCancellationReuse() throws {
        let worker = DispatchQueue(label: "test.guest.queue")
        let queue = SyncGuestServiceQueue(worker: worker) { method, _, _ in
            if method == "bad" { throw SyncIncomingFileServiceError.unsupported }
            return Data("{\"value\":1}".utf8)
        }
        defer { queue.close() }
        XCTAssertTrue(try queue.submit(request(1)))
        worker.sync {}
        let first = try XCTUnwrap(queue.nextReply())
        XCTAssertEqual(queue.nextReply(), first)
        XCTAssertTrue(try queue.submit(request(1, method: "bad")))
        worker.sync {}; XCTAssertEqual(queue.nextReply(), first)
        XCTAssertTrue(try queue.submit(cancel(1))); XCTAssertNil(queue.nextReply())
        XCTAssertFalse(queue.acknowledgeReply(first))
        XCTAssertTrue(try queue.submit(request(1, method: "bad")))
        worker.sync {}
        let replacement = try XCTUnwrap(queue.nextReply())
        XCTAssertNotEqual(replacement, first)
        let parsed = try JSONSerialization.jsonObject(with: replacement.json) as? [String: Any]
        XCTAssertEqual(parsed?["code"] as? String, "unsupported")
        XCTAssertTrue(queue.acknowledgeReply(replacement)); XCTAssertNil(queue.nextReply())
        XCTAssertTrue(try queue.submit(Data(#"{"t":"service.request","id":2,"version":1,"method":4,"args":{}}"#.utf8)))
        XCTAssertTrue(String(decoding: try XCTUnwrap(queue.nextReply()).json, as: UTF8.self).contains("invalid_argument"))
        queue.close(); XCTAssertNil(queue.nextReply())
    }
    func testCancelledQueuedJobsRetainBoundUntilDrained() throws {
        let worker = DispatchQueue(label: "test.guest.bounded"), gate = DispatchSemaphore(value: 0)
        worker.async { gate.wait() }
        var released = false
        defer { if !released { gate.signal() }; worker.sync {} }
        let queue = SyncGuestServiceQueue(worker: worker) { _, _, _ in
            XCTFail("Cancelled queued work executed"); return Data("null".utf8)
        }
        defer { queue.close() }
        for id in 1...64 { XCTAssertTrue(try queue.submit(request(id))) }
        XCTAssertFalse(try queue.submit(request(65)))
        for id in 1...64 { XCTAssertTrue(try queue.submit(cancel(id))) }
        XCTAssertFalse(try queue.submit(request(65)))
        gate.signal(); released = true; worker.sync {}
        XCTAssertNil(queue.nextReply())
        // Invalid version creates a bounded reply without invoking the handler.
        XCTAssertTrue(try queue.submit(Data(#"{"t":"service.request","id":65,"version":2}"#.utf8)))
        XCTAssertNotNil(queue.nextReply())
    }
}
