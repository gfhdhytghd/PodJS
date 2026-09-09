import Foundation

public enum SyncMessagePumpError: Error, Equatable, Sendable { case identity, grants, closed, channel }
public struct SyncMessagePumpResult: Sendable {
    public enum Status: String, Sendable { case pending, applied, expired, ack, staleAck }
    public let status: Status
    public let reply: Data?
    public let delivery: SyncIncomingMessage?
}
/// Exclusive message/ACK dispatcher for a single authenticated session. The host
/// must serialize complete frame writes over reliable encrypted IO, close on
/// failure and never use the owned session from another dispatcher. No network,
/// polling, background policy or guest effect handler is implicitly installed.
public final class SyncMessagePump: @unchecked Sendable {
    private let lock = NSLock()
    private let session: SyncSession
    private let outbox: SyncMessageOutbox
    private let inbox: SyncMessageInbox
    private var closed = false
    private var outgoing: (id: String, digest: Data, frame: Data)?
    private var lastAck: (id: String, digest: Data, expired: Bool, frame: Data)?
    public convenience init(session: SyncSession, outbox: SyncMessageOutbox, inbox: SyncMessageInbox) throws {
        try self.init(session: session, outbox: outbox, inbox: inbox, shared: false)
    }
    init(session: SyncSession, outbox: SyncMessageOutbox, inbox: SyncMessageInbox, shared: Bool) throws {
        guard outbox.matchesIdentity(app: session.appID, device: session.localID),
              inbox.matchesIdentity(app: session.appID, device: session.localID) else { throw SyncMessagePumpError.identity }
        let channels: Set<SyncChannel> = shared ? [.state, .message, .ack] : [.message, .ack]
        guard session.allowedChannels == channels || (shared && session.allowedChannels == [.state, .message, .ack, .file]) else { throw SyncMessagePumpError.grants }
        self.session = session; self.outbox = outbox; self.inbox = inbox
    }
    deinit { close() }
    public func close() { lock.lock(); defer { lock.unlock() }; stop() }
    private func stop() { closed = true; outgoing = nil; lastAck = nil; session.close() }
    private func perform<T>(_ operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncMessagePumpError.closed }
        do { return try operation() } catch { stop(); throw error }
    }
    /// The current signed message remains first until ACK/expiry, even if a new
    /// higher-priority row arrives. Retries retain exact frame/sequence bytes.
    public func sendNext(nowMilliseconds: UInt64) throws -> Data? {
        try perform {
            let rows = try outbox.pending(peer: session.peerID, nowMilliseconds: nowMilliseconds, limit: 1000)
            if let outgoing, rows.contains(where: { $0.messageID == outgoing.id && $0.digest == outgoing.digest }) { return outgoing.frame }
            outgoing = nil
            guard let row = rows.first else { return nil }
            let frame = try session.send(channel: .message, messageID: row.messageID, payload: Array(row.envelope.encoded()))
            outgoing = (row.messageID, row.digest, frame); return frame
        }
    }
    private func ack(id: String, digest: Data, expired: Bool) throws -> Data {
        if let lastAck, lastAck.id == id && lastAck.digest == digest && lastAck.expired == expired { return lastAck.frame }
        let payload = try SyncMessageAcknowledgement(expired: expired, digest: digest).encoded()
        let frame = try session.send(channel: .ack, messageID: id, payload: Array(payload))
        lastAck = (id, digest, expired, frame); return frame
    }
    /// Call after idempotent business work. The original inbox delivery digest
    /// binds the effect; durable applied receipt precedes creation of the ACK.
    public func acknowledge(_ delivery: SyncIncomingMessage, nowMilliseconds: UInt64) throws -> Data {
        try perform {
            guard delivery.peer == session.peerID else { throw SyncMessagePumpError.identity }
            let applied = try inbox.markApplied(peer: delivery.peer, messageID: delivery.messageID, digest: delivery.digest, nowMilliseconds: nowMilliseconds)
            return try ack(id: applied.messageID, digest: applied.digest, expired: false)
        }
    }
    public func receive(frameJSON: Data, nowMilliseconds: UInt64) throws -> SyncMessagePumpResult {
        try perform {
            let verified = try session.verify(frameJSON: frameJSON)
            let frame = try JSONDecoder().decode(MessagePumpFrame.self, from: frameJSON)
            if frame.channel == .ack {
                let acknowledgement = try SyncMessageAcknowledgement.decode(Data(frame.payload))
                let matched = try outbox.acknowledgeAuthenticated(peer: session.peerID, messageID: frame.messageId, digest: acknowledgement.digest)
                if verified.delivery != .duplicate { _ = try session.commit(sequence: frame.sequence) }
                if matched, outgoing?.id == frame.messageId { outgoing = nil }
                return SyncMessagePumpResult(status: matched ? .ack : .staleAck, reply: nil, delivery: nil)
            }
            guard frame.channel == .message else { throw SyncMessagePumpError.channel }
            let delivery = try inbox.receiveAuthenticated(peer: session.peerID, messageID: frame.messageId, envelopeBytes: Data(frame.payload), nowMilliseconds: nowMilliseconds)
            // Durable pending allows transport sequence progress, but not removal
            // from the sender outbox. Applied/expired alone produce application ACK.
            if verified.delivery != .duplicate { _ = try session.commit(sequence: frame.sequence) }
            if delivery.status == .pending { return SyncMessagePumpResult(status: .pending, reply: nil, delivery: delivery) }
            let reply = try ack(id: delivery.messageID, digest: delivery.digest, expired: delivery.status == .expired)
            return SyncMessagePumpResult(status: delivery.status == .expired ? .expired : .applied, reply: reply, delivery: nil)
        }
    }
}
private struct MessagePumpFrame: Decodable { let sequence: UInt64; let channel: SyncChannel; let messageId: String; let payload: [UInt8] }
