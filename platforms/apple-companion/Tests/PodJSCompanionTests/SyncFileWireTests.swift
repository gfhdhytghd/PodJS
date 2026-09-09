import Foundation
import XCTest
@testable import PodJSCompanion

final class SyncFileWireTests: XCTestCase {
    func testRequestMethodsAndCanonicalChunkBounds() throws {
        let hash = String(repeating: "a", count: 64)
        let offer = try SyncFileRequest.offer(SyncFileManifest(transferID: "one", size: 3, sha256: hash, chunkHashes: [hash]))
        XCTAssertEqual(offer.method,.offer); XCTAssertEqual(offer.transferID,"one")
        let chunk = try SyncFileRequest.chunk(transferID: "one", index: 255, bytes: Data([0,255,128]))
        XCTAssertEqual(try SyncFileRequest.decode(chunk.originalJSON).chunk,Data([0,255,128]))
        XCTAssertThrowsError(try SyncFileRequest.chunk(transferID: "one", index: 256, bytes: Data()))
        XCTAssertThrowsError(try SyncFileRequest.decode(Data("{\"version\":1,\"method\":\"chunk\",\"transfer_id\":\"one\",\"index\":0,\"data_base64\":\"AB==\"}".utf8)))
        for method: SyncFileRequest.Method in [.status,.missing,.finish,.cancel] { XCTAssertEqual(try SyncFileRequest.operation(method, transferID: "one").method,method) }
        XCTAssertThrowsError(try SyncFileRequest.operation(.status, transferID: "../one"))
    }
    func testStrictUnknownDuplicateAndRemoteAuthorityFields() throws {
        let bad = [
            "{\"version\":1,\"method\":\"status\",\"transfer_id\":\"one\",\"path\":\"/tmp/x\"}",
            "{\"version\":1,\"method\":\"accept\",\"transfer_id\":\"one\"}",
            "{\"version\":1,\"method\":\"status\",\"transfer_id\":\"one\",\"transfer_id\":\"two\"}",
            "{\"version\":1,\"method\":\"status\",\"method\":\"cancel\",\"transfer_id\":\"one\"}",
            "{\"version\":1,\"method\":\"offer\",\"manifest\":{\"transfer_id\":\"one\",\"size\":0,\"size\":1,\"sha256\":\"" + String(repeating:"a",count:64) + "\",\"chunk_hashes\":[],\"mime\":\"\"}}"
        ]
        for json in bad { XCTAssertThrowsError(try SyncFileRequest.decode(Data(json.utf8))) }
    }
    func testReplyBindsExactRequestBytesAndMethodSpecificFields() throws {
        let original = Data("{ \"version\":1, \"method\":\"missing\", \"transfer_id\":\"one\" }".utf8)
        let request = try SyncFileRequest.decode(original); XCTAssertEqual(request.originalJSON,original)
        let reply = try SyncFileReply.make(request: request, phase: .accepted, missing: [0,2])
        XCTAssertEqual(try SyncFileReply.decode(reply.originalJSON, request: request).missing,[0,2])
        let equivalent = try SyncFileRequest.operation(.missing, transferID: "one")
        XCTAssertThrowsError(try SyncFileReply.decode(reply.originalJSON, request: equivalent))
        XCTAssertThrowsError(try SyncFileReply.make(request: request, phase: .accepted))
        XCTAssertThrowsError(try SyncFileReply.make(request: request, phase: .complete, missing: [0]))
        XCTAssertThrowsError(try SyncFileReply.make(request: request, phase: .accepted, missing: [2,1]))
        let cancel = try SyncFileRequest.operation(.cancel, transferID: "one")
        XCTAssertThrowsError(try SyncFileReply.make(request: cancel, phase: .complete))
        XCTAssertEqual(try SyncFileReply.make(request: cancel, phase: .cancelled).phase,.cancelled)
        let digest = try messageDigest(original).map { String(format:"%02x",$0) }.joined()
        let duplicate = Data("{\"version\":1,\"type\":\"reply\",\"request_sha256\":\"\(digest)\",\"value\":{\"phase\":\"accepted\",\"phase\":\"complete\",\"missing\":[]}}".utf8)
        XCTAssertThrowsError(try SyncFileReply.decode(duplicate, request: request))
    }
}
