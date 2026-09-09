import Foundation

public enum SyncStatePumpError: Error, Equatable, Sendable {
    case identityMismatch, channelGrants, closed, unexpectedChannel, invalidAck
}
public struct SyncStatePumpResult: Sendable {
    public enum Status: String, Sendable { case applied, duplicate, ack, staleAck }
    public let status: Status
    public let reply: Data?
}
/// Dedicated state/ACK owner for an authenticated session. Do not use the
/// session directly after giving it to this pump. Calls and close are serialized.
/// The host writes returned frames in order over an encrypted reliable transport
/// and closes on write failure; reconnect uses fresh handshake challenges.
/// No discovery, OS lifecycle, polling or subscription dispatch is provided here.
public final class SyncStatePump: @unchecked Sendable {
    private let lock = NSLock()
    private let session: SyncSession
    private let state: SyncState
    private var closed = false
    private var outgoing: (id: String, frame: Data)?
    private var lastReply: (sequence: UInt64, frame: Data)?
    public convenience init(session: SyncSession, state: SyncState) throws {
        try self.init(session: session, state: state, shared: false)
    }
    init(session: SyncSession, state: SyncState, shared: Bool) throws {
        guard state.matchesIdentity(app: session.appID, device: session.localID) else { throw SyncStatePumpError.identityMismatch }
        let channels: Set<SyncChannel> = shared ? [.state, .message, .ack] : [.state, .ack]
        guard session.allowedChannels == channels || (shared && session.allowedChannels == [.state, .message, .ack, .file]) else { throw SyncStatePumpError.channelGrants }
        self.session = session; self.state = state
    }
    deinit { close() }
    public func close() {
        lock.lock(); defer { lock.unlock() }; closeLocked()
    }
    private func closeLocked() { closed = true; outgoing = nil; lastReply = nil; session.close() }
    private func perform<T>(_ operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncStatePumpError.closed }
        do { return try operation() } catch { closeLocked(); throw error }
    }
    /// A repeated call while waiting for ACK returns the same signed frame,
    /// rather than consuming a new transport sequence for the same batch.
    public func sendNext() throws -> Data? {
        try perform {
            guard let batch = try state.prepare(peer: session.peerID) else { outgoing = nil; return nil }
            if let outgoing, outgoing.id == batch.messageId { return outgoing.frame }
            let frame = try session.send(channel: .state, messageID: batch.messageId, payload: Array(batch.payload.utf8))
            outgoing = (batch.messageId, frame); return frame
        }
    }
    public func receive(frameJSON: Data) throws -> SyncStatePumpResult {
        try perform {
            // Authenticate and strictly parse the original bytes before decoding
            // any fields for application routing. Never reserialize untrusted JSON.
            let delivery = try session.verify(frameJSON: frameJSON)
            let frame = try JSONDecoder().decode(StatePumpFrame.self, from: frameJSON)
            if frame.channel == .ack {
                let ack = try StateAck.decode(frame.payload)
                let matched = try state.acknowledgeAuthenticated(peer: session.peerID, messageID: frame.messageId, cursor: ack.cursor, digest: ack.digest)
                if delivery.delivery != .duplicate { _ = try session.commit(sequence: frame.sequence) }
                if matched { outgoing = nil }
                return SyncStatePumpResult(status: matched ? .ack : .staleAck, reply: nil)
            }
            guard frame.channel == .state else { throw SyncStatePumpError.unexpectedChannel }
            let receipt = try state.receiveAuthenticated(peer: session.peerID, messageID: frame.messageId, payloadJSON: Data(frame.payload))
            if delivery.delivery != .duplicate { _ = try session.commit(sequence: frame.sequence) }
            if delivery.delivery == .duplicate, let lastReply, lastReply.sequence == frame.sequence {
                return SyncStatePumpResult(status: .duplicate, reply: lastReply.frame)
            }
            let reply = try session.send(channel: .ack, messageID: frame.messageId, payload: StateAck.encode(cursor: receipt.cursor, digest: receipt.digest))
            lastReply = (frame.sequence, reply)
            return SyncStatePumpResult(status: receipt.duplicate ? .duplicate : .applied, reply: reply)
        }
    }
}
private struct StatePumpFrame: Decodable {
    let sequence: UInt64; let channel: SyncChannel; let messageId: String; let payload: [UInt8]
}
/// Cross-platform ACK kind 3: one tag byte, u64 big-endian cursor, SHA-256 bytes.
enum StateAck {
    static func encode(cursor: UInt64, digest: String) throws -> [UInt8] {
        guard cursor <= 9_007_199_254_740_991, digest.utf8.count == 64 else { throw SyncStatePumpError.invalidAck }
        let chars = Array(digest.utf8); var result: [UInt8] = [3]
        for shift in stride(from: 56, through: 0, by: -8) { result.append(UInt8(truncatingIfNeeded: cursor >> shift)) }
        func nibble(_ value: UInt8) throws -> UInt8 {
            if (48...57).contains(value) { return value - 48 }
            if (97...102).contains(value) { return value - 87 }
            throw SyncStatePumpError.invalidAck
        }
        for index in stride(from: 0, to: 64, by: 2) { result.append(try nibble(chars[index]) * 16 + nibble(chars[index + 1])) }
        return result
    }
    static func decode(_ bytes: [UInt8]) throws -> (cursor: UInt64, digest: String) {
        guard bytes.count == 41, bytes[0] == 3 else { throw SyncStatePumpError.invalidAck }
        var cursor: UInt64 = 0
        for value in bytes[1..<9] { cursor = cursor << 8 | UInt64(value) }
        guard cursor <= 9_007_199_254_740_991 else { throw SyncStatePumpError.invalidAck }
        let hex = Array("0123456789abcdef".utf8)
        let digest = String(decoding: bytes[9...].flatMap { [hex[Int($0 >> 4)], hex[Int($0 & 15)]] }, as: UTF8.self)
        return (cursor, digest)
    }
}
