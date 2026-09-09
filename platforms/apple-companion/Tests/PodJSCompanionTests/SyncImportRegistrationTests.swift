import Foundation
import XCTest
@testable import PodJSCompanion

private final class RegistrationStore: SyncSnapshotStore, @unchecked Sendable {
    var bytes: Data?; var fail = false; var commitBeforeFailure = false
    func read() throws -> Data? { bytes }
    func compareExchange(expected: Data?, desired: Data) throws -> Bool {
        guard bytes == expected else { return false }
        if fail && !commitBeforeFailure { throw SyncSnapshotError.storageFailure }
        bytes = desired
        if fail { throw SyncSnapshotError.storageFailure }; return true
    }
}
final class SyncImportRegistrationTests: XCTestCase {
    private func parent() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-register-import-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return root
    }
    func testRegistrationCommitUncertaintyPreservesOnlyDurablyRegisteredSource() throws {
        for committed in [false,true] {
            let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
            let original = parent.appendingPathComponent("source"); try Data("abc".utf8).write(to: original)
            let store = RegistrationStore(); store.fail = true; store.commitBeforeFailure = committed
            let transfers = try SyncOutgoingTransfers(appID: "app", deviceID: "phone", store: store)
            let owner = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: parent.appendingPathComponent("files"), outgoingTransfers: transfers); defer { owner.close() }
            let source = try SyncHostFileSource(url: original, transferID: "one", mime: "")
            XCTAssertThrowsError(try owner.importSource(source, peer: "watch"))
            store.fail = false
            let sources = SyncOutgoingFiles(sharedFiles: owner)
            XCTAssertEqual(try sources.list()[0].phase,committed ? .complete : .removed)
            XCTAssertEqual(try transfers.list().count,committed ? 1 : 0)
            if committed { XCTAssertEqual(try sources.readChunk(transferID: "one", index: 0),Data("abc".utf8)) }
            else { XCTAssertFalse(FileManager.default.fileExists(atPath: parent.appendingPathComponent("files/data/one").path)) }
        }
    }
    func testClientReopenResolvesRegisteringIntentFromActualTaskJournal() throws {
        for registered in [false,true] {
            let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
            let original = parent.appendingPathComponent("source"); try Data("abc".utf8).write(to: original)
            let client = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: parent)
            _ = try client.outgoingFiles.importFile(sourceURL: original, transferID: "one")
            if registered { _ = try client.registerOutgoing(peer: "watch", transferID: "one") }
            client.close()
            let root = parent.appendingPathComponent("podjs-companion-app/incoming-files")
            let journal = try FileSyncSnapshotStore(privateRoot: root)
            let raw = try XCTUnwrap(journal.read())
            var value = try XCTUnwrap(JSONSerialization.jsonObject(with: raw) as? [String: Any])
            var sources = try XCTUnwrap(value["sources"] as? [[String: Any]])
            sources[0]["phase"] = "registering"; sources[0]["peer"] = "watch"; value["sources"] = sources
            XCTAssertTrue(try journal.compareExchange(expected: raw, desired: JSONSerialization.data(withJSONObject: value))); journal.close()
            let reopened = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: parent); defer { reopened.close() }
            XCTAssertEqual(try reopened.outgoingFiles.list()[0].phase, registered ? .complete : .removed)
            XCTAssertEqual(try reopened.outgoingTransfers.list().count,registered ? 1 : 0)
            if registered { XCTAssertEqual(try reopened.outgoingFiles.readChunk(transferID: "one", index: 0),Data("abc".utf8)) }
            else { XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("data/one").path)) }
        }
    }
}
