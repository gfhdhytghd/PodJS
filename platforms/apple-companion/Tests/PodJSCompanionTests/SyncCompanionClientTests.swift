import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncCompanionClientTests: XCTestCase {
    private func parent() throws -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-client-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: value, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); return value
    }
    func testAppIsolationIdentityBindingAndReopen() throws {
        let directory = try parent(); defer { try? FileManager.default.removeItem(at: directory) }
        let a = try SyncCompanionClient(appID: "app-a", deviceID: "watch", privateParent: directory)
        let b = try SyncCompanionClient(appID: "app-b", deviceID: "watch", privateParent: directory); defer { b.close() }
        _ = try a.state.set("key", valueJSON: Data("true".utf8))
        try a.messageOutbox.enqueue(peer: "phone", messageID: "one", payload: Data([1]), ttlMilliseconds: 1000, highPriority: false, nowMilliseconds: 0)
        let wire = try SyncMessageEnvelope(expiresAt: 1000, highPriority: false, payload: Data([2])).encoded()
        _ = try a.messageInbox.receiveAuthenticated(peer: "phone", messageID: "incoming", envelopeBytes: wire, nowMilliseconds: 0)
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        _ = try a.incomingFiles.offerAuthenticated(peer: "phone", manifest: SyncFileManifest(transferID: "file", size: 3, sha256: hash, chunkHashes: [hash]))
        XCTAssertTrue(try b.incomingFiles.listLocal().isEmpty)
        XCTAssertNil(try b.state.get("key")); XCTAssertTrue(try b.messageOutbox.pending(peer: "phone", nowMilliseconds: 0).isEmpty)
        XCTAssertThrowsError(try SyncCompanionClient(appID: "app-a", deviceID: "watch", privateParent: directory))
        a.close(); XCTAssertThrowsError(try a.state.get("key")); XCTAssertThrowsError(try a.incomingFiles.listLocal())
        XCTAssertThrowsError(try SyncCompanionClient(appID: "app-a", deviceID: "other-device", privateParent: directory))
        let restored = try SyncCompanionClient(appID: "app-a", deviceID: "watch", privateParent: directory); defer { restored.close() }
        XCTAssertEqual(try restored.state.get("key"),Data("true".utf8))
        XCTAssertEqual(try restored.messageOutbox.pending(peer: "phone", nowMilliseconds: 1).count,1)
        XCTAssertEqual(try restored.messageInbox.pending(nowMilliseconds: 1).count,1)
        XCTAssertEqual(try restored.incomingFiles.listLocal().first?.phase,.offered)
    }
    func testClientOwnsSingleConnectionAndClosesBorrowedPump() throws {
        let directory = try parent(); defer { try? FileManager.default.removeItem(at: directory) }
        let client = try SyncCompanionClient(appID: "app", deviceID: "watch", privateParent: directory)
        let binding = SyncBinding(appID: "app", initiator: "phone", responder: "watch", initiatorNonce: Array(repeating: 1, count: 32), responderNonce: Array(repeating: 2, count: 32))
        let a = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: true, allowedChannels: [.state,.message,.ack])
        let b = try SyncSession(pairingKey: Array(repeating: 7, count: 32), binding: binding, localIsInitiator: false, allowedChannels: [.state,.message,.ack]); defer { a.close(); b.close() }
        let ap = try a.proof(), bp = try b.proof(); _ = try a.authenticate(remoteProof: bp); _ = try b.authenticate(remoteProof: ap)
        XCTAssertThrowsError(try client.attach(session: a))
        let pump = try client.attach(session: b)
        XCTAssertThrowsError(try client.attach(session: b)) { XCTAssertEqual($0 as? SyncCompanionClientError,.connectionOwned) }
        XCTAssertNotNil(try pump.sendState())
        client.detach(); XCTAssertThrowsError(try pump.sendState()); XCTAssertThrowsError(try b.proof())
        client.close(); XCTAssertThrowsError(try client.attach(session: a)) { XCTAssertEqual($0 as? SyncCompanionClientError,.closed) }
    }
}
