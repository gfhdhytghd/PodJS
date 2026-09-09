import Foundation

public enum SyncFilePumpError: Error, Equatable, Sendable { case identity, grants, closed, channel }
public struct SyncFilePumpResult: Sendable {
    public enum Status: Sendable { case request, reply, duplicateReply }
    public let status: Status
    public let reply: Data?
}
/// Exclusive file-channel dispatcher. Host supplies authenticated, reliable,
/// encrypted IO and serializes complete frame writes. No local consent, source
/// import, automatic polling or OS file export is granted by this dispatcher.
/// Unknown/consumed replies close the session; retain completed observations
/// until queued transport frames have drained. Request IDs must never be reused.
public final class SyncFilePump: @unchecked Sendable {
    private let lock = NSLock()
    private let session: SyncSession
    private let requests: SyncFileRequests
    private let incoming: SyncIncomingFiles
    private var closed = false
    private var outgoing: (id: String, bytes: Data, frame: Data)?
    private var lastReply: (id: String, bytes: Data, frame: Data)?
    public convenience init(session: SyncSession, requests: SyncFileRequests, incoming: SyncIncomingFiles) throws {
        try self.init(session: session, requests: requests, incoming: incoming, shared: false)
    }
    init(session: SyncSession, requests: SyncFileRequests, incoming: SyncIncomingFiles, shared: Bool) throws {
        guard requests.matchesIdentity(app: session.appID, device: session.localID),
              incoming.matchesIdentity(app: session.appID, device: session.localID) else { throw SyncFilePumpError.identity }
        let channels: Set<SyncChannel> = shared ? [.state, .message, .ack, .file] : [.file]
        guard session.allowedChannels == channels else { throw SyncFilePumpError.grants }
        self.session = session; self.requests = requests; self.incoming = incoming
    }
    deinit { close() }
    public func close() { lock.lock(); defer { lock.unlock() }; stop() }
    private func stop() { closed = true; outgoing = nil; lastReply = nil; session.close() }
    private func perform<T>(_ operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncFilePumpError.closed }
        do { return try operation() } catch { stop(); throw error }
    }
    public func sendNext() throws -> Data? {
        try perform {
            guard let next = try requests.next(peer: session.peerID) else { outgoing = nil; return nil }
            if let outgoing, outgoing.id == next.messageID, outgoing.bytes == next.request.originalJSON { return outgoing.frame }
            let frame = try session.send(channel: .file, messageID: next.messageID, payload: Array(next.request.originalJSON))
            outgoing = (next.messageID, next.request.originalJSON, frame); return frame
        }
    }
    public func receive(frameJSON: Data) throws -> SyncFilePumpResult {
        try perform {
            let verified = try session.verify(frameJSON: frameJSON)
            let frame = try JSONDecoder().decode(FilePumpFrame.self, from: frameJSON)
            guard frame.channel == .file else { throw SyncFilePumpError.channel }
            let bytes = Data(frame.payload)
            // Strict Rust request validation determines the request branch.
            // Everything else must pass strict reply validation against the
            // exact durable request, including authenticated peer and outer ID.
            if let request = try? SyncFileRequest.decode(bytes) {
                let reply = try incoming.receiveAuthenticatedRequest(peer: session.peerID, requestID: frame.messageId,
                    request: request, duplicateFrame: verified.delivery == .duplicate)
                if verified.delivery != .duplicate { _ = try session.commit(sequence: frame.sequence) }
                if let lastReply, lastReply.id == frame.messageId, lastReply.bytes == reply.originalJSON {
                    return SyncFilePumpResult(status: .request, reply: lastReply.frame)
                }
                let signed = try session.send(channel: .file, messageID: frame.messageId, payload: Array(reply.originalJSON))
                lastReply = (frame.messageId, reply.originalJSON, signed)
                return SyncFilePumpResult(status: .request, reply: signed)
            }
            let result = try requests.receiveAuthenticated(peer: session.peerID, messageID: frame.messageId, replyBytes: bytes)
            if verified.delivery != .duplicate { _ = try session.commit(sequence: frame.sequence) }
            if outgoing?.id == frame.messageId { outgoing = nil }
            return SyncFilePumpResult(status: result == .received ? .reply : .duplicateReply, reply: nil)
        }
    }
}
private struct FilePumpFrame: Decodable { let sequence: UInt64; let channel: SyncChannel; let messageId: String; let payload: [UInt8] }
