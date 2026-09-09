import Foundation

public enum SyncFileLimitsError: Error, Equatable, Sendable { case exceedsProtocol, changed }
/// Host-approved manifest/profile limits. Profiles can only narrow the protocol
/// ceilings; combine a manifest and target profile by intersection, never union.
public struct SyncFileLimits: Codable, Equatable, Sendable {
    public let maximumFileBytes: UInt64
    public let maximumAppBytes: UInt64
    public let maximumTransfers: Int
    public static let defaults = SyncFileLimits(file: 16 * 1024 * 1024, app: 32 * 1024 * 1024, transfers: 128)
    private init(file: UInt64, app: UInt64, transfers: Int) { maximumFileBytes = file; maximumAppBytes = app; maximumTransfers = transfers }
    public init(maximumFileBytes: UInt64 = 16 * 1024 * 1024, maximumAppBytes: UInt64 = 32 * 1024 * 1024, maximumTransfers: Int = 128) throws {
        guard maximumFileBytes <= 16 * 1024 * 1024, maximumAppBytes <= 32 * 1024 * 1024,
              (1...128).contains(maximumTransfers) else { throw SyncFileLimitsError.exceedsProtocol }
        self.init(file: maximumFileBytes, app: maximumAppBytes, transfers: maximumTransfers)
    }
    public func intersecting(_ profile: SyncFileLimits) -> SyncFileLimits {
        SyncFileLimits(file: min(maximumFileBytes, profile.maximumFileBytes), app: min(maximumAppBytes, profile.maximumAppBytes), transfers: min(maximumTransfers, profile.maximumTransfers))
    }
    private enum CodingKeys: String, CodingKey { case maximumFileBytes, maximumAppBytes, maximumTransfers }
    public init(from decoder: Decoder) throws {
        let value = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(maximumFileBytes: value.decode(UInt64.self, forKey: .maximumFileBytes),
            maximumAppBytes: value.decode(UInt64.self, forKey: .maximumAppBytes), maximumTransfers: value.decode(Int.self, forKey: .maximumTransfers))
    }
}
