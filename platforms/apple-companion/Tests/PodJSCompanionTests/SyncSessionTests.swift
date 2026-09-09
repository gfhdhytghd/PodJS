import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncSessionTests: XCTestCase {
    private func session(_ initiator: Bool, channels: Set<SyncChannel> = [.message, .ack]) throws -> SyncSession {
        try SyncSession(pairingKey: Array(repeating: 7, count: 32),
            binding: SyncBinding(appID: "app", initiator: "phone", responder: "watch",
                initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32)),
            localIsInitiator: initiator, allowedChannels: channels)
    }
    func testNativeHandshakeDeliveryAndDurableCommit() throws {
        let a = try session(true), b = try session(false); defer { a.close(); b.close() }
        XCTAssertThrowsError(try a.send(channel: .message, messageID: "one", payload: [1]))
        let ap = try a.proof(), bp = try b.proof()
        XCTAssertEqual(try a.authenticate(remoteProof: bp), try b.authenticate(remoteProof: ap))
        let frame = try a.send(channel: .message, messageID: "one", payload: [0, 255, 128])
        let pending = try b.verify(frameJSON: frame)
        XCTAssertEqual(pending.delivery, .pending); XCTAssertEqual(pending.acknowledged, 0)
        XCTAssertThrowsError(try b.commit(sequence: 2)); XCTAssertEqual(try b.commit(sequence: 1), 1)
        XCTAssertEqual(try b.verify(frameJSON: frame).delivery, .duplicate)
        XCTAssertThrowsError(try a.send(channel: .file, messageID: "file", payload: []))
    }
    func testInvalidProofAndClosedHandle() throws {
        let value = try session(true)
        XCTAssertThrowsError(try value.authenticate(remoteProof: Array(repeating: 0, count: 32)))
        XCTAssertThrowsError(try value.proof()); value.close(); value.close()
        XCTAssertThrowsError(try value.proof()) { XCTAssertEqual($0 as? SyncSessionError, .closed) }
    }
    func testInvalidConfigurationIsRejectedBeforeCreatingASession() throws {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32))
        XCTAssertThrowsError(try SyncSession(pairingKey: Array(repeating: 0, count: 32), binding: binding,
            localIsInitiator: true, allowedChannels: [.message]))
        XCTAssertThrowsError(try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding,
            localIsInitiator: true, allowedChannels: []))
    }
    func testTamperedFrameCannotAdvanceAcknowledgementOrReuseTheSession() throws {
        let a = try session(true), b = try session(false); defer { a.close(); b.close() }
        let ap = try a.proof(), bp = try b.proof()
        _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        let original = try a.send(channel: .message, messageID: "m", payload: [1])
        var frame = try XCTUnwrap(JSONSerialization.jsonObject(with: original) as? [String: Any])
        frame["payload"] = [2]
        XCTAssertThrowsError(try b.verify(frameJSON: JSONSerialization.data(withJSONObject: frame)))
        XCTAssertThrowsError(try b.commit(sequence: 1))
        XCTAssertThrowsError(try b.verify(frameJSON: original))
    }
    func testUntrustedFrameIsNotDecodedAndReserializedBeforeNativeValidation() throws {
        let a = try session(true), b = try session(false); defer { a.close(); b.close() }
        let ap = try a.proof(), bp = try b.proof()
        _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        var frame = try XCTUnwrap(JSONSerialization.jsonObject(with: a.send(channel: .message, messageID: "m", payload: [])) as? [String: Any])
        frame["unexpected"] = true
        XCTAssertThrowsError(try b.verify(frameJSON: JSONSerialization.data(withJSONObject: frame)))
        XCTAssertThrowsError(try b.verify(frameJSON: Data("{},\"method\":\"proof\"".utf8)))
    }
    func testConcurrentCloseSerializesBorrowedNativeReplies() throws {
        let value = try session(true)
        DispatchQueue.concurrentPerform(iterations: 100) { index in
            if index % 7 == 0 { value.close() } else { _ = try? value.proof() }
        }
        XCTAssertThrowsError(try value.proof())
    }
}
