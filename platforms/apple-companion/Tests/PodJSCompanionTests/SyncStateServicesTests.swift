import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncStateServicesTests: XCTestCase {
    func testGuestStateNullTombstoneDurabilityAndLargeQueuedValue() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try FileSyncSnapshotStore(privateRoot: root)
        let state = try SyncState(appID: "app", deviceID: "watch", store: store)
        let services = SyncStateServices(state: state)
        let key = Data(#"{"key":"key"}"#.utf8)
        func object(_ data: Data) throws -> [String: Any] { try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any]) }
        XCTAssertEqual(try object(services.handle(method: "sync.state.get", arguments: key))["exists"] as? Bool, false)
        let set = try object(services.handle(method: "sync.state.set", arguments: Data(#"{"key":"key","value":null}"#.utf8)))
        XCTAssertTrue(set["value"] is NSNull); XCTAssertEqual(set["counter"] as? Int, 1)
        XCTAssertEqual(set["deviceId"] as? String, "watch")
        XCTAssertEqual(try object(services.handle(method: "sync.state.get", arguments: key))["exists"] as? Bool, true)
        _ = try services.handle(method: "sync.state.delete", arguments: key)
        let deleted = try object(services.handle(method: "sync.state.get", arguments: key))
        XCTAssertEqual(deleted["exists"] as? Bool, false)
        XCTAssertEqual((deleted["entry"] as? [String: Any])?["deleted"] as? Bool, true)
        let token = try SyncCancellation(); token.cancel()
        XCTAssertThrowsError(try services.handle(method: "sync.state.set", arguments: Data(#"{"key":"other","value":true}"#.utf8), cancellation: token))
        XCTAssertNil(try state.get("other"))
        XCTAssertThrowsError(try services.handle(method: "sync.state.synchronize", arguments: Data(#"{"peerId":"phone"}"#.utf8))) { XCTAssertEqual($0 as? SyncStateServiceError, .unsupported) }
        XCTAssertThrowsError(try services.handle(method: "sync.state.set", arguments: Data(#"{"key":"key","value":1,"deviceId":"forged"}"#.utf8)))
        let worker = DispatchQueue(label: "test.state.large")
        let queue = SyncGuestServiceQueue(worker: worker) { method, args, token in try services.handle(method: method, arguments: args, cancellation: token) }
        defer { queue.close() }
        let value = String(repeating: "a", count: 16000)
        let raw = try JSONSerialization.data(withJSONObject: ["t": "service.request", "version": 1, "id": 1, "method": "sync.state.set", "args": ["key": "large", "value": value]])
        XCTAssertTrue(try queue.submit(raw)); worker.sync {}
        XCTAssertEqual(try object(XCTUnwrap(queue.nextReply()).json)["ok"] as? Bool, true)
        store.close()
        let reopenedStore = try FileSyncSnapshotStore(privateRoot: root); defer { reopenedStore.close() }
        let reopened = SyncStateServices(state: try SyncState(appID: "app", deviceID: "watch", store: reopenedStore))
        let restored = try object(reopened.handle(method: "sync.state.get", arguments: Data(#"{"key":"large"}"#.utf8)))
        XCTAssertEqual((restored["entry"] as? [String: Any])?["value"] as? String, value)
        XCTAssertEqual(try object(reopened.handle(method: "sync.state.get", arguments: key))["exists"] as? Bool, false)
    }
}
