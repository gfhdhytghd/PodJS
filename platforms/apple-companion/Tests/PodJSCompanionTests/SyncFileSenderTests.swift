import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncFileSenderTests: XCTestCase {
    private func parent() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-sender-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        for name in ["phone", "watch"] { try FileManager.default.createDirectory(at: root.appendingPathComponent(name), withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
        return root
    }
    private func pair(_ left: SyncCompanionClient, _ right: SyncCompanionClient, round: UInt8 = 1) throws -> (SyncCompanionPump, SyncCompanionPump) {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: round, count: 32), responderNonce: Array(repeating: round + 10, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: [.state,.message,.ack,.file])
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: [.state,.message,.ack,.file])
        let ap = try a.proof(), bp = try b.proof(); _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        return (try left.attach(session: a),try right.attach(session: b))
    }
    func testRegisteredSenderResumesLostReplyAcrossClientReopenToVerifiedCompletion() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let left = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root.appendingPathComponent("phone"))
        let right = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: root.appendingPathComponent("watch")); defer { right.close() }
        let bytes = Data((0..<65539).map { UInt8($0 % 251) }), source = root.appendingPathComponent("source")
        try bytes.write(to: source)
        _ = try left.importOutgoing(sourceURL: source, peer: "watch", transferID: "file")
        let (a,b) = try pair(left,right)
        XCTAssertEqual(try left.stepOutgoing(peer: "watch"),.queued)
        let pending = try XCTUnwrap(left.fileRequests.next(peer: "watch"))
        let offer = try XCTUnwrap(a.sendFile())
        _ = try b.receive(frameJSON: offer, nowMilliseconds: 0) // Reply lost.
        XCTAssertEqual(try left.stepOutgoing(peer: "watch"),.awaitingReply)
        try right.incomingFiles.acceptLocal(peer: "phone", transferID: "file")
        left.close(); right.detach()
        let reopened = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root.appendingPathComponent("phone")); defer { reopened.close() }
        XCTAssertEqual(try reopened.fileRequests.next(peer: "watch")?.messageID,pending.messageID)
        XCTAssertEqual(try reopened.fileRequests.next(peer: "watch")?.request.originalJSON,pending.request.originalJSON)
        let (nextA,nextB) = try pair(reopened,right,round: 2)
        let replayReply = try XCTUnwrap(nextB.receive(frameJSON: XCTUnwrap(nextA.sendFile()), nowMilliseconds: 1).reply)
        _ = try nextA.receive(frameJSON: replayReply, nowMilliseconds: 1)
        // Durable first offer reply remains offered despite later local consent.
        XCTAssertEqual(try reopened.stepOutgoing(peer: "watch"),.waitingConsent)
        var finished = false
        for _ in 0..<20 {
            let frame = try XCTUnwrap(nextA.sendFile())
            let reply = try XCTUnwrap(nextB.receive(frameJSON: frame, nowMilliseconds: 2).reply)
            _ = try nextA.receive(frameJSON: reply, nowMilliseconds: 2)
            let state = try reopened.stepOutgoing(peer: "watch")
            if state == .complete { finished = true; break }
        }
        XCTAssertTrue(finished); XCTAssertEqual(try reopened.stepOutgoing(peer: "watch"),.idle)
        XCTAssertTrue(try reopened.fileRequests.completed(peer: "watch").isEmpty)
        XCTAssertNil(try reopened.fileRequests.next(peer: "watch"))
        let record = try XCTUnwrap(reopened.outgoingTransfers.list().first)
        XCTAssertEqual(record.phase,.complete); XCTAssertEqual(record.acknowledgedChunks,[0,1])
        let artifact = try right.incomingFiles.finishAuthenticated(peer: "phone", transferID: "file")
        XCTAssertEqual(try Data(contentsOf: artifact),bytes)
    }
    func testCancelBeforeFirstOfferEstablishesRemoteIdentityAndRetainsSource() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let left = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root.appendingPathComponent("phone")); defer { left.close() }
        let right = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: root.appendingPathComponent("watch")); defer { right.close() }
        let source = root.appendingPathComponent("source"); try Data("abc".utf8).write(to: source)
        _ = try left.outgoingFiles.importFile(sourceURL: source, transferID: "file")
        _ = try left.registerOutgoing(peer: "watch", transferID: "file")
        try left.outgoingTransfers.requestCancel(peer: "watch", transferID: "file")
        let (a,b) = try pair(left,right)
        XCTAssertEqual(try left.stepOutgoing(peer: "watch"),.queued)
        XCTAssertEqual(try left.fileRequests.next(peer: "watch")?.request.method,.offer)
        for expected in [SyncFileSenderStatus.queued,.cancelled] {
            let reply = try XCTUnwrap(b.receive(frameJSON: XCTUnwrap(a.sendFile()), nowMilliseconds: 0).reply)
            _ = try a.receive(frameJSON: reply, nowMilliseconds: 0)
            XCTAssertEqual(try left.stepOutgoing(peer: "watch"),expected)
        }
        XCTAssertEqual(try left.outgoingTransfers.list()[0].phase,.cancelled)
        XCTAssertEqual(try right.incomingFiles.listLocal()[0].phase,.cancelled)
        XCTAssertEqual(try left.outgoingFiles.readChunk(transferID: "file", index: 0),Data("abc".utf8))
        XCTAssertEqual(try left.stepOutgoing(peer: "watch"),.idle)
    }
}
