import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncIncomingFileServicesTests: XCTestCase {
    func testEventBackpressureSubscriptionResetAndConsentExposure() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let files = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("incoming")); defer { files.close() }
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: root); defer { storage.close() }
        let service = try SyncIncomingFileServices(files: files, storage: storage)
        let foreign = try SyncGuestFiles(appID: "other", runtimeDataRoot: root); defer { foreign.close() }
        XCTAssertThrowsError(try SyncIncomingFileServices(files: files, storage: foreign)) {
            XCTAssertEqual($0 as? SyncIncomingFileServiceError, .identity)
        }
        let hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        _ = try files.offerAuthenticated(peer: "watch", manifest: SyncFileManifest(transferID: "one", size: 0, sha256: hash, chunkHashes: []))
        let args = Data(#"{"transferId":"one"}"#.utf8)
        XCTAssertNil(try service.nextEvent())
        service.setEventsActive(true)
        let first = try XCTUnwrap(service.nextEvent())
        XCTAssertEqual(try service.nextEvent(), first)
        XCTAssertThrowsError(try service.handle(method: "sync.files.accept", arguments: args))
        service.setEventsActive(false)
        XCTAssertFalse(service.acknowledgeEvent(first))
        XCTAssertNil(try service.nextEvent())
        service.setEventsActive(true)
        let fresh = try XCTUnwrap(service.nextEvent())
        XCTAssertNotEqual(fresh, first)
        XCTAssertFalse(service.acknowledgeEvent(first))
        XCTAssertTrue(service.acknowledgeEvent(fresh))
        XCTAssertFalse(service.acknowledgeEvent(fresh))
        XCTAssertNil(try service.nextEvent())
        _ = try service.handle(method: "sync.files.accept", arguments: args)
        // Cursor wraps at the end; nil is not end-of-subscription.
        let changed = try service.nextEvent() ?? service.nextEvent()
        let event = try XCTUnwrap(changed)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: event.json) as? [String: Any])
        XCTAssertEqual(json["t"] as? String, "sync.file.changed")
        XCTAssertEqual((json["value"] as? [String: Any])?["state"] as? String, "transferring")
        XCTAssertTrue(service.acknowledgeEvent(event))
    }
    func testGuestConsentProgressSaveAndFreshLifetime() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("files"), withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        let files = try SyncIncomingFiles(appID: "app", deviceID: "phone", privateRoot: root.appendingPathComponent("incoming")); defer { files.close() }
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: root); defer { storage.close() }
        let service = try SyncIncomingFileServices(files: files, storage: storage)
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        _ = try files.offerAuthenticated(peer: "watch", manifest: SyncFileManifest(transferID: "one", size: 3, sha256: hash, chunkHashes: [hash]))
        let args = Data(#"{"transferId":"one"}"#.utf8)
        func status(_ method: String) throws -> SyncIncomingFileStatus {
            try JSONDecoder().decode(SyncIncomingFileStatus.self, from: service.handle(method: method, arguments: args))
        }
        XCTAssertThrowsError(try service.handle(method: "sync.files.accept", arguments: args)) { XCTAssertEqual($0 as? SyncIncomingFileServiceError, .notExposed) }
        XCTAssertEqual(try status("sync.files.status").state, .offered)
        XCTAssertEqual(try status("sync.files.accept").receivedBytes, 0)
        try files.writeAuthenticated(peer: "watch", transferID: "one", index: 0, bytes: Data("abc".utf8))
        let progress = try status("sync.files.status")
        XCTAssertEqual(progress.state, .transferring); XCTAssertEqual(progress.receivedBytes, 3)
        let source = try files.finishAuthenticated(peer: "watch", transferID: "one")
        try files.cancelAuthenticated(peer: "watch", transferID: "one")
        XCTAssertEqual(try status("sync.files.status").state, .complete)
        let save = Data(#"{"transferId":"one","path":"result"}"#.utf8)
        let saved = try service.handle(method: "sync.files.save", arguments: save)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: saved) as? [String: Any])?["path"] as? String, "result")
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("files/result")), Data("abc".utf8))
        XCTAssertEqual(try service.handle(method: "sync.files.cancel", arguments: args), Data("null".utf8))
        XCTAssertEqual(try Data(contentsOf: source), Data("abc".utf8))
        let fresh = try SyncIncomingFileServices(files: files, storage: storage)
        XCTAssertThrowsError(try fresh.handle(method: "sync.files.save", arguments: save))
        XCTAssertThrowsError(try service.handle(method: "sync.files.status", arguments: Data(#"{"transferId":"one","peer":"other"}"#.utf8)))
        XCTAssertThrowsError(try service.handle(method: "sync.files.offer", arguments: args))
        let token = try SyncCancellation(); token.cancel()
        XCTAssertThrowsError(try service.handle(method: "sync.files.status", arguments: args, cancellation: token))
        try Data("bad".utf8).write(to: source)
        XCTAssertEqual(try status("sync.files.status").state, .failed)
        XCTAssertThrowsError(try service.handle(method: "sync.files.save", arguments: save))
    }
}
