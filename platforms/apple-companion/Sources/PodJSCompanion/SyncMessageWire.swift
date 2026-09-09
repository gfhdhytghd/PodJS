import Foundation

public enum SyncMessageError: Error, Equatable, Sendable { case invalidEnvelope, invalidAck }
/// Android/Harmony-compatible wire envelope. Expiry is an absolute millisecond
/// timestamp; the payload is opaque. Authentication/identity are outer-frame
/// responsibilities, and this type does not imply delivery or durable receipt.
public struct SyncMessageEnvelope: Equatable, Sendable {
    public let expiresAt: UInt64
    public let highPriority: Bool
    public let payload: Data
    public init(expiresAt: UInt64, highPriority: Bool, payload: Data) throws {
        guard expiresAt <= 9_007_199_254_740_991, payload.count <= 262144 else { throw SyncMessageError.invalidEnvelope }
        self.expiresAt = expiresAt; self.highPriority = highPriority; self.payload = payload
    }
    public func encoded() -> Data {
        var bytes = Data()
        for shift in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: expiresAt >> shift)) }
        bytes.append(highPriority ? 1 : 0); bytes.append(payload); return bytes
    }
    public static func decode(_ data: Data) throws -> SyncMessageEnvelope {
        guard (9...262153).contains(data.count) else { throw SyncMessageError.invalidEnvelope }
        let bytes = Array(data)
        guard bytes[8] <= 1 else { throw SyncMessageError.invalidEnvelope }
        var expires: UInt64 = 0
        for value in bytes.prefix(8) { expires = expires << 8 | UInt64(value) }
        return try SyncMessageEnvelope(expiresAt: expires, highPriority: bytes[8] == 1, payload: Data(bytes.dropFirst(9)))
    }
}
/// Kind 1 means accepted durably, kind 2 means expired. Neither is a transport
/// acknowledgement; peer/message ID and SHA-256(envelope) must match pending IO.
public struct SyncMessageAcknowledgement: Equatable, Sendable {
    public let expired: Bool
    public let digest: Data
    public init(expired: Bool, digest: Data) throws {
        guard digest.count == 32 else { throw SyncMessageError.invalidAck }
        self.expired = expired; self.digest = digest
    }
    public func encoded() -> Data { Data([expired ? 2 : 1]) + digest }
    public static func decode(_ data: Data) throws -> SyncMessageAcknowledgement {
        guard data.count == 33 else { throw SyncMessageError.invalidAck }
        let bytes = Array(data)
        guard bytes[0] == 1 || bytes[0] == 2 else { throw SyncMessageError.invalidAck }
        return try SyncMessageAcknowledgement(expired: bytes[0] == 2, digest: Data(bytes.dropFirst()))
    }
}
