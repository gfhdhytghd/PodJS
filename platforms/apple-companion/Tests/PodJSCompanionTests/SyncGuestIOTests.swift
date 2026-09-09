import Foundation
import XCTest
import CPodJSSync

final class SyncGuestIOTests: XCTestCase {
    func testNativeGateInteropBusyReleaseAndNullFailures() throws {
        XCTAssertNil(pod_guest_io_open(nil)); XCTAssertEqual(pod_guest_io_try_enter(nil),-1)
        pod_guest_io_leave(nil); pod_guest_io_close(nil)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-guest-io-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try XCTUnwrap(root.path.withCString { pod_guest_io_open($0) })
        let second = try XCTUnwrap(root.path.withCString { pod_guest_io_open($0) }); defer { pod_guest_io_close(second) }
        XCTAssertEqual(pod_guest_io_try_enter(first),1)
        XCTAssertEqual(pod_guest_io_try_enter(second),0)
        XCTAssertEqual(pod_guest_io_try_enter(first),-1)
        pod_guest_io_leave(first); XCTAssertEqual(pod_guest_io_try_enter(second),1)
        pod_guest_io_leave(second); XCTAssertEqual(pod_guest_io_try_enter(first),1)
        pod_guest_io_close(first); XCTAssertEqual(pod_guest_io_try_enter(second),1)
        pod_guest_io_leave(second)
    }
}
