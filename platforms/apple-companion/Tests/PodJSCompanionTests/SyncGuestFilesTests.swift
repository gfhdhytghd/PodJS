import Foundation
import XCTest
import CPodJSSync
@testable import PodJSCompanion

final class SyncGuestFilesTests: XCTestCase {
    private let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private func parent() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-guest-save-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        for path in ["guest", "guest/files", "guest/files/nested"] {
            try FileManager.default.createDirectory(at: root.appendingPathComponent(path), withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        }
        return root
    }
    private func manifest() -> SyncFileManifest { SyncFileManifest(transferID: "one", size: 3, sha256: hash, chunkHashes: [hash]) }
    func testIncomingSaveIsExplicitNoOverwriteAndRetainsCancelledCopy() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let incoming = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("incoming")); defer { incoming.close() }
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: root.appendingPathComponent("guest")); defer { storage.close() }
        _ = try incoming.offerAuthenticated(peer: "watch", manifest: manifest())
        XCTAssertThrowsError(try incoming.saveCompleteLocal(peer: "watch", transferID: "one", path: "nested/result", storage: storage))
        try incoming.acceptLocal(peer: "watch", transferID: "one")
        try incoming.writeAuthenticated(peer: "watch", transferID: "one", index: 0, bytes: Data("abc".utf8))
        let source = try incoming.finishAuthenticated(peer: "watch", transferID: "one")
        try incoming.cancelAuthenticated(peer: "watch", transferID: "one")
        try incoming.saveCompleteLocal(peer: "watch", transferID: "one", path: "nested/result", storage: storage)
        try incoming.saveCompleteLocal(peer: "watch", transferID: "one", path: "nested/result", storage: storage)
        let destination = root.appendingPathComponent("guest/files/nested/result")
        XCTAssertEqual(try Data(contentsOf: destination),Data("abc".utf8)); XCTAssertEqual(try Data(contentsOf: source),Data("abc".utf8))
        try Data("bad".utf8).write(to: destination)
        XCTAssertThrowsError(try incoming.saveCompleteLocal(peer: "watch", transferID: "one", path: "nested/result", storage: storage))
        XCTAssertEqual(try Data(contentsOf: destination),Data("bad".utf8))
        storage.close(); XCTAssertThrowsError(try incoming.saveCompleteLocal(peer: "watch", transferID: "one", path: "other", storage: storage))
    }
    func testDescriptorTraversalAndFailedVerificationDoNotPublish() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: root.appendingPathComponent("guest")); defer { storage.close() }
        let source = root.appendingPathComponent("source"); try Data("abc".utf8).write(to: source)
        for path in ["../escape", "/escape", "nested//escape", "./escape", "nested/../escape", "nested/", "bad\0path"] {
            XCTAssertThrowsError(try storage.publish(source: source, manifest: manifest(), path: path))
        }
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("guest/files/link"), withDestinationURL: root)
        XCTAssertThrowsError(try storage.publish(source: source, manifest: manifest(), path: "link/escape"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("escape").path))
        try FileManager.default.removeItem(at: root.appendingPathComponent("guest/files/link"))
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("guest/files/target"), withDestinationURL: source)
        XCTAssertThrowsError(try storage.publish(source: source, manifest: manifest(), path: "target"))
        try FileManager.default.removeItem(at: root.appendingPathComponent("guest/files/target"))
        try Data("bad".utf8).write(to: source)
        XCTAssertThrowsError(try storage.publish(source: source, manifest: manifest(), path: "nested/failed"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("guest/files/nested/failed").path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("guest/sync-save").path),["owner.lock"])
    }
    func testGateQuotaAndInterruptedPublicationRecovery() throws {
        let root = try parent(); defer { try? FileManager.default.removeItem(at: root) }
        let guest = root.appendingPathComponent("guest")
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: guest); defer { storage.close() }
        let source = root.appendingPathComponent("source"); try Data("abc".utf8).write(to: source)
        let gate = try XCTUnwrap(guest.path.withCString { pod_guest_io_open($0) }); defer { pod_guest_io_close(gate) }
        XCTAssertEqual(pod_guest_io_try_enter(gate),1)
        XCTAssertThrowsError(try storage.publish(source: source, manifest: manifest(), path: "result")) { XCTAssertEqual($0 as? SyncGuestFilesError,.busy) }
        pod_guest_io_leave(gate)
        let occupied = guest.appendingPathComponent("files/occupied"); try Data().write(to: occupied)
        let descriptor = try FileHandle(forWritingTo: occupied); try descriptor.truncate(atOffset: 16 * 1024 * 1024); try descriptor.close()
        XCTAssertThrowsError(try storage.publish(source: source, manifest: manifest(), path: "result"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: guest.appendingPathComponent("files/result").path))
        try FileManager.default.removeItem(at: occupied)
        try storage.publish(source: source, manifest: manifest(), path: "result")
        // Simulate death after link publication but before private-name cleanup.
        let stale = guest.appendingPathComponent("sync-save/save-abc")
        try FileManager.default.linkItem(at: guest.appendingPathComponent("files/result"), to: stale)
        try storage.publish(source: source, manifest: manifest(), path: "result")
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
        XCTAssertEqual(try Data(contentsOf: guest.appendingPathComponent("files/result")),Data("abc".utf8))
    }
}
