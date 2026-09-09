import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncOutgoingFilesTests: XCTestCase {
    func testHostFileImportFreezesChunksAndRejectsUnsafeSource() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
        let original = parent.appendingPathComponent("original")
        let bytes = Data((0..<65539).map { UInt8($0 % 251) }); try bytes.write(to: original)
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: parent.appendingPathComponent("files")); defer { incoming.close() }
        let outgoing = SyncOutgoingFiles(sharedFiles: incoming)
        let manifest = try outgoing.importFile(sourceURL: original, transferID: "source", mime: "text/plain")
        XCTAssertEqual(manifest.size,65539); XCTAssertEqual(manifest.chunkHashes.count,2)
        XCTAssertEqual(manifest.sha256,try messageDigest(bytes).map { String(format: "%02x", $0) }.joined())
        try Data("changed".utf8).write(to: original); try FileManager.default.removeItem(at: original)
        XCTAssertEqual(try outgoing.readChunk(transferID: "source", index: 0),Data(bytes.prefix(65536)))
        XCTAssertEqual(try outgoing.readChunk(transferID: "source", index: 1),Data(bytes.suffix(3)))
        let empty = parent.appendingPathComponent("empty"); try Data().write(to: empty)
        XCTAssertEqual(try outgoing.importFile(sourceURL: empty, transferID: "empty").size,0)
        XCTAssertEqual(try outgoing.list().last?.phase,.complete)
        let link = parent.appendingPathComponent("link"); try FileManager.default.createSymbolicLink(at: link, withDestinationURL: empty)
        XCTAssertThrowsError(try outgoing.importFile(sourceURL: link, transferID: "link"))
        XCTAssertThrowsError(try outgoing.importFile(sourceURL: parent, transferID: "directory"))
        XCTAssertThrowsError(try outgoing.importFile(sourceURL: empty, transferID: "../invalid"))
        XCTAssertEqual(try outgoing.list().count,2)
    }
    func testChangedDescriptorSourceRollsBackImportWithoutReusingID() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
        let original = parent.appendingPathComponent("original"); try Data("abc".utf8).write(to: original)
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: parent.appendingPathComponent("files")); defer { incoming.close() }
        let source = try SyncHostFileSource(url: original, transferID: "changed", mime: "")
        try Data("xyz".utf8).write(to: original)
        XCTAssertThrowsError(try incoming.importSource(source))
        let outgoing = SyncOutgoingFiles(sharedFiles: incoming)
        XCTAssertEqual(try outgoing.list().first?.phase,.removed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: parent.appendingPathComponent("files/data/changed").path))
        XCTAssertThrowsError(try outgoing.importFile(sourceURL: original, transferID: "changed"))
        let empty = parent.appendingPathComponent("empty"); try Data().write(to: empty)
        let emptySource = try SyncHostFileSource(url: empty, transferID: "grew", mime: "")
        try Data("x".utf8).write(to: empty)
        XCTAssertThrowsError(try incoming.importSource(emptySource))
        XCTAssertEqual(try outgoing.list().last?.phase,.removed)
        _ = try outgoing.importFile(sourceURL: original, transferID: "fresh")
        XCTAssertEqual(try outgoing.readChunk(transferID: "fresh", index: 0),Data("xyz".utf8))
    }
    func testAbandonedImportRecoveryRemovesUnpublishedBytes() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("files")
        let initial = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root)
        let outgoing = SyncOutgoingFiles(sharedFiles: initial)
        try outgoing.prepare(manifest("abandoned"))
        try outgoing.writeChunk(transferID: "abandoned", index: 0, bytes: Data("abc".utf8)); initial.close()
        let store = try FileSyncSnapshotStore(privateRoot: root)
        let raw = try XCTUnwrap(store.read())
        var value = try XCTUnwrap(JSONSerialization.jsonObject(with: raw) as? [String: Any])
        var sources = try XCTUnwrap(value["sources"] as? [[String: Any]])
        sources[0]["phase"] = "importing"; value["sources"] = sources
        XCTAssertTrue(try store.compareExchange(expected: raw, desired: JSONSerialization.data(withJSONObject: value))); store.close()
        let resumed = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root); defer { resumed.close() }
        try resumed.recover()
        XCTAssertEqual(try SyncOutgoingFiles(sharedFiles: resumed).list().first?.phase,.removed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("data/abandoned").path))
        XCTAssertThrowsError(try resumed.offerAuthenticated(peer: "watch", manifest: manifest("abandoned")))
    }
    private func parent() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-sources-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return root
    }
    private func manifest(_ id: String, large: Bool = false) -> SyncFileManifest {
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        return SyncFileManifest(transferID: id, size: large ? 16 * 1024 * 1024 : 3, sha256: hash, chunkHashes: Array(repeating: hash, count: large ? 256 : 1))
    }
    func testImmutableSourceReopensAndReservesIdentityAcrossDirections() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("files")
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root)
        let outgoing = SyncOutgoingFiles(sharedFiles: incoming)
        try outgoing.prepare(manifest("source"))
        XCTAssertThrowsError(try outgoing.readChunk(transferID: "source", index: 0))
        XCTAssertThrowsError(try outgoing.finish(transferID: "source"))
        XCTAssertThrowsError(try incoming.offerAuthenticated(peer: "watch", manifest: manifest("source")))
        try outgoing.writeChunk(transferID: "source", index: 0, bytes: Data("abc".utf8))
        try outgoing.finish(transferID: "source")
        XCTAssertThrowsError(try outgoing.writeChunk(transferID: "source", index: 0, bytes: Data("abc".utf8)))
        incoming.close(); XCTAssertThrowsError(try outgoing.list())
        let reopened = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root); defer { reopened.close() }
        let sources = SyncOutgoingFiles(sharedFiles: reopened)
        XCTAssertEqual(try sources.readChunk(transferID: "source", index: 0),Data("abc".utf8))
        try sources.prepare(manifest("source")); try sources.finish(transferID: "source")
        XCTAssertTrue(try reopened.listLocal().isEmpty)
        try sources.remove(transferID: "source"); try sources.remove(transferID: "source")
        XCTAssertEqual(try sources.list().first?.phase,.removed)
        XCTAssertThrowsError(try sources.prepare(manifest("source")))
        XCTAssertThrowsError(try reopened.offerAuthenticated(peer: "watch", manifest: manifest("source")))
        _ = try reopened.offerAuthenticated(peer: "watch", manifest: manifest("incoming"))
        XCTAssertThrowsError(try sources.prepare(manifest("incoming")))
    }
    func testBothDirectionsShareAssemblyQuotaAndRemovalReleasesOnlyBytes() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: parent.appendingPathComponent("files")); defer { incoming.close() }
        let outgoing = SyncOutgoingFiles(sharedFiles: incoming)
        try outgoing.prepare(manifest("large-source", large: true))
        _ = try incoming.offerAuthenticated(peer: "watch", manifest: manifest("incoming"))
        XCTAssertThrowsError(try incoming.acceptLocal(peer: "watch", transferID: "incoming"))
        XCTAssertEqual(try incoming.listLocal().first?.phase,.offered)
        XCTAssertThrowsError(try outgoing.prepare(manifest("extra")))
        XCTAssertEqual(try outgoing.list().count,1)
        try outgoing.remove(transferID: "large-source")
        try incoming.acceptLocal(peer: "watch", transferID: "incoming")
        XCTAssertThrowsError(try outgoing.prepare(manifest("another-large", large: true)))
        XCTAssertEqual(try outgoing.list().count,1)
        try incoming.cancelUnfinished(peer: "watch", transferID: "incoming")
        try outgoing.prepare(manifest("another-large", large: true))
        XCTAssertEqual(try outgoing.list().last?.phase,.staging)
    }
    func testPersistedSourceIntentsRecoverUnderReopenedLease() throws {
        let parent = try parent(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("files")
        func phase(_ phase: String) throws {
            let store = try FileSyncSnapshotStore(privateRoot: root); defer { store.close() }
            let raw = try XCTUnwrap(store.read())
            var value = try XCTUnwrap(JSONSerialization.jsonObject(with: raw) as? [String: Any])
            var sources = try XCTUnwrap(value["sources"] as? [[String: Any]])
            sources[0]["phase"] = phase; value["sources"] = sources
            XCTAssertTrue(try store.compareExchange(expected: raw, desired: JSONSerialization.data(withJSONObject: value)))
        }
        let initial = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root)
        let first = SyncOutgoingFiles(sharedFiles: initial)
        try first.prepare(manifest("source")); initial.close(); try phase("preparing")
        let resumed = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root)
        try resumed.recover(); let second = SyncOutgoingFiles(sharedFiles: resumed)
        XCTAssertEqual(try second.list().first?.phase,.staging)
        try second.writeChunk(transferID: "source", index: 0, bytes: Data("abc".utf8))
        try second.finish(transferID: "source"); resumed.close(); try phase("completing")
        let finished = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root)
        try finished.recover(); let third = SyncOutgoingFiles(sharedFiles: finished)
        XCTAssertEqual(try third.readChunk(transferID: "source", index: 0),Data("abc".utf8))
        try third.remove(transferID: "source"); finished.close(); try phase("removing")
        let removed = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root); defer { removed.close() }
        try removed.recover(); XCTAssertEqual(try SyncOutgoingFiles(sharedFiles: removed).list().first?.phase,.removed)
    }
}
