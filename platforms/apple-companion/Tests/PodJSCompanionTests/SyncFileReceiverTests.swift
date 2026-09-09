import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncFileReceiverTests: XCTestCase {
    private let abc = Data("abc".utf8)
    private let digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private func manifest(_ id: String = "file") -> SyncFileManifest {
        SyncFileManifest(transferID: id, size: 3, sha256: digest, chunkHashes: [digest])
    }
    private func temporary() throws -> URL {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-swift-files-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        return parent
    }
    func testNativeResumeHashVerificationAndIdempotentFinish() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("peer")
        let first = try SyncFileReceiver(privateRoot: root)
        try first.prepareAccepted(manifest()); XCTAssertEqual(try first.missing(transferID: "file"), [0])
        XCTAssertThrowsError(try first.writeChunk(transferID: "file", index: 0, bytes: Data([1, 2, 3])))
        XCTAssertThrowsError(try first.finish(transferID: "file"))
        try first.writeChunk(transferID: "file", index: 0, bytes: abc); first.close()
        let reopened = try SyncFileReceiver(privateRoot: root); defer { reopened.close() }
        XCTAssertEqual(try reopened.missing(transferID: "file"), [])
        let url = try reopened.finish(transferID: "file")
        XCTAssertEqual(try Data(contentsOf: url), abc); XCTAssertEqual(try reopened.finish(transferID: "file"), url)
    }
    func testCompletedChunkReadUsesNativeValidationAndRejectsCorruption() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let receiver = try SyncFileReceiver(privateRoot: parent.appendingPathComponent("peer")); defer { receiver.close() }
        try receiver.prepareAccepted(manifest())
        XCTAssertThrowsError(try receiver.readCompleteChunk(transferID: "file", index: 0))
        try receiver.writeChunk(transferID: "file", index: 0, bytes: abc)
        let url = try receiver.finish(transferID: "file")
        XCTAssertEqual(try receiver.readCompleteChunk(transferID: "file", index: 0),abc)
        XCTAssertThrowsError(try receiver.readCompleteChunk(transferID: "file", index: 1))
        XCTAssertThrowsError(try receiver.readCompleteChunk(transferID: "file", index: -1))
        XCTAssertThrowsError(try receiver.readCompleteChunk(transferID: "../file", index: 0))
        try Data("bad".utf8).write(to: url)
        XCTAssertThrowsError(try receiver.readCompleteChunk(transferID: "file", index: 0))
        XCTAssertEqual(try Data(contentsOf: url),Data("bad".utf8))
        receiver.close(); XCTAssertThrowsError(try receiver.readCompleteChunk(transferID: "file", index: 0))
    }
    func testExclusiveLeaseAndReleaseOnClose() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("peer"), first = try SyncFileReceiver(privateRoot: parent.appendingPathComponent("peer"))
        XCTAssertThrowsError(try SyncFileReceiver(privateRoot: root))
        first.close(); first.close(); let next = try SyncFileReceiver(privateRoot: root); next.close()
        XCTAssertThrowsError(try first.missing(transferID: "file")) { XCTAssertEqual($0 as? SyncFileReceiverError, .closed) }
    }
    func testPrivateLeaseRefusesSymlinksAndUnsafePermissions() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("peer"), target = parent.appendingPathComponent("other")
        try abc.write(to: target)
        let lock = parent.appendingPathComponent(".podjs-sync-peer.lock")
        try FileManager.default.createSymbolicLink(at: lock, withDestinationURL: target)
        XCTAssertThrowsError(try SyncFileReceiver(privateRoot: root)); XCTAssertEqual(try Data(contentsOf: target), abc)
        try FileManager.default.removeItem(at: lock)
        let receiver = try SyncFileReceiver(privateRoot: root); receiver.close()
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: lock.path)
        XCTAssertThrowsError(try SyncFileReceiver(privateRoot: root))
    }
    func testCleanupOnlyDeletesSelectedHostArtifactAndMetadataIsValidated() throws {
        let parent = try temporary(); defer { try? FileManager.default.removeItem(at: parent) }
        let receiver = try SyncFileReceiver(privateRoot: parent.appendingPathComponent("peer")); defer { receiver.close() }
        XCTAssertThrowsError(try receiver.prepareAccepted(manifest("../outside")))
        try receiver.prepareAccepted(manifest()); try receiver.prepareAccepted(manifest("other"))
        try receiver.writeChunk(transferID: "file", index: 0, bytes: abc)
        let completed = try receiver.finish(transferID: "file"); try receiver.removeHostCopy(transferID: "file")
        XCTAssertFalse(FileManager.default.fileExists(atPath: completed.path))
        XCTAssertEqual(try receiver.missing(transferID: "other"), [0])
        XCTAssertThrowsError(try receiver.writeChunk(transferID: "other", index: 0, bytes: Data(repeating: 0, count: 65_537)))
    }
}
