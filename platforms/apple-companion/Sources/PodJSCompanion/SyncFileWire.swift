import Foundation
import CPodJSSync

public enum SyncFileWireError: Error, Equatable, Sendable { case request, reply }
public struct SyncFileRequest: Sendable {
    public enum Method: String, Codable, Sendable { case offer, status, missing, chunk, finish, cancel }
    /// Retain exact original bytes for replay/digest binding, not canonical JSON.
    public let originalJSON: Data
    private let fields: FileRequestFields
    public var method: Method { fields.method }
    public var transferID: String { fields.manifest?.transferID ?? fields.transfer_id! }
    public var manifest: SyncFileManifest? { fields.manifest }
    public var index: Int? { fields.index }
    public var chunk: Data? { fields.data_base64.flatMap { Data(base64Encoded: $0) } }
    public static func decode(_ bytes: Data) throws -> SyncFileRequest {
        guard !bytes.isEmpty, bytes.count <= 98304 else { throw SyncFileWireError.request }
        let canonical: Data = try bytes.withUnsafeBytes { input in
            guard let raw = pod_sync_file_request_validate(input.bindMemory(to: UInt8.self).baseAddress, input.count) else { throw SyncFileWireError.request }
            defer { pod_sync_file_wire_free(raw) }; return Data(String(cString: raw).utf8)
        }
        return SyncFileRequest(originalJSON: bytes, fields: try JSONDecoder().decode(FileRequestFields.self, from: canonical))
    }
    public static func offer(_ manifest: SyncFileManifest) throws -> SyncFileRequest {
        try decode(JSONEncoder().encode(FileOfferWire(version: 1, method: .offer, manifest: manifest)))
    }
    public static func chunk(transferID: String, index: Int, bytes: Data) throws -> SyncFileRequest {
        guard bytes.count <= 65536 else { throw SyncFileWireError.request }
        return try decode(JSONEncoder().encode(FileChunkWire(version: 1, method: .chunk, transfer_id: transferID, index: index, data_base64: bytes.base64EncodedString())))
    }
    public static func operation(_ method: Method, transferID: String) throws -> SyncFileRequest {
        guard method != .offer, method != .chunk else { throw SyncFileWireError.request }
        return try decode(JSONEncoder().encode(FileIdentityWire(version: 1, method: method, transfer_id: transferID)))
    }
}
public enum SyncFileWirePhase: String, Codable, Sendable { case offered, accepting, accepted, complete, cancelling, cancelled }
public struct SyncFileReply: Sendable {
    public let originalJSON: Data
    public let phase: SyncFileWirePhase
    public let missing: [Int]?
    /// Caller must also bind authenticated peer and outer request ID to its
    /// durable pending request. Digest validation alone cannot grant that binding.
    public static func decode(_ bytes: Data, request: SyncFileRequest) throws -> SyncFileReply {
        guard !bytes.isEmpty, bytes.count <= 4096 else { throw SyncFileWireError.reply }
        let canonical: Data = try request.originalJSON.withUnsafeBytes { original in try bytes.withUnsafeBytes { input in
            guard let raw = pod_sync_file_reply_validate(original.bindMemory(to: UInt8.self).baseAddress, original.count,
                input.bindMemory(to: UInt8.self).baseAddress, input.count) else { throw SyncFileWireError.reply }
            defer { pod_sync_file_wire_free(raw) }; return Data(String(cString: raw).utf8)
        } }
        let fields = try JSONDecoder().decode(FileReplyFields.self, from: canonical)
        return SyncFileReply(originalJSON: bytes, phase: fields.value.phase, missing: fields.value.missing)
    }
    public static func make(request: SyncFileRequest, phase: SyncFileWirePhase, missing: [Int]? = nil) throws -> SyncFileReply {
        let digest = try messageDigest(request.originalJSON).map { String(format: "%02x", $0) }.joined()
        let bytes = try JSONEncoder().encode(FileReplyFields(version: 1, type: "reply", request_sha256: digest, value: FileValueFields(phase: phase, missing: missing)))
        return try decode(bytes, request: request)
    }
}
private struct FileRequestFields: Decodable, Sendable {
    let method: SyncFileRequest.Method; let manifest: SyncFileManifest?; let transfer_id: String?; let index: Int?; let data_base64: String?
}
private struct FileOfferWire: Encodable { let version: Int; let method: SyncFileRequest.Method; let manifest: SyncFileManifest }
private struct FileChunkWire: Encodable { let version: Int; let method: SyncFileRequest.Method; let transfer_id: String; let index: Int; let data_base64: String }
private struct FileIdentityWire: Encodable { let version: Int; let method: SyncFileRequest.Method; let transfer_id: String }
private struct FileReplyFields: Codable { let version: Int; let type: String; let request_sha256: String; let value: FileValueFields }
private struct FileValueFields: Codable { let phase: SyncFileWirePhase; let missing: [Int]? }
