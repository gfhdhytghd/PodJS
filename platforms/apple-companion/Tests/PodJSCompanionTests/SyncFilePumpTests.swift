import Foundation
import XCTest
@testable import PodJSCompanion

private final class FilePumpStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }
        guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncFilePumpTests: XCTestCase {
    private func sessions() throws -> (SyncSession, SyncSession) {
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch",
            initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: [.file])
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: [.file])
        let ap = try a.proof(), bp = try b.proof(); _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        return (a,b)
    }
    private func parent() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-file-pump-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return url
    }
    private func offer() throws -> SyncFileRequest {
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        return try .offer(SyncFileManifest(transferID: "one", size: 3, sha256: hash, chunkHashes: [hash]))
    }
    func testAuthenticatedOfferReplayAfterConsentAndNextStatus() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let (a,b) = try sessions()
        let requests = try SyncFileRequests(appID: "app", deviceID: "phone", store: FilePumpStore())
        let local = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("phone"))
        let remote = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root.appendingPathComponent("watch"))
        defer { local.close(); remote.close() }
        let sender = try SyncFilePump(session: a, requests: requests, incoming: local)
        let receiver = try SyncFilePump(session: b, requests: SyncFileRequests(appID: "app", deviceID: "watch", store: FilePumpStore()), incoming: remote)
        _ = try requests.enqueue(peer: "watch", messageID: "offer-id", request: offer())
        let frame = try XCTUnwrap(sender.sendNext()); XCTAssertEqual(try sender.sendNext(),frame)
        let reply = try XCTUnwrap(receiver.receive(frameJSON: frame).reply)
        try remote.acceptLocal(peer: "phone", transferID: "one")
        XCTAssertEqual(try receiver.receive(frameJSON: frame).reply,reply)
        XCTAssertEqual(try sender.receive(frameJSON: reply).status,.reply)
        XCTAssertEqual(try sender.receive(frameJSON: reply).status,.duplicateReply)
        XCTAssertNil(try sender.sendNext())
        XCTAssertEqual(try requests.completed(peer: "watch").first?.reply?.phase,.offered)
        _ = try requests.enqueue(peer: "watch", messageID: "status-id", request: .operation(.status, transferID: "one"))
        let next = try XCTUnwrap(sender.sendNext())
        let statusReply = try XCTUnwrap(receiver.receive(frameJSON: next).reply)
        XCTAssertEqual(try sender.receive(frameJSON: statusReply).status,.reply)
        XCTAssertEqual(try requests.completed(peer: "watch").last?.reply?.phase,.accepted)
        XCTAssertThrowsError(try receiver.receive(frameJSON: frame))
        XCTAssertThrowsError(try b.proof())
    }
    func testReplyStorageFailureClosesAndRetainsPendingRequest() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let (a,b) = try sessions(); defer { b.close() }
        let store = FilePumpStore(), request = try offer()
        let requests = try SyncFileRequests(appID: "app", deviceID: "phone", store: store)
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("phone")); defer { incoming.close() }
        let pump = try SyncFilePump(session: a, requests: requests, incoming: incoming)
        _ = try requests.enqueue(peer: "watch", messageID: "one", request: request)
        _ = try pump.sendNext()
        let reply = try SyncFileReply.make(request: request, phase: .offered)
        let frame = try b.send(channel: .file, messageID: "one", payload: Array(reply.originalJSON))
        store.fail = true; XCTAssertThrowsError(try pump.receive(frameJSON: frame))
        store.fail = false; XCTAssertNotNil(try requests.next(peer: "watch"))
        XCTAssertThrowsError(try pump.sendNext()); XCTAssertThrowsError(try a.proof())
    }
    func testIdentityMismatchRejectsConstruction() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let (a,b) = try sessions(); defer { a.close(); b.close() }
        let incoming = try SyncIncomingFiles(appID: "other", deviceID: "phone", privateRoot: root.appendingPathComponent("phone")); defer { incoming.close() }
        XCTAssertThrowsError(try SyncFilePump(session: a,
            requests: SyncFileRequests(appID: "app", deviceID: "phone", store: FilePumpStore()), incoming: incoming))
    }
}
