import Foundation
import XCTest
@testable import PodJSCompanion

private final class CompanionStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }
        guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncCompanionPumpTests: XCTestCase {
    func testFileReplyCommitFailureClosesEveryMixedRouteAndRetainsRequest() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-mixed-failure-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("files"))
        defer { incoming.close() }
        let (a,b) = try pair(channels: [.state,.message,.ack,.file]); defer { b.close() }
        let store = CompanionStore()
        let requests = try SyncFileRequests(appID: "app", deviceID: "phone", store: store)
        let state = try SyncState(appID: "app", deviceID: "phone", store: CompanionStore())
        let outbox = try SyncMessageOutbox(appID: "app", deviceID: "phone", store: CompanionStore())
        let inbox = try SyncMessageInbox(appID: "app", deviceID: "phone", store: CompanionStore())
        // Reject incomplete services and wrong grants before a child takes
        // session ownership: the same authenticated session remains usable.
        XCTAssertThrowsError(try SyncCompanionPump(session: a, state: state, outbox: outbox, inbox: inbox, fileRequests: requests))
        XCTAssertThrowsError(try SyncCompanionPump(session: a, state: state, outbox: outbox, inbox: inbox))
        XCTAssertThrowsError(try SyncStatePump(session: a, state: state))
        XCTAssertThrowsError(try SyncMessagePump(session: a, outbox: outbox, inbox: inbox))
        let pump = try SyncCompanionPump(session: a, state: state, outbox: outbox, inbox: inbox, fileRequests: requests, incomingFiles: incoming)
        let request = try SyncFileRequest.operation(.status, transferID: "one")
        _ = try requests.enqueue(peer: "watch", messageID: "request", request: request)
        let original = try XCTUnwrap(pump.sendFile())
        XCTAssertEqual(try pump.sendFile(),original)
        let reply = try SyncFileReply.make(request: request, phase: .accepted)
        let frame = try b.send(channel: .file, messageID: "request", payload: Array(reply.originalJSON))
        store.fail = true
        XCTAssertThrowsError(try pump.receive(frameJSON: frame, nowMilliseconds: 0))
        store.fail = false
        XCTAssertEqual(try requests.next(peer: "watch")?.request.originalJSON,request.originalJSON)
        XCTAssertTrue(try requests.completed(peer: "watch").isEmpty)
        XCTAssertThrowsError(try pump.sendFile()); XCTAssertThrowsError(try pump.sendState())
        XCTAssertThrowsError(try pump.sendMessage(nowMilliseconds: 0))
        XCTAssertThrowsError(try pump.receive(frameJSON: frame, nowMilliseconds: 0))
        XCTAssertThrowsError(try a.proof())
        // Transport failure must not close client-owned storage or erase work.
        _ = try state.set("retained", valueJSON: Data("true".utf8))
        XCTAssertEqual(try state.get("retained"),Data("true".utf8))
    }
    private func pair(channels: Set<SyncChannel> = [.state,.message,.ack]) throws -> (SyncSession, SyncSession) {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: channels)
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: channels)
        let ap = try a.proof(), bp = try b.proof(); _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap); return (a,b)
    }
    func testMixedChannelsShareSequenceAndRouteApplicationAckKinds() throws {
        let (a,b) = try pair()
        let stateA = try SyncState(appID: "app", deviceID: "phone", store: CompanionStore()), stateB = try SyncState(appID: "app", deviceID: "watch", store: CompanionStore())
        let outA = try SyncMessageOutbox(appID: "app", deviceID: "phone", store: CompanionStore()), outB = try SyncMessageOutbox(appID: "app", deviceID: "watch", store: CompanionStore())
        let inboxA = try SyncMessageInbox(appID: "app", deviceID: "phone", store: CompanionStore()), inboxB = try SyncMessageInbox(appID: "app", deviceID: "watch", store: CompanionStore())
        let left = try SyncCompanionPump(session: a, state: stateA, outbox: outA, inbox: inboxA)
        let right = try SyncCompanionPump(session: b, state: stateB, outbox: outB, inbox: inboxB)
        _ = try stateA.set("fromPhone", valueJSON: Data("true".utf8)); _ = try stateB.set("fromWatch", valueJSON: Data("null".utf8))
        try outA.enqueue(peer: "watch", messageID: "phone-message", payload: Data([1]), ttlMilliseconds: 1000, highPriority: false, nowMilliseconds: 0)
        try outB.enqueue(peer: "phone", messageID: "watch-message", payload: Data([2]), ttlMilliseconds: 1000, highPriority: false, nowMilliseconds: 0)
        var toRight = [try XCTUnwrap(left.sendState()), try XCTUnwrap(left.sendMessage(nowMilliseconds: 0))]
        var toLeft = [try XCTUnwrap(right.sendState()), try XCTUnwrap(right.sendMessage(nowMilliseconds: 0))]
        var deliveries = 0
        for _ in 0..<12 {
            if !toRight.isEmpty {
                let result = try right.receive(frameJSON: toRight.removeFirst(), nowMilliseconds: 1)
                if let reply = result.reply { toLeft.append(reply) }
                if case .message(let message) = result, let delivery = message.delivery { deliveries += 1; toLeft.append(try right.acknowledgeMessage(delivery, nowMilliseconds: 1)) }
            }
            if !toLeft.isEmpty {
                let result = try left.receive(frameJSON: toLeft.removeFirst(), nowMilliseconds: 1)
                if let reply = result.reply { toRight.append(reply) }
                if case .message(let message) = result, let delivery = message.delivery { deliveries += 1; toRight.append(try left.acknowledgeMessage(delivery, nowMilliseconds: 1)) }
            }
        }
        XCTAssertEqual(deliveries,2); XCTAssertTrue(toLeft.isEmpty); XCTAssertTrue(toRight.isEmpty)
        XCTAssertTrue(try outA.pending(peer: "watch", nowMilliseconds: 1).isEmpty); XCTAssertTrue(try outB.pending(peer: "phone", nowMilliseconds: 1).isEmpty)
        XCTAssertEqual(try stateA.get("fromWatch"),Data("null".utf8)); XCTAssertEqual(try stateB.get("fromPhone"),Data("true".utf8))
        // Received edits still require another state cycle, not a false global completion.
        XCTAssertFalse(try stateA.acknowledgement(peer: "watch").synchronized)
    }
    func testInvalidAuthenticatedAckKindClosesAllRoutes() throws {
        let (a,b) = try pair(); defer { a.close() }
        let pump = try SyncCompanionPump(session: b,
            state: SyncState(appID: "app", deviceID: "watch", store: CompanionStore()),
            outbox: SyncMessageOutbox(appID: "app", deviceID: "watch", store: CompanionStore()),
            inbox: SyncMessageInbox(appID: "app", deviceID: "watch", store: CompanionStore()))
        let frame = try a.send(channel: .ack, messageID: "bad", payload: [4])
        XCTAssertThrowsError(try pump.receive(frameJSON: frame, nowMilliseconds: 0))
        XCTAssertThrowsError(try pump.sendState()); XCTAssertThrowsError(try pump.sendMessage(nowMilliseconds: 0))
    }
    func testClientRoutesAllFourChannelsAndDetachClosesFilePump() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-mixed-files-" + UUID().uuidString)
        let phone = root.appendingPathComponent("phone"), watch = root.appendingPathComponent("watch")
        try FileManager.default.createDirectory(at: phone, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.createDirectory(at: watch, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let leftClient = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: phone)
        let rightClient = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: watch)
        defer { leftClient.close(); rightClient.close() }
        let (a,b) = try pair(channels: [.state,.message,.ack,.file])
        // Public single-channel dispatchers must not gain broad grants.
        XCTAssertThrowsError(try SyncFilePump(session: a, requests: leftClient.fileRequests, incoming: leftClient.incomingFiles))
        let left = try leftClient.attach(session: a), right = try rightClient.attach(session: b)
        _ = try leftClient.state.set("key", valueJSON: Data("true".utf8))
        try leftClient.messageOutbox.enqueue(peer: "watch", messageID: "message", payload: Data([3]), ttlMilliseconds: 100, highPriority: false, nowMilliseconds: 0)
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        _ = try leftClient.fileRequests.enqueue(peer: "watch", messageID: "offer", request: .offer(SyncFileManifest(transferID: "one", size: 3, sha256: hash, chunkHashes: [hash])))
        var forward = [try XCTUnwrap(left.sendState()), try XCTUnwrap(left.sendMessage(nowMilliseconds: 0)), try XCTUnwrap(left.sendFile())]
        var backward: [Data] = []
        var messages = 0, files = 0
        for _ in 0..<16 {
            if !forward.isEmpty {
                let result = try right.receive(frameJSON: forward.removeFirst(), nowMilliseconds: 1)
                if let reply = result.reply { backward.append(reply) }
                if case .message(let result) = result, let delivery = result.delivery {
                    messages += 1; backward.append(try right.acknowledgeMessage(delivery, nowMilliseconds: 1))
                }
                if case .file = result { files += 1 }
            }
            if !backward.isEmpty {
                let result = try left.receive(frameJSON: backward.removeFirst(), nowMilliseconds: 1)
                if let reply = result.reply { forward.append(reply) }
            }
        }
        XCTAssertTrue(forward.isEmpty); XCTAssertTrue(backward.isEmpty)
        XCTAssertEqual(messages,1); XCTAssertEqual(files,1)
        XCTAssertEqual(try rightClient.state.get("key"),Data("true".utf8))
        XCTAssertTrue(try leftClient.messageOutbox.pending(peer: "watch", nowMilliseconds: 1).isEmpty)
        XCTAssertEqual(try leftClient.fileRequests.completed(peer: "watch").first?.reply?.phase,.offered)
        XCTAssertNil(try left.sendFile())
        try rightClient.incomingFiles.acceptLocal(peer: "phone", transferID: "one")
        let operations: [(String, SyncFileRequest, SyncFileWirePhase)] = [
            ("chunk", try .chunk(transferID: "one", index: 0, bytes: Data("abc".utf8)), .accepted),
            ("finish", try .operation(.finish, transferID: "one"), .complete)
        ]
        for (id, request, phase) in operations {
            _ = try leftClient.fileRequests.enqueue(peer: "watch", messageID: id, request: request)
            let frame = try XCTUnwrap(left.sendFile())
            let reply = try XCTUnwrap(right.receive(frameJSON: frame, nowMilliseconds: 2).reply)
            _ = try left.receive(frameJSON: reply, nowMilliseconds: 2)
            XCTAssertEqual(try leftClient.fileRequests.completed(peer: "watch").last?.reply?.phase,phase)
        }
        let complete = try rightClient.incomingFiles.finishAuthenticated(peer: "phone", transferID: "one")
        XCTAssertEqual(try Data(contentsOf: complete),Data("abc".utf8))
        leftClient.detach(); XCTAssertThrowsError(try left.sendFile()); XCTAssertThrowsError(try a.proof())
    }
}
