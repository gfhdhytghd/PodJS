import Foundation

public enum SyncStateServiceError: Error, Equatable, Sendable { case unsupported, argument }

/// Host-bound state namespace. No guest app/device identity is accepted.
/// synchronize is deliberately unsupported until an authenticated transport
/// barrier can complete it; a local cursor is not proof of peer application.
public final class SyncStateServices: Sendable {
    private let state: SyncState
    public init(state: SyncState) { self.state = state }
    public func handle(method: String, arguments: Data, cancellation: SyncCancellation? = nil) throws -> Data {
        guard ["sync.state.get", "sync.state.set", "sync.state.delete"].contains(method) else { throw SyncStateServiceError.unsupported }
        guard arguments.count <= 70 * 1024,
              let args = try JSONSerialization.jsonObject(with: arguments) as? [String: Any],
              Set(args.keys) == (method == "sync.state.set" ? Set(["key", "value"]) : Set(["key"])),
              let key = args["key"] as? String, !key.isEmpty, key.utf8.count <= 128,
              key.utf8.allSatisfy({ (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0) }) else { throw SyncStateServiceError.argument }
        try cancellation?.check()
        if method == "sync.state.get" {
            // Read one atomic snapshot, preserving tombstone metadata and the
            // distinction between missing keys and a stored JSON null.
            let snapshot = try JSONSerialization.jsonObject(with: state.snapshotJSON()) as? [String: Any]
            guard let entries = snapshot?["entries"] as? [[String: Any]] else { throw SyncStateError.malformedReply }
            guard let entry = entries.first(where: { $0["key"] as? String == key }) else {
                return Data("{\"exists\":false}".utf8)
            }
            guard let deleted = entry["deleted"] as? Bool else { throw SyncStateError.malformedReply }
            return try JSONSerialization.data(withJSONObject: ["exists": !deleted, "entry": entry])
        }
        let entry: SyncStateEntry
        if method == "sync.state.set" {
            let value = try JSONSerialization.data(withJSONObject: args["value"]!, options: [.fragmentsAllowed])
            try cancellation?.check()
            entry = try state.set(key, valueJSON: value)
        } else { entry = try state.delete(key) }
        // Do not turn a committed mutation into cancellation merely because its
        // token changed while the durable store was committing.
        let value = try JSONSerialization.jsonObject(with: Data(entry.valueJSON.utf8), options: [.fragmentsAllowed])
        return try JSONSerialization.data(withJSONObject: ["key": entry.key, "value": value, "counter": entry.counter, "deviceId": entry.deviceId, "deleted": entry.deleted])
    }
}
