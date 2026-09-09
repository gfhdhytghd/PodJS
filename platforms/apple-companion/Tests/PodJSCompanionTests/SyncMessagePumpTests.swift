import Foundation
import XCTest
@testable import PodJSCompanion

private final class MessagePumpStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }; guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncMessagePumpTests: XCTestCase {
    private func sessions() throws -> (SyncSession, SyncSession) {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: [.message, .ack])
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: [.message, .ack])
        let ap = try a.proof(), bp = try b.proof(); _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap); return (a,b)
    }
    func testDurablePendingDoesNotAckAndAppliedRetryReusesExactAck() throws {
        let (a,b) = try sessions()
        let out = try SyncMessageOutbox(appID: "app", deviceID: "phone", store: MessagePumpStore())
        let incoming = try SyncMessageInbox(appID: "app", deviceID: "watch", store: MessagePumpStore())
        let sender = try SyncMessagePump(session: a, outbox: out, inbox: SyncMessageInbox(appID: "app", deviceID: "phone", store: MessagePumpStore()))
        let receiver = try SyncMessagePump(session: b, outbox: SyncMessageOutbox(appID: "app", deviceID: "watch", store: MessagePumpStore()), inbox: incoming)
        try out.enqueue(peer: "watch", messageID: "one", payload: Data([1,2,255]), ttlMilliseconds: 1000, highPriority: false, nowMilliseconds: 0)
        let frame = try XCTUnwrap(sender.sendNext(nowMilliseconds: 0)), result = try receiver.receive(frameJSON: frame, nowMilliseconds: 0)
        XCTAssertEqual(result.status,.pending); XCTAssertNil(result.reply)
        XCTAssertEqual(try out.pending(peer: "watch", nowMilliseconds: 0).count,1)
        XCTAssertEqual(try sender.sendNext(nowMilliseconds: 1),frame)
        XCTAssertEqual(try receiver.receive(frameJSON: frame, nowMilliseconds: 1).status,.pending)
        let ack = try receiver.acknowledge(XCTUnwrap(result.delivery), nowMilliseconds: 1)
        let replay = try receiver.receive(frameJSON: frame, nowMilliseconds: 2)
        XCTAssertEqual(replay.status,.applied); XCTAssertNil(replay.delivery); XCTAssertEqual(replay.reply,ack)
        XCTAssertEqual(try sender.receive(frameJSON: ack, nowMilliseconds: 2).status,.ack)
        XCTAssertNil(try sender.sendNext(nowMilliseconds: 2)); XCTAssertTrue(try incoming.pending(nowMilliseconds: 2).isEmpty)
    }
    func testExpiredReceiptAllocatesNoBusinessEffect() throws {
        let (a,b) = try sessions(), store = MessagePumpStore()
        let out = try SyncMessageOutbox(appID: "app", deviceID: "phone", store: MessagePumpStore())
        let sender = try SyncMessagePump(session: a, outbox: out, inbox: SyncMessageInbox(appID: "app", deviceID: "phone", store: MessagePumpStore()))
        let receiver = try SyncMessagePump(session: b, outbox: SyncMessageOutbox(appID: "app", deviceID: "watch", store: MessagePumpStore()), inbox: SyncMessageInbox(appID: "app", deviceID: "watch", store: store))
        try out.enqueue(peer: "watch", messageID: "old", payload: Data(), ttlMilliseconds: 1, highPriority: false, nowMilliseconds: 0)
        let frame = try XCTUnwrap(sender.sendNext(nowMilliseconds: 0)), result = try receiver.receive(frameJSON: frame, nowMilliseconds: 1)
        XCTAssertEqual(result.status,.expired); XCTAssertNil(store.bytes)
        XCTAssertEqual(try sender.receive(frameJSON: XCTUnwrap(result.reply), nowMilliseconds: 1).status,.ack)
    }
    func testAppliedReceiptFailureClosesWithoutAckAndRetainsPendingEffect() throws {
        let (a,b) = try sessions(), store = MessagePumpStore()
        let inbox = try SyncMessageInbox(appID: "app", deviceID: "watch", store: store)
        let receiver = try SyncMessagePump(session: b, outbox: SyncMessageOutbox(appID: "app", deviceID: "watch", store: MessagePumpStore()), inbox: inbox)
        defer { a.close() }
        let payload = try SyncMessageEnvelope(expiresAt: 1000, highPriority: false, payload: Data([9])).encoded()
        let frame = try a.send(channel: .message, messageID: "one", payload: Array(payload))
        let result = try receiver.receive(frameJSON: frame, nowMilliseconds: 0)
        store.fail = true; XCTAssertThrowsError(try receiver.acknowledge(XCTUnwrap(result.delivery), nowMilliseconds: 1))
        store.fail = false; XCTAssertEqual(try inbox.pending(nowMilliseconds: 1).count,1)
        XCTAssertThrowsError(try receiver.receive(frameJSON: frame, nowMilliseconds: 1)); XCTAssertThrowsError(try b.proof())
    }
}
