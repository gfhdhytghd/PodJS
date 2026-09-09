import Foundation
import CPodJSSync

public struct SyncStateEntry: Decodable, Sendable {
    public let key: String
    public let valueJSON: String
    public let counter: UInt64
    public let deviceId: String
    public let deleted: Bool
}
public struct SyncStateReceipt: Decodable, Sendable {
    public let cursor: UInt64
    public let digest: String
    public let duplicate: Bool
}
public struct SyncStateBatch: Decodable, Sendable {
    public let messageId: String
    public let from: UInt64
    public let to: UInt64
    public let payload: String
    public let digest: String
}
public struct SyncStateAcknowledgement: Decodable, Sendable {
    public let synchronized: Bool
    public let receivedCursor: UInt64
}
public enum SyncStateError: Error, Equatable, Sendable {
    case invalidIdentity, invalidJSON, rejected, conflict, malformedReply, oversizedRequest
}
/// Durable local state and authenticated batch ingress. Rust calculates LWW
/// merges; Swift commits snapshot and receipt in one CAS before returning.
/// This does not connect or implement a synchronize/event dispatcher yet.
public final class SyncState: @unchecked Sendable {
    private let lock = NSLock()
    private let store: any SyncSnapshotStore
    private let appID: String
    private let deviceID: String
    private var listeners: [UUID: @Sendable (Data) -> Void] = [:]
    /// Callbacks run synchronously after the CAS and outside the state lock.
    /// They may read/unsubscribe/reenter, but must remain brief. Each receives
    /// the committed snapshot, not necessarily the latest state after reentry.
    /// Removal prevents future captures, not an already captured callback.
    @discardableResult public func subscribe(_ listener: @escaping @Sendable (Data) -> Void) -> UUID {
        lock.lock(); defer { lock.unlock() }
        let token = UUID(); listeners[token] = listener; return token
    }
    public func unsubscribe(_ token: UUID) {
        lock.lock(); defer { lock.unlock() }; listeners.removeValue(forKey: token)
    }
    func matchesIdentity(app: String, device: String) -> Bool { appID == app && deviceID == device }
    public init(appID: String, deviceID: String, store: any SyncSnapshotStore) throws {
        for id in [appID, deviceID] {
            guard !id.isEmpty, id.utf8.count <= 128, id.utf8.allSatisfy({
                (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95, 46, 58, 45].contains($0)
            }) else { throw SyncStateError.invalidIdentity }
        }
        self.appID = appID; self.deviceID = deviceID; self.store = store
    }
    /// nil means absent/deleted; JSON null is returned as the bytes "null".
    public func get(_ key: String) throws -> Data? {
        let reply: StateGetReply = try perform(StateKeyOperation(method: "get", key: key))
        guard reply.found else { return nil }
        guard let json = reply.valueJSON else { throw SyncStateError.malformedReply }; return Data(json.utf8)
    }
    public func set(_ key: String, valueJSON: Data) throws -> SyncStateEntry {
        guard let value = String(data: valueJSON, encoding: .utf8) else { throw SyncStateError.invalidJSON }
        return try perform(StateSetOperation(method: "set", key: key, value_json: value))
    }
    public func delete(_ key: String) throws -> SyncStateEntry {
        try perform(StateKeyOperation(method: "delete", key: key))
    }
    /// Version-one state snapshot with value fields intact, including Unicode
    /// object keys. Do not route it as an outgoing batch without cursor/ACK state.
    public func snapshotJSON() throws -> Data {
        let reply: StateSnapshotReply = try perform(StateMethod(method: "snapshot")); return Data(reply.json.utf8)
    }
    /// Persist before send. Reopen/retry returns the exact pending payload and ID.
    /// The UUID is only used for a new batch; it does not replace a pending ID.
    public func prepare(peer: String) throws -> SyncStateBatch? {
        let reply: StatePrepareReply = try perform(StatePrepareOperation(method: "prepare", peer: peer, message_id: UUID().uuidString))
        return reply.batch
    }
    /// Read-only barrier for the current durable local entries, not a statement
    /// that the peer has no undisclosed changes. Does not prepare or send.
    public func acknowledgement(peer: String) throws -> SyncStateAcknowledgement {
        try perform(StatePeerOperation(method: "acknowledgement", peer: peer))
    }
    /// Host must authenticate the ACK frame and bind its peer/channel first.
    /// False means no matching pending batch; storage errors must not be ignored.
    public func acknowledgeAuthenticated(peer: String, messageID: String, cursor: UInt64, digest: String) throws -> Bool {
        let reply: StateAckReply = try perform(StateAckOperation(method: "acknowledge", peer: peer, message_id: messageID, cursor: cursor, digest: digest))
        return reply.matched
    }
    /// Host calls only after authenticating the peer/frame and state grant.
    public func receiveAuthenticated(peer: String, messageID: String, payloadJSON: Data) throws -> SyncStateReceipt {
        guard let payload = String(data: payloadJSON, encoding: .utf8) else { throw SyncStateError.invalidJSON }
        return try perform(StateReceiveOperation(method: "receive", peer: peer, message_id: messageID, payload_json: payload))
    }
    private func perform<Q: Encodable, R: Decodable>(_ operation: Q) throws -> R {
        let request = try JSONEncoder().encode(StateRequest(appId: appID, localDeviceId: deviceID, operation: operation))
        guard request.count <= 1024 * 1024 else { throw SyncStateError.oversizedRequest }
        lock.lock()
        var notification: (Data, [@Sendable (Data) -> Void])?
        defer {
            lock.unlock()
            if let (snapshot, captured) = notification { for listener in captured { listener(snapshot) } }
        }
        let previous = try store.read()
        if let previous, previous.isEmpty { throw SyncStateError.invalidJSON }
        let bytes = previous ?? Data()
        let response: Data = try bytes.withUnsafeBytes { old in try request.withUnsafeBytes { command in
            guard let raw = pod_sync_state_calculate(previous == nil ? nil : old.bindMemory(to: UInt8.self).baseAddress, old.count,
                command.bindMemory(to: UInt8.self).baseAddress, command.count) else { throw SyncStateError.malformedReply }
            defer { pod_sync_state_response_free(raw) }; return Data(String(cString: raw).utf8)
        } }
        let envelope = try JSONDecoder().decode(StateEnvelope<R>.self, from: response)
        guard envelope.ok else { throw SyncStateError.rejected }
        guard let value = envelope.value else { throw SyncStateError.malformedReply }
        if value.changed {
            guard try store.compareExchange(expected: previous, desired: Data(value.snapshot.utf8)) else { throw SyncStateError.conflict }
            if let json = value.stateJSON { notification = (Data(json.utf8), Array(listeners.values)) }
        }
        return value.result
    }
}
private struct StateRequest<Operation: Encodable>: Encodable { let appId: String; let localDeviceId: String; let operation: Operation }
private struct StateMethod: Encodable { let method: String }
private struct StateKeyOperation: Encodable { let method: String; let key: String }
private struct StateSetOperation: Encodable { let method: String; let key: String; let value_json: String }
private struct StateReceiveOperation: Encodable { let method: String; let peer: String; let message_id: String; let payload_json: String }
private struct StatePrepareOperation: Encodable { let method: String; let peer: String; let message_id: String }
private struct StatePeerOperation: Encodable { let method: String; let peer: String }
private struct StateAckOperation: Encodable { let method: String; let peer: String; let message_id: String; let cursor: UInt64; let digest: String }
private struct StatePrepareReply: Decodable { let batch: SyncStateBatch? }
private struct StateAckReply: Decodable { let matched: Bool }
private struct StateEnvelope<Result: Decodable>: Decodable { let ok: Bool; let value: StateTransform<Result>? }
private struct StateTransform<Result: Decodable>: Decodable { let snapshot: String; let changed: Bool; let stateJSON: String?; let result: Result }
private struct StateGetReply: Decodable { let found: Bool; let valueJSON: String? }
private struct StateSnapshotReply: Decodable { let json: String }
