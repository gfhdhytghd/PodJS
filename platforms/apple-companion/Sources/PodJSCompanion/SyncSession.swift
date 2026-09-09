import Foundation
import CPodJSSync

public enum SyncChannel: String, Codable, Sendable, CaseIterable {
    case state, message, file, ack
}

/// Challenges must be fresh OS-random 32-byte values for each connection.
public struct SyncBinding: Encodable, Sendable {
    public let version: UInt32
    public let appID: String
    public let initiator: String
    public let responder: String
    public let initiatorNonce: [UInt8]
    public let responderNonce: [UInt8]
    public init(appID: String, initiator: String, responder: String,
                initiatorNonce: [UInt8], responderNonce: [UInt8], version: UInt32 = 1) {
        self.version = version; self.appID = appID; self.initiator = initiator; self.responder = responder
        self.initiatorNonce = initiatorNonce; self.responderNonce = responderNonce
    }
    enum CodingKeys: String, CodingKey {
        case version, initiator, responder
        case appID = "app_id", initiatorNonce = "initiator_nonce", responderNonce = "responder_nonce"
    }
}

public enum SyncSessionError: Error, Equatable, Sendable {
    case runtimeABI, invalidConfiguration, closed, oversizedCommand, malformedReply
    case rejected(String)
}
public struct SyncDelivery: Decodable, Equatable, Sendable {
    public enum Kind: String, Decodable, Sendable { case pending, duplicate }
    public let delivery: Kind
    public let acknowledged: UInt64
}

/// Host-only borrowed-pairing-key session. This authenticates bytes, not their
/// confidentiality: an encrypted transport and explicit pairing remain required.
/// NSLock serializes every access/close and response bytes are copied before
/// unlocking. The unchecked conformance does not expose the native pointer.
public final class SyncSession: @unchecked Sendable {
    public let appID: String
    public let localID: String
    public let peerID: String
    public let allowedChannels: Set<SyncChannel>
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private static let commandLimit = 2 * 1024 * 1024

    public init(pairingKey: [UInt8], binding: SyncBinding, localIsInitiator: Bool,
                allowedChannels: Set<SyncChannel>) throws {
        self.appID = binding.appID
        self.localID = localIsInitiator ? binding.initiator : binding.responder
        self.peerID = localIsInitiator ? binding.responder : binding.initiator
        self.allowedChannels = allowedChannels
        guard pod_runtime_abi_version() == 2 else { throw SyncSessionError.runtimeABI }
        let config = Configuration(key: pairingKey, binding: binding, local_is_initiator: localIsInitiator,
                                   allowed_channels: allowedChannels.sorted { $0.rawValue < $1.rawValue })
        var bytes = try JSONEncoder().encode(config)
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        handle = bytes.withUnsafeBytes { buffer in
            pod_sync_session_open(buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
        }
        guard handle != nil else { throw SyncSessionError.invalidConfiguration }
    }
    deinit { close() }
    public func close() {
        lock.lock(); defer { lock.unlock() }
        if let value = handle { handle = nil; pod_sync_session_close(value) }
    }
    public func proof() throws -> [UInt8] {
        let value: ProofReply = try request(Method(method: "proof")); return value.proof
    }
    /// The opposite role's proof is mandatory; a bad proof poisons this session.
    public func authenticate(remoteProof: [UInt8]) throws -> String {
        let reply: AuthenticationReply = try request(Authentication(method: "authenticate", proof: remoteProof))
        guard reply.authenticated else { throw SyncSessionError.malformedReply }; return reply.sessionId
    }
    /// Retain the returned exact JSON frame for retransmission until acknowledged.
    public func send(channel: SyncChannel, messageID: String, payload: [UInt8]) throws -> Data {
        let bytes = try perform(JSONEncoder().encode(Send(method: "send", channel: channel, message_id: messageID, payload: payload)))
        let object = try JSONSerialization.jsonObject(with: bytes)
        guard let reply = object as? [String: Any], let value = reply["value"] as? [String: Any],
              let frame = value["frame"] as? [String: Any] else {
            let _: EmptyReply = try decode(bytes); throw SyncSessionError.malformedReply
        }
        return try JSONSerialization.data(withJSONObject: frame, options: [.sortedKeys])
    }
    /// Forward the original frame JSON unchanged to Rust's strict decoder.
    /// Unknown fields, invalid tags, unauthorized channels and sequence gaps fail
    /// closed. No application delivery is acknowledged by this method alone.
    public func verify(frameJSON: Data) throws -> SyncDelivery {
        guard frameJSON.count <= Self.commandLimit - 40 else { throw SyncSessionError.oversizedCommand }
        var bytes = Data("{\"method\":\"verify\",\"frame\":".utf8)
        bytes.append(frameJSON); bytes.append(contentsOf: "}".utf8)
        return try decode(perform(bytes))
    }
    /// Call only after the received application effect has durably committed.
    public func commit(sequence: UInt64) throws -> UInt64 {
        let reply: CommitReply = try request(Commit(method: "commit", sequence: sequence)); return reply.acknowledged
    }
    private func request<Q: Encodable, R: Decodable>(_ value: Q) throws -> R {
        try decode(perform(JSONEncoder().encode(value)))
    }
    private func perform(_ bytes: Data) throws -> Data {
        guard !bytes.isEmpty && bytes.count <= Self.commandLimit else { throw SyncSessionError.oversizedCommand }
        lock.lock(); defer { lock.unlock() }
        guard let handle else { throw SyncSessionError.closed }
        return try bytes.withUnsafeBytes { buffer in
            guard let response = pod_sync_session_command(handle, buffer.bindMemory(to: UInt8.self).baseAddress, buffer.count)
            else { throw SyncSessionError.malformedReply }
            return Data(String(cString: response).utf8)
        }
    }
    private func decode<R: Decodable>(_ bytes: Data) throws -> R {
        let envelope = try JSONDecoder().decode(Reply<R>.self, from: bytes)
        guard envelope.ok else { throw SyncSessionError.rejected(envelope.code ?? "sync_session_error") }
        guard let value = envelope.value else { throw SyncSessionError.malformedReply }; return value
    }
}
private struct Configuration: Encodable {
    let key: [UInt8]; let binding: SyncBinding; let local_is_initiator: Bool; let allowed_channels: [SyncChannel]
}
private struct Method: Encodable { let method: String }
private struct Authentication: Encodable { let method: String; let proof: [UInt8] }
private struct Send: Encodable { let method: String; let channel: SyncChannel; let message_id: String; let payload: [UInt8] }
private struct Commit: Encodable { let method: String; let sequence: UInt64 }
private struct Reply<Value: Decodable>: Decodable { let ok: Bool; let value: Value?; let code: String? }
private struct ProofReply: Decodable { let proof: [UInt8] }
private struct AuthenticationReply: Decodable { let authenticated: Bool; let sessionId: String }
private struct CommitReply: Decodable { let acknowledged: UInt64 }
private struct EmptyReply: Decodable {}
