import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncIncomingFilesTests: XCTestCase {
    private let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private func parent() throws -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-consent-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: value, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return value
    }
    private func manifest(_ id: String = "one") -> SyncFileManifest { SyncFileManifest(transferID: id, size: 3, sha256: hash, chunkHashes: [hash]) }
    func testDurableRequestReplayKeepsOriginalReplyAfterConsentAndRetriesPendingEffect() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }; let root = parent.appendingPathComponent("incoming")
        let files = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        let offer = try SyncFileRequest.offer(manifest())
        let first = try files.receiveAuthenticatedRequest(peer: "phone", requestID: "offer-id", request: offer)
        XCTAssertEqual(first.phase,.offered); try files.acceptLocal(peer: "phone", transferID: "one"); files.close()
        let reopened = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root); defer { reopened.close() }
        let replay = try reopened.receiveAuthenticatedRequest(peer: "phone", requestID: "offer-id", request: offer)
        XCTAssertEqual(replay.originalJSON,first.originalJSON); XCTAssertEqual(replay.phase,.offered)
        let status = try SyncFileRequest.operation(.status, transferID: "one")
        XCTAssertEqual(try reopened.receiveAuthenticatedRequest(peer: "phone", requestID: "status-id", request: status).phase,.accepted)
        XCTAssertThrowsError(try reopened.receiveAuthenticatedRequest(peer: "phone", requestID: "offer-id", request: offer, duplicateFrame: true))
        XCTAssertThrowsError(try reopened.receiveAuthenticatedRequest(peer: "phone", requestID: "status-id", request: offer))
        _ = try reopened.offerAuthenticated(peer: "other", manifest: manifest("two"))
        let chunk = try SyncFileRequest.chunk(transferID: "two", index: 0, bytes: Data("abc".utf8))
        XCTAssertThrowsError(try reopened.receiveAuthenticatedRequest(peer: "other", requestID: "chunk-id", request: chunk))
        XCTAssertThrowsError(try reopened.receiveAuthenticatedRequest(peer: "other", requestID: "next-id", request: .operation(.status, transferID: "two")))
        try reopened.acceptLocal(peer: "other", transferID: "two")
        XCTAssertEqual(try reopened.receiveAuthenticatedRequest(peer: "other", requestID: "chunk-id", request: chunk).phase,.accepted)
        XCTAssertTrue(try reopened.missingAuthenticated(peer: "other", transferID: "two").isEmpty)
    }
    func testRemoteCancelRetainsCompletedLocalCopyAndPersistsWireTerminalState() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }; let root = parent.appendingPathComponent("incoming")
        let files = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        let offer = try SyncFileRequest.offer(manifest())
        XCTAssertEqual(try files.executeAuthenticated(peer: "phone", request: offer).phase,.offered)
        let chunk = try SyncFileRequest.chunk(transferID: "one", index: 0, bytes: Data("abc".utf8))
        XCTAssertThrowsError(try files.executeAuthenticated(peer: "phone", request: chunk))
        try files.acceptLocal(peer: "phone", transferID: "one")
        XCTAssertEqual(try files.executeAuthenticated(peer: "phone", request: chunk).phase,.accepted)
        let finish = try SyncFileRequest.operation(.finish, transferID: "one")
        XCTAssertEqual(try files.executeAuthenticated(peer: "phone", request: finish).phase,.complete)
        let artifact = try files.finishAuthenticated(peer: "phone", transferID: "one")
        let cancel = try SyncFileRequest.operation(.cancel, transferID: "one")
        XCTAssertEqual(try files.executeAuthenticated(peer: "phone", request: cancel).phase,.cancelled)
        XCTAssertEqual(try files.listLocal().first?.phase,.complete)
        XCTAssertEqual(try Data(contentsOf: artifact),Data("abc".utf8)); files.close()
        let reopened = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root); defer { reopened.close() }
        XCTAssertEqual(try reopened.wirePhase(peer: "phone", transferID: "one"),.cancelled)
        XCTAssertEqual(try reopened.executeAuthenticated(peer: "phone", request: offer).phase,.cancelled)
        XCTAssertEqual(try reopened.executeAuthenticated(peer: "phone", request: finish).phase,.cancelled)
        XCTAssertEqual(try reopened.executeAuthenticated(peer: "phone", request: .operation(.missing, transferID: "one")).missing,[])
        XCTAssertThrowsError(try reopened.writeAuthenticated(peer: "phone", transferID: "one", index: 0, bytes: Data("abc".utf8)))
        XCTAssertEqual(try Data(contentsOf: artifact),Data("abc".utf8))
    }
    func testAssemblyQuotaRejectsWithoutGrantingConsentAndWholeHashFailureCanBeRemovedLocally() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }; let root = parent.appendingPathComponent("incoming")
        let files = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root); defer { files.close() }
        let large = SyncFileManifest(transferID: "large", size: 16 * 1024 * 1024, sha256: hash, chunkHashes: Array(repeating: hash, count: 256))
        _ = try files.offerAuthenticated(peer: "phone", manifest: large); try files.acceptLocal(peer: "phone", transferID: "large")
        _ = try files.offerAuthenticated(peer: "phone", manifest: manifest())
        XCTAssertThrowsError(try files.acceptLocal(peer: "phone", transferID: "one")) { XCTAssertEqual($0 as? SyncIncomingFileError,.quota) }
        XCTAssertEqual(try files.listLocal().last?.phase,.offered)
        try files.cancelUnfinished(peer: "phone", transferID: "large")
        let bad = SyncFileManifest(transferID: "bad", size: 3, sha256: String(repeating: "0", count: 64), chunkHashes: [hash])
        _ = try files.offerAuthenticated(peer: "phone", manifest: bad); try files.acceptLocal(peer: "phone", transferID: "bad")
        try files.writeAuthenticated(peer: "phone", transferID: "bad", index: 0, bytes: Data("abc".utf8))
        XCTAssertThrowsError(try files.finishAuthenticated(peer: "phone", transferID: "bad"))
        XCTAssertEqual(try files.listLocal().last?.phase,.completing)
        try files.removeLocal(peer: "phone", transferID: "bad")
        XCTAssertEqual(try files.listLocal().last?.phase,.cancelled)
    }
    // Simulate durable intent left by an interrupted operation; reopen the
    // actual journal under its lease after the owner has closed.
    private func phase(_ root: URL, _ phase: String) throws {
        let store = try FileSyncSnapshotStore(privateRoot: root); defer { store.close() }
        let raw = try XCTUnwrap(store.read())
        var value = try XCTUnwrap(JSONSerialization.jsonObject(with: raw) as? [String: Any])
        var offers = try XCTUnwrap(value["offers"] as? [[String: Any]])
        offers[0]["phase"] = phase; value["offers"] = offers
        XCTAssertTrue(try store.compareExchange(expected: raw, desired: JSONSerialization.data(withJSONObject: value)))
    }
    func testConsentBeforeAllocationCompletionAndGuestCancelPreservesArtifact() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }; let root = parent.appendingPathComponent("incoming")
        let files = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        XCTAssertEqual(try files.offerAuthenticated(peer: "phone", manifest: manifest()).phase,.offered)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("data").path).isEmpty)
        XCTAssertThrowsError(try files.writeAuthenticated(peer: "phone", transferID: "one", index: 0, bytes: Data("abc".utf8)))
        XCTAssertThrowsError(try files.offerAuthenticated(peer: "other", manifest: manifest()))
        try files.acceptLocal(peer: "phone", transferID: "one")
        XCTAssertEqual(try files.missingAuthenticated(peer: "phone", transferID: "one"),[0])
        XCTAssertThrowsError(try files.finishAuthenticated(peer: "phone", transferID: "one"))
        try files.writeAuthenticated(peer: "phone", transferID: "one", index: 0, bytes: Data("abc".utf8))
        XCTAssertTrue(try files.missingAuthenticated(peer: "phone", transferID: "one").isEmpty)
        let artifact = try files.finishAuthenticated(peer: "phone", transferID: "one"); files.close()
        try phase(root,"completing")
        let restored = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        try restored.cancelUnfinished(peer: "phone", transferID: "one")
        XCTAssertEqual(try restored.listLocal().first?.phase,.complete); XCTAssertEqual(try Data(contentsOf: artifact),Data("abc".utf8))
        try restored.removeLocal(peer: "phone", transferID: "one"); XCTAssertFalse(FileManager.default.fileExists(atPath: artifact.path)); restored.close()
        try phase(root,"cancelling")
        let cleanup = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root); defer { cleanup.close() }
        try cleanup.recover(); XCTAssertEqual(try cleanup.listLocal().first?.phase,.cancelled)
    }
    func testAcceptingRecoveryAndCancellationDoNotRequireExistingAllocation() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }; let root = parent.appendingPathComponent("incoming")
        let initial = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        _ = try initial.offerAuthenticated(peer: "phone", manifest: manifest()); initial.close(); try phase(root,"accepting")
        let recovered = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        try recovered.recover(); XCTAssertEqual(try recovered.listLocal().first?.phase,.accepted)
        try recovered.cancelUnfinished(peer: "phone", transferID: "one"); recovered.close()
        try phase(root,"accepting")
        let cancel = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root)
        try cancel.cancelUnfinished(peer: "phone", transferID: "one")
        XCTAssertEqual(try cancel.listLocal().first?.phase,.cancelled)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("data").path).isEmpty); cancel.close()
        XCTAssertThrowsError(try SyncIncomingFiles(appID: "other", deviceID: "watch", privateRoot: root))
    }
}
