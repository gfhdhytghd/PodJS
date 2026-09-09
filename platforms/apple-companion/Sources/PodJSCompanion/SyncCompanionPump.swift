import Foundation

public enum SyncCompanionPumpError: Error, Equatable, Sendable { case grants, closed, channel }
public enum SyncCompanionPumpResult: Sendable {
    case state(SyncStatePumpResult)
    case message(SyncMessagePumpResult)
    case file(SyncFilePumpResult)
    public var reply: Data? {
        switch self { case .state(let result): return result.reply; case .message(let result): return result.reply; case .file(let result): return result.reply }
    }
}
/// One exclusive authenticated session for state and message journals. Routing,
/// signing and durable commits share this lock; child dispatchers are private.
/// The host must write returned frames completely in call order over reliable
/// encrypted IO and close on write failure. Do not mix a separate pump/session
/// user into this connection. File routing requires explicit file grants and
/// both durable file services; omitting them retains state/message-only mode.
/// Queue state-subscriber wakeups on the host worker; do not reenter this pump
/// synchronously from a subscriber invoked during receive.
public final class SyncCompanionPump: @unchecked Sendable {
    private let lock = NSLock()
    private let session: SyncSession
    private let state: SyncStatePump
    private let message: SyncMessagePump
    private let file: SyncFilePump?
    private var closed = false
    public init(session: SyncSession, state: SyncState, outbox: SyncMessageOutbox, inbox: SyncMessageInbox,
                fileRequests: SyncFileRequests? = nil, incomingFiles: SyncIncomingFiles? = nil) throws {
        guard (fileRequests == nil) == (incomingFiles == nil) else { throw SyncCompanionPumpError.grants }
        let channels: Set<SyncChannel> = fileRequests == nil ? [.state, .message, .ack] : [.state, .message, .ack, .file]
        guard session.allowedChannels == channels else { throw SyncCompanionPumpError.grants }
        // Validate all identities before constructing a child that owns close.
        guard state.matchesIdentity(app: session.appID, device: session.localID),
              outbox.matchesIdentity(app: session.appID, device: session.localID),
              inbox.matchesIdentity(app: session.appID, device: session.localID) else { throw SyncMessagePumpError.identity }
        if let fileRequests, let incomingFiles {
            guard fileRequests.matchesIdentity(app: session.appID, device: session.localID),
                  incomingFiles.matchesIdentity(app: session.appID, device: session.localID) else { throw SyncFilePumpError.identity }
        }
        self.session = session
        self.state = try SyncStatePump(session: session, state: state, shared: true)
        self.message = try SyncMessagePump(session: session, outbox: outbox, inbox: inbox, shared: true)
        if let fileRequests, let incomingFiles {
            self.file = try SyncFilePump(session: session, requests: fileRequests, incoming: incomingFiles, shared: true)
        } else { self.file = nil }
    }
    deinit { close() }
    public func close() { lock.lock(); defer { lock.unlock() }; stop() }
    private func stop() { closed = true; file?.close(); state.close(); message.close(); session.close() }
    private func perform<T>(_ operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncCompanionPumpError.closed }
        do { return try operation() } catch { stop(); throw error }
    }
    public func sendState() throws -> Data? { try perform { try state.sendNext() } }
    public func sendFile() throws -> Data? {
        try perform { guard let file else { throw SyncCompanionPumpError.grants }; return try file.sendNext() }
    }
    public func sendMessage(nowMilliseconds: UInt64) throws -> Data? { try perform { try message.sendNext(nowMilliseconds: nowMilliseconds) } }
    public func acknowledgeMessage(_ delivery: SyncIncomingMessage, nowMilliseconds: UInt64) throws -> Data {
        try perform { try message.acknowledge(delivery, nowMilliseconds: nowMilliseconds) }
    }
    public func receive(frameJSON: Data, nowMilliseconds: UInt64) throws -> SyncCompanionPumpResult {
        try perform {
            // Authenticate original bytes before using any field for dispatch.
            // Child verify repeats this check on exactly the same immutable Data;
            // verify alone never commits sequence/application state.
            _ = try session.verify(frameJSON: frameJSON)
            let route = try JSONDecoder().decode(CompanionRoute.self, from: frameJSON)
            switch route.channel {
            case .state: return .state(try state.receive(frameJSON: frameJSON))
            case .message: return .message(try message.receive(frameJSON: frameJSON, nowMilliseconds: nowMilliseconds))
            case .ack:
                if route.payload.first == 3 { return .state(try state.receive(frameJSON: frameJSON)) }
                if route.payload.first == 1 || route.payload.first == 2 { return .message(try message.receive(frameJSON: frameJSON, nowMilliseconds: nowMilliseconds)) }
                throw SyncCompanionPumpError.channel
            case .file:
                guard let file else { throw SyncCompanionPumpError.channel }
                return .file(try file.receive(frameJSON: frameJSON))
            }
        }
    }
}
private struct CompanionRoute: Decodable { let channel: SyncChannel; let payload: [UInt8] }
