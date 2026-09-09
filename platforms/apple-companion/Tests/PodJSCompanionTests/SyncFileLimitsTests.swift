import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncFileLimitsTests: XCTestCase {
    private func parent() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-profile-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return root
    }
    private func manifest(_ id: String, size: UInt64 = 3) -> SyncFileManifest {
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        return SyncFileManifest(transferID: id, size: size, sha256: hash, chunkHashes: [hash])
    }
    func testProfileIntersectionAdmissionAndPersistedReopenLimits() throws {
        XCTAssertThrowsError(try SyncFileLimits(maximumFileBytes: 16 * 1024 * 1024 + 1))
        XCTAssertThrowsError(try SyncFileLimits(maximumAppBytes: 32 * 1024 * 1024 + 1))
        XCTAssertThrowsError(try SyncFileLimits(maximumTransfers: 129))
        let declared = try SyncFileLimits(maximumFileBytes: 4, maximumAppBytes: 6, maximumTransfers: 3)
        let profile = try SyncFileLimits(maximumFileBytes: 3, maximumAppBytes: 9, maximumTransfers: 2)
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let client = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root, declaredFileLimits: declared, profileFileLimits: profile)
        XCTAssertEqual(client.fileLimits.maximumFileBytes,3); XCTAssertEqual(client.fileLimits.maximumAppBytes,6); XCTAssertEqual(client.fileLimits.maximumTransfers,2)
        XCTAssertThrowsError(try client.incomingFiles.offerAuthenticated(peer: "watch", manifest: manifest("too-big", size: 4)))
        try client.outgoingFiles.prepare(manifest("source"))
        _ = try client.incomingFiles.offerAuthenticated(peer: "watch", manifest: manifest("incoming"))
        XCTAssertThrowsError(try client.incomingFiles.acceptLocal(peer: "watch", transferID: "incoming"))
        try client.outgoingFiles.remove(transferID: "source")
        try client.incomingFiles.acceptLocal(peer: "watch", transferID: "incoming")
        XCTAssertThrowsError(try client.outgoingFiles.prepare(manifest("third")))
        client.close()
        XCTAssertThrowsError(try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root))
        let reopened = try SyncCompanionClient(appID: "app", deviceID: "phone", privateParent: root, declaredFileLimits: declared, profileFileLimits: profile); defer { reopened.close() }
        XCTAssertEqual(try reopened.incomingFiles.listLocal().first?.phase,.accepted)
    }
    func testDamagedCompleteRepairCannotBypassTightProfileReservation() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let files = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("files"),
            limits: SyncFileLimits(maximumFileBytes: 3, maximumAppBytes: 9)); defer { files.close() }
        _ = try files.offerAuthenticated(peer: "watch", manifest: manifest("incoming"))
        try files.acceptLocal(peer: "watch", transferID: "incoming")
        try files.writeAuthenticated(peer: "watch", transferID: "incoming", index: 0, bytes: Data("abc".utf8))
        let artifact = try files.finishAuthenticated(peer: "watch", transferID: "incoming")
        let outgoing = SyncOutgoingFiles(sharedFiles: files); try outgoing.prepare(manifest("source"))
        try Data("bad".utf8).write(to: artifact)
        XCTAssertThrowsError(try files.writeAuthenticated(peer: "watch", transferID: "incoming", index: 0, bytes: Data("abc".utf8)))
        XCTAssertEqual(try Data(contentsOf: artifact),Data("bad".utf8)); XCTAssertEqual(try files.listLocal()[0].phase,.complete)
        try outgoing.remove(transferID: "source")
        try files.writeAuthenticated(peer: "watch", transferID: "incoming", index: 0, bytes: Data("abc".utf8))
        XCTAssertEqual(try files.listLocal()[0].phase,.accepted)
        XCTAssertThrowsError(try outgoing.prepare(manifest("another")))
        _ = try files.finishAuthenticated(peer: "watch", transferID: "incoming")
        try outgoing.prepare(manifest("another"))
        XCTAssertEqual(try Data(contentsOf: artifact),Data("abc".utf8))
    }
}
