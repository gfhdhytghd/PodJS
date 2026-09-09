import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncMessageWireTests: XCTestCase {
    func testMessageGoldenBytesAndSlicedData() throws {
        let envelope = try SyncMessageEnvelope(expiresAt: 0x0102030405, highPriority: true, payload: Data([0,255,128]))
        let wire = Data([0,0,0,1,2,3,4,5,1,0,255,128])
        XCTAssertEqual(envelope.encoded(), wire)
        XCTAssertEqual(try SyncMessageEnvelope.decode(wire), envelope)
        let padded = Data([99]) + wire
        XCTAssertEqual(try SyncMessageEnvelope.decode(padded.dropFirst()), envelope)
        let maximum = try SyncMessageEnvelope(expiresAt: 9_007_199_254_740_991, highPriority: false, payload: Data(repeating: 255, count: 262144))
        XCTAssertEqual(try SyncMessageEnvelope.decode(maximum.encoded()), maximum)
    }
    func testEnvelopeRejectsUnsafeExpiryPriorityAndBounds() throws {
        XCTAssertThrowsError(try SyncMessageEnvelope(expiresAt: UInt64.max, highPriority: false, payload: Data()))
        XCTAssertThrowsError(try SyncMessageEnvelope(expiresAt: 1, highPriority: false, payload: Data(repeating: 0, count: 262145)))
        XCTAssertThrowsError(try SyncMessageEnvelope.decode(Data(repeating: 0, count: 8)))
        XCTAssertThrowsError(try SyncMessageEnvelope.decode(Data([0,0,0,0,0,0,0,0,2])))
        XCTAssertThrowsError(try SyncMessageEnvelope.decode(Data(repeating: 255, count: 8) + Data([0])))
    }
    func testAcknowledgementKindsRemainDistinctFromStateAck() throws {
        for expired in [false, true] {
            let value = try SyncMessageAcknowledgement(expired: expired, digest: Data(0..<32))
            XCTAssertEqual(value.encoded(), Data([expired ? 2 : 1]) + Data(0..<32))
            XCTAssertEqual(try SyncMessageAcknowledgement.decode(value.encoded()), value)
        }
        XCTAssertThrowsError(try SyncMessageAcknowledgement.decode(Data([3]) + Data(repeating: 0, count: 32)))
        XCTAssertThrowsError(try SyncMessageAcknowledgement.decode(Data(repeating: 1, count: 32)))
        XCTAssertThrowsError(try SyncMessageAcknowledgement(expired: false, digest: Data()))
    }
    func testMessageSizedJournalPreservesDataWhenOpenedWithSmallerBound() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("podjs-message-bound-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("messages"), bytes = Data(repeating: 123, count: 5 * 1024 * 1024)
        let larger = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024)
        XCTAssertTrue(try larger.compareExchange(expected: nil, desired: bytes)); larger.close()
        let smaller = try FileSyncSnapshotStore(privateRoot: root)
        XCTAssertThrowsError(try smaller.read()); XCTAssertThrowsError(try smaller.compareExchange(expected: nil, desired: Data()))
        smaller.close()
        let reopened = try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024)
        defer { reopened.close() }; XCTAssertEqual(try reopened.read(), bytes)
        XCTAssertThrowsError(try FileSyncSnapshotStore(privateRoot: root, maximumBytes: 18 * 1024 * 1024 + 1))
    }
}
