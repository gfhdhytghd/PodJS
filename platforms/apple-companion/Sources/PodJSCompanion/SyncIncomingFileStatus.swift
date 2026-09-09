import Foundation

/// Guest-safe projection: no peer, native paths, hashes or journal metadata.
public struct SyncIncomingFileStatus: Codable, Equatable, Sendable {
    public enum State: String, Codable, Sendable { case offered, transferring, complete, cancelled, failed }
    public let transferId: String
    public let state: State
    public let receivedBytes: UInt64
    public let totalBytes: UInt64
}
