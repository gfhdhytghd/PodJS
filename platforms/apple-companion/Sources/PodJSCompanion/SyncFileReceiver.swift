import Foundation
import CPodJSSync

public struct SyncFileManifest: Codable, Equatable, Sendable {
    public let transferID: String
    public let size: UInt64
    public let sha256: String
    public let chunkHashes: [String]
    public let mime: String
    public init(transferID: String, size: UInt64, sha256: String, chunkHashes: [String], mime: String = "") {
        self.transferID = transferID; self.size = size; self.sha256 = sha256
        self.chunkHashes = chunkHashes; self.mime = mime
    }
    enum CodingKeys: String, CodingKey {
        case size, sha256, mime
        case transferID = "transfer_id", chunkHashes = "chunk_hashes"
    }
}
public enum SyncFileReceiverError: Error, Equatable, Sendable {
    case runtimeABI, invalidRoot, unavailableOrOwned, closed, oversizedChunk, oversizedCommand, malformedReply
    case rejected(String)
}

/// Low-level host-private receiver, not the guest consent/transfer registry.
/// The host authenticates/authorizes its peer before every call and supplies an
/// OS-private root with an existing trusted parent (never a guest/network path).
/// Run synchronous file IO on a worker. The lifetime OS lease excludes another
/// cooperating instance/process, including during native open-time recovery.
public final class SyncFileReceiver: @unchecked Sendable {
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private var lease: Int32 = -1

    public init(privateRoot: URL) throws {
        guard pod_runtime_abi_version() == 2 else { throw SyncFileReceiverError.runtimeABI }
        let root = privateRoot.standardizedFileURL
        guard privateRoot.isFileURL, root.path != "/", !root.path.utf8.contains(0),
              !root.lastPathComponent.isEmpty else { throw SyncFileReceiverError.invalidRoot }
        let lockURL = root.deletingLastPathComponent().appendingPathComponent(".podjs-sync-" + root.lastPathComponent + ".lock")
        lease = lockURL.path.withCString { pod_apple_sync_file_lease_open($0) }
        guard lease >= 0 else { throw SyncFileReceiverError.unavailableOrOwned }
        handle = root.path.withCString { pod_sync_files_open($0) }
        guard handle != nil else {
            pod_apple_sync_file_lease_close(lease); lease = -1
            throw SyncFileReceiverError.invalidRoot
        }
    }
    deinit { close() }
    public func close() {
        lock.lock(); defer { lock.unlock() }
        if let value = handle { handle = nil; pod_sync_files_close(value) }
        if lease >= 0 { pod_apple_sync_file_lease_close(lease); lease = -1 }
    }
    /// Call only after the host's explicit acceptance policy allows allocation.
    public func prepareAccepted(_ manifest: SyncFileManifest) throws {
        let reply: Offered = try request(Offer(method: "offer", manifest: manifest))
        guard reply.transferId == manifest.transferID else { throw SyncFileReceiverError.malformedReply }
    }
    public func missing(transferID: String) throws -> [Int] {
        let reply: Missing = try request(Identity(method: "missing", transfer_id: transferID)); return reply.missing
    }
    func verifiedComplete(transferID: String) throws -> Bool {
        let reply: VerifiedComplete = try request(Identity(method: "verified_complete", transfer_id: transferID)); return reply.complete
    }
    public func writeChunk(transferID: String, index: Int, bytes: Data) throws {
        guard bytes.count <= 65_536 else { throw SyncFileReceiverError.oversizedChunk }
        let reply: Stored = try request(Chunk(method: "chunk", transfer_id: transferID, index: index, data_base64: bytes.base64EncodedString()))
        guard reply.stored, reply.index == index else { throw SyncFileReceiverError.malformedReply }
    }
    /// Returns a host-only verified private artifact, not a guest-visible path.
    /// Explicit safe publication to guest storage is a separate, unfinished API.
    public func finish(transferID: String) throws -> URL {
        let reply: Finished = try request(Identity(method: "finish", transfer_id: transferID))
        guard reply.state == "complete" else { throw SyncFileReceiverError.malformedReply }
        return URL(fileURLWithPath: reply.path)
    }
    /// Host-only read; native code verifies the whole file and selected chunk
    /// through one descriptor under this receiver's lease before returning data.
    /// Not exposed as a remote file wire method or a guest storage path.
    public func readCompleteChunk(transferID: String, index: Int) throws -> Data {
        let reply: ReadChunkReply = try request(ReadChunk(method: "read_complete_chunk", transfer_id: transferID, index: index))
        guard reply.index == index, reply.data_base64.utf8.count <= 87384,
              let bytes = Data(base64Encoded: reply.data_base64), bytes.count <= 65536,
              bytes.base64EncodedString() == reply.data_base64 else { throw SyncFileReceiverError.malformedReply }
        return bytes
    }
    /// Explicitly removes this host copy, INCLUDING a completed artifact. A
    /// higher-level guest cancel must not blindly map to this cleanup primitive.
    public func removeHostCopy(transferID: String) throws {
        let reply: Removed = try request(Identity(method: "cancel", transfer_id: transferID))
        guard reply.state == "cancelled" else { throw SyncFileReceiverError.malformedReply }
    }
    private func request<Q: Encodable, R: Decodable>(_ value: Q) throws -> R {
        let bytes = try JSONEncoder().encode(value)
        guard bytes.count <= 96 * 1024 else { throw SyncFileReceiverError.oversizedCommand }
        lock.lock(); defer { lock.unlock() }
        guard let handle else { throw SyncFileReceiverError.closed }
        let response: Data = try bytes.withUnsafeBytes { buffer in
            guard let raw = pod_sync_files_command(handle, buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
            else { throw SyncFileReceiverError.malformedReply }
            return Data(String(cString: raw).utf8)
        }
        let reply = try JSONDecoder().decode(FileReply<R>.self, from: response)
        guard reply.ok else { throw SyncFileReceiverError.rejected(reply.code ?? "file_transfer_error") }
        guard let value = reply.value else { throw SyncFileReceiverError.malformedReply }; return value
    }
}
private struct FileReply<Value: Decodable>: Decodable { let ok: Bool; let value: Value?; let code: String? }
private struct Offer: Encodable { let method: String; let manifest: SyncFileManifest }
private struct Identity: Encodable { let method: String; let transfer_id: String }
private struct Chunk: Encodable { let method: String; let transfer_id: String; let index: Int; let data_base64: String }
private struct Offered: Decodable { let transferId: String }
private struct Missing: Decodable { let missing: [Int] }
private struct VerifiedComplete: Decodable { let complete: Bool }
private struct Stored: Decodable { let index: Int; let stored: Bool }
private struct Finished: Decodable { let path: String; let state: String }
private struct Removed: Decodable { let state: String }
private struct ReadChunk: Encodable { let method: String; let transfer_id: String; let index: Int }
private struct ReadChunkReply: Decodable { let index: Int; let data_base64: String }
