import Foundation
import XCTest
@testable import PodJSCompanion

private final class PumpStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }
        guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncStatePumpTests: XCTestCase {
    private func pair(nonce: UInt8 = 1) throws -> (SyncSession, SyncSession) {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: nonce, count: 32), responderNonce: Array(repeating: nonce + 1, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: [.state, .ack])
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: [.state, .ack])
        let ap = try a.proof(), bp = try b.proof()
        _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        return (a, b)
    }
    func testActualAuthenticatedExchangeAndLostAckRetransmission() throws {
        let (a, b) = try pair()
        let left = try SyncState(appID: "app", deviceID: "phone", store: PumpStore())
        let right = try SyncState(appID: "app", deviceID: "watch", store: PumpStore())
        let sender = try SyncStatePump(session: a, state: left), receiver = try SyncStatePump(session: b, state: right)
        _ = try left.set("key", valueJSON: Data("true".utf8))
        let frame = try XCTUnwrap(sender.sendNext()), reply = try receiver.receive(frameJSON: frame)
        XCTAssertEqual(reply.status, .applied); XCTAssertEqual(try right.get("key"), Data("true".utf8))
        XCTAssertEqual(try sender.sendNext(), frame)
        let retry = try receiver.receive(frameJSON: frame)
        XCTAssertEqual(retry.status, .duplicate); XCTAssertEqual(retry.reply, reply.reply)
        XCTAssertEqual(try sender.receive(frameJSON: XCTUnwrap(retry.reply)).status, .ack)
        XCTAssertNil(try sender.sendNext())
        XCTAssertEqual(try sender.receive(frameJSON: XCTUnwrap(retry.reply)).status, .staleAck)
    }
    func testStorageFailureClosesSessionAndReconnectRetriesDurableBatch() throws {
        let (a, b) = try pair(), receiveStore = PumpStore()
        let left = try SyncState(appID: "app", deviceID: "phone", store: PumpStore())
        let right = try SyncState(appID: "app", deviceID: "watch", store: receiveStore)
        let sender = try SyncStatePump(session: a, state: left), receiver = try SyncStatePump(session: b, state: right)
        _ = try left.set("key", valueJSON: Data("null".utf8)); let frame = try XCTUnwrap(sender.sendNext())
        receiveStore.fail = true
        XCTAssertThrowsError(try receiver.receive(frameJSON: frame)); XCTAssertNil(receiveStore.bytes)
        XCTAssertThrowsError(try b.commit(sequence: 1)); XCTAssertThrowsError(try receiver.sendNext())
        sender.close(); receiveStore.fail = false
        let (newA, newB) = try pair(nonce: 3)
        let newSender = try SyncStatePump(session: newA, state: left), newReceiver = try SyncStatePump(session: newB, state: right)
        let next = try XCTUnwrap(newSender.sendNext()); XCTAssertNotEqual(next, frame)
        let result = try newReceiver.receive(frameJSON: next)
        XCTAssertEqual(try newSender.receive(frameJSON: XCTUnwrap(result.reply)).status, .ack)
        XCTAssertEqual(try right.get("key"), Data("null".utf8)); XCTAssertNil(try newSender.sendNext())
    }
    func testIdentityMismatchAndAckBinaryBoundaries() throws {
        let (a, b) = try pair(); defer { a.close(); b.close() }
        let wrong = try SyncState(appID: "other", deviceID: "phone", store: PumpStore())
        XCTAssertThrowsError(try SyncStatePump(session: a, state: wrong))
        let digest = String(repeating: "01ab", count: 16)
        let bytes = try StateAck.encode(cursor: 0x0102030405, digest: digest)
        XCTAssertEqual(Array(bytes.prefix(9)), [3,0,0,0,1,2,3,4,5])
        XCTAssertEqual(try StateAck.decode(bytes).cursor, 0x0102030405)
        XCTAssertEqual(try StateAck.decode(bytes).digest, digest)
        XCTAssertThrowsError(try StateAck.decode([3])); XCTAssertThrowsError(try StateAck.decode([3] + Array(repeating: 255, count: 40)))
        XCTAssertThrowsError(try StateAck.encode(cursor: 1, digest: digest.uppercased()))
    }
    func testAuthenticatedMalformedPayloadCannotCommitOrReturnAck() throws {
        for channel: SyncChannel in [.state, .ack] {
            let (a, b) = try pair(), store = PumpStore()
            defer { a.close(); b.close() }
            let state = try SyncState(appID: "app", deviceID: "watch", store: store)
            let pump = try SyncStatePump(session: b, state: state)
            let frame = try a.send(channel: channel, messageID: "bad", payload: [0xff])
            XCTAssertThrowsError(try pump.receive(frameJSON: frame)); XCTAssertNil(store.bytes)
            XCTAssertThrowsError(try b.commit(sequence: 1)); XCTAssertThrowsError(try pump.sendNext())
        }
    }
}
