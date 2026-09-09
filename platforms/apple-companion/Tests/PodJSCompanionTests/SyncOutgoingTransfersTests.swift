import Foundation
import XCTest
@testable import PodJSCompanion

private final class TransferStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        if fail { throw SyncSnapshotError.storageFailure }
        guard bytes == expected else { return false }; bytes = desired; return true
    }
}
final class SyncOutgoingTransfersTests: XCTestCase {
    private func manifest() -> SyncFileManifest {
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        return SyncFileManifest(transferID: "one", size: 3, sha256: hash, chunkHashes: [hash])
    }
    private func observation(_ request: SyncFileRequest, phase: SyncFileWirePhase, missing: [Int]? = nil, peer: String = "watch") throws -> SyncPendingFileRequest {
        let queue = try SyncFileRequests(appID: "app", deviceID: "phone", store: TransferStore())
        _ = try queue.enqueue(peer: peer, messageID: "request", request: request)
        let reply = try SyncFileReply.make(request: request, phase: phase, missing: missing)
        _ = try queue.receiveAuthenticated(peer: peer, messageID: "request", replyBytes: reply.originalJSON)
        return try XCTUnwrap(queue.completed(peer: peer).first)
    }
    func testProgressRequiresDurableObservationAndCancelDoesNotClaimCompletion() throws {
        let store = TransferStore(), transfers = try SyncOutgoingTransfers(appID: "app", deviceID: "phone", store: TransferStore())
        _ = try transfers.register(peer: "watch", manifest: manifest())
        XCTAssertThrowsError(try transfers.register(peer: "other", manifest: manifest()))
        XCTAssertFalse(try transfers.list()[0].progressKnown)
        let missing = try observation(.operation(.missing, transferID: "one"), phase: .accepted, missing: [0])
        try transfers.observeCompleted(missing)
        XCTAssertTrue(try transfers.list()[0].progressKnown); XCTAssertTrue(try transfers.list()[0].acknowledgedChunks.isEmpty)
        let bad = try observation(.chunk(transferID: "one", index: 0, bytes: Data("bad".utf8)), phase: .accepted)
        XCTAssertThrowsError(try transfers.observeCompleted(bad))
        let chunk = try observation(.chunk(transferID: "one", index: 0, bytes: Data("abc".utf8)), phase: .accepted)
        try transfers.observeCompleted(chunk); try transfers.observeCompleted(chunk)
        XCTAssertEqual(try transfers.list()[0].acknowledgedChunks,[0]); XCTAssertEqual(try transfers.list()[0].phase,.queued)
        try transfers.requestCancel(peer: "watch", transferID: "one")
        XCTAssertEqual(try transfers.list()[0].phase,.cancelRequested)
        try transfers.observeCompleted(observation(.operation(.cancel, transferID: "one"), phase: .cancelled))
        XCTAssertEqual(try transfers.list()[0].phase,.cancelled)
        XCTAssertThrowsError(try transfers.observeCompleted(chunk))
        let other = try SyncOutgoingTransfers(appID: "app", deviceID: "phone", store: store)
        _ = try other.register(peer: "watch", manifest: manifest())
        let finish = try observation(.operation(.finish, transferID: "one"), phase: .complete)
        store.fail = true; XCTAssertThrowsError(try other.observeCompleted(finish))
        store.fail = false; XCTAssertEqual(try other.list()[0].phase,.queued)
        try other.observeCompleted(finish); store.fail = true; try other.observeCompleted(finish)
        XCTAssertEqual(try other.list()[0].phase,.complete)
    }
    func testClientRegistersOnlyCompletedSourceAndReopensPeerBinding() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-transfer-client-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let client = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root)
        try client.outgoingFiles.prepare(manifest())
        XCTAssertThrowsError(try client.registerOutgoing(peer: "watch", transferID: "one"))
        try client.outgoingFiles.writeChunk(transferID: "one", index: 0, bytes: Data("abc".utf8)); try client.outgoingFiles.finish(transferID: "one")
        XCTAssertEqual(try client.registerOutgoing(peer: "watch", transferID: "one").phase,.queued)
        XCTAssertThrowsError(try client.registerOutgoing(peer: "other", transferID: "one"))
        client.close(); XCTAssertThrowsError(try client.outgoingTransfers.list())
        let reopened = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root); defer { reopened.close() }
        XCTAssertEqual(try reopened.outgoingTransfers.list()[0].peer,"watch")
        XCTAssertEqual(try reopened.registerOutgoing(peer: "watch", transferID: "one").phase,.queued)
        XCTAssertEqual(try reopened.outgoingFiles.readChunk(transferID: "one", index: 0),Data("abc".utf8))
    }
}
