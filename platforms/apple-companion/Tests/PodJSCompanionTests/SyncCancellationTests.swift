import Foundation
import XCTest
@testable import PodJSCompanion

private final class CancelCommittedStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; let token: SyncCancellation
    init(_ token: SyncCancellation) { self.token = token }
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        guard bytes == expected else { return false }; bytes = desired; token.cancel(); return true
    }
}
final class SyncCancellationTests: XCTestCase {
    func testEmptyImportAndRegistrationWithLiveToken() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let sourceURL = root.appendingPathComponent("empty"); try Data().write(to: sourceURL)
        let token = try SyncCancellation()
        let client = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root); defer { client.close() }
        let manifest = try client.importOutgoing(sourceURL: sourceURL, peer: "watch", transferID: "empty", cancellation: token)
        XCTAssertEqual(manifest.size, 0); XCTAssertTrue(manifest.chunkHashes.isEmpty)
        XCTAssertEqual(try client.outgoingTransfers.list().count, 1)
        XCTAssertFalse(token.isCancelled)
    }
    private func parent() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-cancel-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return root
    }
    func testPreCancelledImportAndSaveDoNotMutateAndTokenIsThreadSafe() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let client = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root); defer { client.close() }
        let token = try SyncCancellation(); XCTAssertFalse(token.isCancelled)
        DispatchQueue.concurrentPerform(iterations: 20) { _ in token.cancel(); XCTAssertTrue(token.isCancelled) }
        XCTAssertThrowsError(try client.importOutgoing(sourceURL: root.appendingPathComponent("missing"), peer: "watch", transferID: "one", cancellation: token)) {
            XCTAssertEqual($0 as? SyncCancellationError,.cancelled)
        }
        XCTAssertTrue(try client.outgoingFiles.list().isEmpty); XCTAssertTrue(try client.outgoingTransfers.list().isEmpty)
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: root); defer { storage.close() }
        XCTAssertThrowsError(try client.incomingFiles.saveCompleteLocal(peer: "watch", transferID: "unknown", path: "target", storage: storage, cancellation: token)) {
            XCTAssertEqual($0 as? SyncCancellationError,.cancelled)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("files/target").path))
    }
    func testCancellationAfterSourceScanPreventsStageButAfterRegistryCommitRetainsTask() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appendingPathComponent("source"); try Data("abc".utf8).write(to: path)
        let early = try SyncCancellation(), late = try SyncCancellation()
        let store = CancelCommittedStore(late), transfers = try SyncOutgoingTransfers(appID: "app", deviceID: "phone", store: store)
        let files = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("private"), outgoingTransfers: transfers); defer { files.close() }
        let source = try SyncHostFileSource(url: path, transferID: "early", mime: "", cancellation: early)
        early.cancel(); XCTAssertThrowsError(try files.importSource(source, peer: "watch"))
        XCTAssertTrue(try SyncOutgoingFiles(sharedFiles: files).list().isEmpty)
        let committed = try SyncHostFileSource(url: path, transferID: "committed", mime: "", cancellation: late)
        _ = try files.importSource(committed, peer: "watch")
        XCTAssertTrue(late.isCancelled)
        XCTAssertEqual(try transfers.list()[0].phase,.queued)
        XCTAssertEqual(try SyncOutgoingFiles(sharedFiles: files).list()[0].phase,.complete)
        XCTAssertEqual(try SyncOutgoingFiles(sharedFiles: files).readChunk(transferID: "committed", index: 0),Data("abc".utf8))
    }
}
