import Foundation
import XCTest
import CPodJSSync
import CPodJSRuntimeTestSupport
@testable import PodJSCompanion

final class SyncGuestRuntimePumpTests: XCTestCase {
    private func hash(_ text: String) -> String {
        let value = text.utf8.reduce(UInt64(0xcbf29ce484222325)) { ($0 ^ UInt64($1)) &* 0x100000001b3 }
        return String(format: "%016llx", value)
    }
    func testActualGuestOfferConsentAndSaveIntoRuntimeFiles() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("files"), withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        let source = #"""
        let accepted = false, saved = false;
        globalThis.frame = function() {
            for (const event of JSON.parse(pod.takeEvents() || '[]')) {
                if (event.t === 'sync.file.changed' && event.value.state === 'offered' && !accepted) {
                    accepted = true;
                    pod.emit(JSON.stringify({t:'service.request',version:1,id:1,method:'sync.files.accept',args:{transferId:event.value.transferId}}));
                }
                if (event.t === 'sync.file.changed' && event.value.state === 'complete' && !saved) {
                    saved = true;
                    pod.emit(JSON.stringify({t:'service.request',version:1,id:2,method:'sync.files.save',args:{transferId:event.value.transferId,path:'result'}}));
                }
                if (event.t === 'service.result') pod.emit(JSON.stringify({t:'observed',reply:event}));
            }
        };
        """#
        let manifest = try JSONSerialization.data(withJSONObject: [
            "target": "watchos-watch", "hostAbi": 2,
            "pocketjsRevision": "0a90bf904d835210e52a11ed275a86d0040b5086",
            "pakHash": hash("pak"), "bundleHash": hash(source), "capabilities": []
        ] as [String: Any])
        let pointer = try XCTUnwrap(root.path.withCString { path in source.withCString { source in
            String(decoding: manifest, as: UTF8.self).withCString { pod_test_runtime_open_at(source, $0, path) }
        } })
        defer { pod_test_runtime_close(pointer) }
        let runtime = OpaquePointer(pointer)
        let files = try SyncIncomingFiles(appID: "app", deviceID: "watch", privateRoot: root.appendingPathComponent("incoming")); defer { files.close() }
        let storage = try SyncGuestFiles(appID: "app", runtimeDataRoot: root); defer { storage.close() }
        let frameGate = try XCTUnwrap(root.path.withCString { pod_guest_io_open($0) }); defer { pod_guest_io_close(frameGate) }
        let services = try SyncIncomingFileServices(files: files, storage: storage)
        XCTAssertThrowsError(try SyncGuestRuntimePump(runtime: runtime, incomingFiles: services)) {
            XCTAssertEqual($0 as? SyncIncomingFileServiceError, .unsupported)
        }
        let worker = DispatchQueue(label: "test.actual.file-services")
        let queue = SyncGuestServiceQueue(worker: worker) { method, args, token in
            try services.handle(method: method, arguments: args, cancellation: token)
        }
        let pump = SyncGuestRuntimePump(runtime: runtime, queue: queue, incomingFiles: services); defer { pump.close() }
        let digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        _ = try files.offerAuthenticated(peer: "phone", manifest: SyncFileManifest(transferID: "one", size: 3, sha256: digest, chunkHashes: [digest]))
        var observed: [[String: Any]] = []
        func drive() throws {
            for _ in 0..<5 {
                try pump.pump { if let item = try? JSONSerialization.jsonObject(with: $0) as? [String: Any] { observed.append(item) } }
                worker.sync {}
                XCTAssertEqual(pod_guest_io_try_enter(frameGate), 1)
                let result = pod_test_runtime_frame(pointer)
                pod_guest_io_leave(frameGate)
                XCTAssertEqual(result, 0)
            }
        }
        pump.setFileEventsActive(true)
        try drive()
        XCTAssertEqual(try files.statusLocal(peer: "phone", transferID: "one").state, .transferring)
        try files.writeAuthenticated(peer: "phone", transferID: "one", index: 0, bytes: Data("abc".utf8))
        let retained = try files.finishAuthenticated(peer: "phone", transferID: "one")
        try drive()
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("files/result")), Data("abc".utf8))
        XCTAssertEqual(try Data(contentsOf: retained), Data("abc".utf8))
        let replies = observed.compactMap { $0["reply"] as? [String: Any] }
        XCTAssertEqual(replies.count, 2)
        XCTAssertTrue(replies.allSatisfy { $0["ok"] as? Bool == true })
        let savedReply = try XCTUnwrap(replies.first { $0["id"] as? Int == 2 })
        XCTAssertEqual((savedReply["value"] as? [String: Any])?["path"] as? String, "result")
    }
    func testActualQuickJSRequestReplyAndNativeBackpressure() throws {
        let source = #"""
        pod.emit(JSON.stringify({t:'service.request',version:1,id:1,method:'sync.files.status',args:{transferId:'one'}}));
        globalThis.frame = function() {
            for (const event of JSON.parse(pod.takeEvents() || '[]')) {
                if (event.t === 'service.result') pod.emit(JSON.stringify({t:'observed',reply:event}));
            }
        };
        """#
        let manifest = try JSONSerialization.data(withJSONObject: [
            "target": "watchos-watch", "hostAbi": 2,
            "pocketjsRevision": "0a90bf904d835210e52a11ed275a86d0040b5086",
            "pakHash": hash("pak"), "bundleHash": hash(source), "capabilities": []
        ] as [String: Any])
        let pointer = try XCTUnwrap(source.withCString { source in String(decoding: manifest, as: UTF8.self).withCString { pod_test_runtime_open(source, $0) } })
        defer { pod_test_runtime_close(pointer) }
        let runtime = OpaquePointer(pointer)
        let worker = DispatchQueue(label: "test.actual.runtime")
        let queue = SyncGuestServiceQueue(worker: worker) { method, args, _ in
            XCTAssertEqual(method, "sync.files.status")
            XCTAssertEqual((try JSONSerialization.jsonObject(with: args) as? [String: Any])?["transferId"] as? String, "one")
            return Data(#"{"transferId":"one","state":"offered","receivedBytes":0,"totalBytes":3}"#.utf8)
        }
        let pump = SyncGuestRuntimePump(runtime: runtime, queue: queue)
        defer { pump.close() }
        for _ in 0..<256 { XCTAssertEqual("{\"t\":\"fill\"}".withCString { pod_runtime_post_event(runtime, $0) }, 0) }
        try pump.pump(); worker.sync {}
        let pending = try XCTUnwrap(queue.nextReply())
        try pump.pump(); XCTAssertEqual(queue.nextReply(), pending)
        XCTAssertEqual(pod_test_runtime_frame(pointer), 0)
        try pump.pump(); XCTAssertNil(queue.nextReply())
        XCTAssertEqual(pod_test_runtime_frame(pointer), 0)
        var observed: [Data] = []
        try pump.pump { observed.append($0) }
        XCTAssertEqual(observed.count, 1)
        let object = try JSONSerialization.jsonObject(with: XCTUnwrap(observed.first)) as? [String: Any]
        XCTAssertEqual(object?["t"] as? String, "observed")
        let reply = object?["reply"] as? [String: Any]
        XCTAssertEqual(reply?["ok"] as? Bool, true)
        XCTAssertEqual(reply?["id"] as? Int, 1)
        XCTAssertEqual((reply?["value"] as? [String: Any])?["state"] as? String, "offered")
        pump.close(); try pump.pump { _ in XCTFail("Closed pump read a runtime effect") }
    }
}
