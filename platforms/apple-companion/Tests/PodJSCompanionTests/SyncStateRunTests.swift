import Foundation
import XCTest
@testable import PodJSCompanion

private final class RunStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        guard bytes == expected else { return false }; bytes = desired; return true
    }
}
private final class RunIO: @unchecked Sendable {
    var frames: [Data] = []; var closes = 0; var fail = false; var time: UInt64 = 0
    func write(_ frame: Data) throws { if fail { throw SyncSnapshotError.storageFailure }; frames.append(frame) }
}
final class SyncStateRunTests: XCTestCase {
    private func pair() throws -> (SyncSession, SyncSession) {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: [.state, .ack])
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: [.state, .ack])
        let ap = try a.proof(), bp = try b.proof(); _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        return (a,b)
    }
    private func run(_ session: SyncSession, _ state: SyncState, _ io: RunIO) throws -> SyncStateRun {
        try SyncStateRun(session: session, state: state, durationMilliseconds: 100, write: { try io.write($0) }, closeIO: { io.closes += 1 }, now: { io.time })
    }
    func testBidirectionalRunConvergesOnlyAfterDurableAcksAndNewEditInvalidatesBarrier() throws {
        let (a,b) = try pair(), ai = RunIO(), bi = RunIO()
        let leftStore = RunStore(), left = try SyncState(appID: "app", deviceID: "phone", store: leftStore)
        let right = try SyncState(appID: "app", deviceID: "watch", store: RunStore())
        XCTAssertFalse(try left.acknowledgement(peer: "watch").synchronized); XCTAssertNil(leftStore.bytes)
        _ = try left.set("phone", valueJSON: Data("true".utf8)); _ = try right.set("watch", valueJSON: Data("null".utf8))
        let ar = try run(a,left,ai), br = try run(b,right,bi)
        XCTAssertFalse(try ar.step()); XCTAssertFalse(try br.step())
        // IO queues prevent reentrant callbacks; preserve each direction's order.
        for _ in 0..<20 {
            if !ai.frames.isEmpty { try br.receive(frameJSON: ai.frames.removeFirst()) }
            if !bi.frames.isEmpty { try ar.receive(frameJSON: bi.frames.removeFirst()) }
        }
        XCTAssertTrue(ai.frames.isEmpty); XCTAssertTrue(bi.frames.isEmpty)
        XCTAssertTrue(try ar.step()); XCTAssertTrue(try br.step())
        XCTAssertEqual(try left.get("watch"), Data("null".utf8)); XCTAssertEqual(try right.get("phone"), Data("true".utf8))
        _ = try left.delete("phone"); XCTAssertFalse(try left.acknowledgement(peer: "watch").synchronized)
        XCTAssertFalse(try ar.step()); XCTAssertEqual(ai.frames.count, 1)
        ar.close(); br.close(); XCTAssertEqual(ai.closes,1); XCTAssertEqual(bi.closes,1)
    }
    func testWriteFailureClosesAndRetainsDurablePendingBatch() throws {
        let (a,b) = try pair(); defer { b.close() }
        let io = RunIO(), state = try SyncState(appID: "app", deviceID: "phone", store: RunStore())
        let owner = try run(a,state,io); io.fail = true
        XCTAssertThrowsError(try owner.step()); XCTAssertEqual(io.closes,1)
        XCTAssertNotNil(try state.prepare(peer: "watch")); XCTAssertFalse(try state.acknowledgement(peer: "watch").synchronized)
        io.fail = false; XCTAssertThrowsError(try owner.step()); XCTAssertTrue(io.frames.isEmpty)
        owner.close(); XCTAssertEqual(io.closes,1)
    }
    func testMonotonicDeadlineStopsBeforeAnyLateSend() throws {
        let (a,b) = try pair(); defer { b.close() }
        let io = RunIO(), state = try SyncState(appID: "app", deviceID: "phone", store: RunStore())
        let owner = try run(a,state,io); io.time = 100_000_000
        XCTAssertThrowsError(try owner.step()) { XCTAssertEqual($0 as? SyncStateRunError, .deadline) }
        XCTAssertTrue(io.frames.isEmpty); XCTAssertEqual(io.closes,1)
        XCTAssertThrowsError(try owner.receive(frameJSON: Data()))
    }
}
