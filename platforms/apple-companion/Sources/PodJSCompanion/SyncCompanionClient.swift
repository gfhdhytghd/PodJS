import Foundation

public enum SyncCompanionClientError: Error, Equatable, Sendable { case identity, metadata, closed, connectionOwned }
/// Durable Apple host instance. Supply an existing trusted OS-private parent.
/// App identity must come from installed host metadata, not guest input. This
/// creates no radio/network connection and grants no unsupported capabilities.
/// One client holds all namespace leases and owns at most one attached pump.
public final class SyncCompanionClient: @unchecked Sendable {
    public let appID: String
    public let deviceID: String
    public let fileLimits: SyncFileLimits
    public let state: SyncState
    public let messageOutbox: SyncMessageOutbox
    public let messageInbox: SyncMessageInbox
    public let incomingFiles: SyncIncomingFiles
    public let fileRequests: SyncFileRequests
    public let outgoingFiles: SyncOutgoingFiles
    public let outgoingTransfers: SyncOutgoingTransfers
    private let lock = NSLock()
    private let identityStore: FileSyncSnapshotStore
    private let stateStore: FileSyncSnapshotStore
    private let outboxStore: FileSyncSnapshotStore
    private let inboxStore: FileSyncSnapshotStore
    private let fileRequestStore: FileSyncSnapshotStore
    private let outgoingTransferStore: FileSyncSnapshotStore
    private var connection: SyncCompanionPump?
    private var closed = false

    public init(appID: String, deviceID: String, privateParent: URL,
                declaredFileLimits: SyncFileLimits = .defaults, profileFileLimits: SyncFileLimits = .defaults) throws {
        for identity in [appID, deviceID] {
            guard !identity.isEmpty, identity.utf8.count <= 128, identity.utf8.allSatisfy({
                (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
            }) else { throw SyncCompanionClientError.identity }
        }
        self.appID = appID; self.deviceID = deviceID
        self.fileLimits = declaredFileLimits.intersecting(profileFileLimits)
        let root = privateParent.appendingPathComponent("podjs-companion-" + appID, isDirectory: true)
        // Parent lease and identity commit precede opening child stores. Never
        // reset a foreign/corrupt identity or silently attach it to a new device.
        let identityStore = try FileSyncSnapshotStore(privateRoot: root)
        let expected = ClientIdentity(schema: 1, kind: "apple-companion", app: appID, device: deviceID)
        if let bytes = try identityStore.read() {
            guard try JSONDecoder().decode(ClientIdentity.self, from: bytes) == expected else { throw SyncCompanionClientError.identity }
        } else {
            guard try identityStore.compareExchange(expected: nil, desired: JSONEncoder().encode(expected)) else { throw SyncCompanionClientError.metadata }
        }
        self.identityStore = identityStore
        self.stateStore = try FileSyncSnapshotStore(privateRoot: root.appendingPathComponent("state"))
        self.outboxStore = try FileSyncSnapshotStore(privateRoot: root.appendingPathComponent("message-outbox"), maximumBytes: 18 * 1024 * 1024)
        self.inboxStore = try FileSyncSnapshotStore(privateRoot: root.appendingPathComponent("message-inbox"), maximumBytes: 18 * 1024 * 1024)
        self.fileRequestStore = try FileSyncSnapshotStore(privateRoot: root.appendingPathComponent("file-requests"), maximumBytes: 18 * 1024 * 1024)
        self.outgoingTransferStore = try FileSyncSnapshotStore(privateRoot: root.appendingPathComponent("outgoing-transfers"))
        self.state = try SyncState(appID: appID, deviceID: deviceID, store: stateStore)
        self.messageOutbox = try SyncMessageOutbox(appID: appID, deviceID: deviceID, store: outboxStore)
        self.messageInbox = try SyncMessageInbox(appID: appID, deviceID: deviceID, store: inboxStore)
        self.outgoingTransfers = try SyncOutgoingTransfers(appID: appID, deviceID: deviceID, store: outgoingTransferStore)
        self.incomingFiles = try SyncIncomingFiles(appID: appID, deviceID: deviceID, privateRoot: root.appendingPathComponent("incoming-files"), outgoingTransfers: outgoingTransfers, limits: fileLimits)
        self.outgoingFiles = SyncOutgoingFiles(sharedFiles: incomingFiles)
        self.fileRequests = try SyncFileRequests(appID: appID, deviceID: deviceID, store: fileRequestStore)
        try incomingFiles.recoverOutgoingImports()
    }
    deinit { close() }
    /// Freeze and bind a fresh source as one recoverable operation. It does not
    /// send a frame. On an uncertain error inspect source/task history before
    /// retrying: a committed registration is retained, never silently removed.
    public func importOutgoing(sourceURL: URL, peer: String, transferID: String, mime: String = "", cancellation: SyncCancellation? = nil) throws -> SyncFileManifest {
        try cancellation?.check()
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncCompanionClientError.closed }
        // Validate target before opening or reading any source bytes.
        guard peer != deviceID, !peer.isEmpty, peer.utf8.count <= 128, peer.utf8.allSatisfy({
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [95,46,58,45].contains($0)
        }) else { throw SyncIncomingFileError.identity }
        let source = try SyncHostFileSource(url: sourceURL, transferID: transferID, mime: mime, cancellation: cancellation)
        return try incomingFiles.importSource(source, peer: peer)
    }
    /// Register an already completed immutable source before creating any wire
    /// requests. The peer is permanently bound to this retained transfer ID.
    public func registerOutgoing(peer: String, transferID: String) throws -> SyncOutgoingTransfer {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncCompanionClientError.closed }
        return try incomingFiles.registerSource(peer: peer, id: transferID, transfers: outgoingTransfers)
    }
    /// Advances one registered outgoing file operation. The host sends the
    /// queued request through the attached pump, then calls again after a durable
    /// reply. Pace waitingConsent from UI/lifecycle; do not busy poll. Do not mix
    /// manual queue producers/consumers for this peer with this driver.
    public func stepOutgoing(peer: String) throws -> SyncFileSenderStatus {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncCompanionClientError.closed }
        return try SyncFileSender.step(peer: peer, local: deviceID, requests: fileRequests, transfers: outgoingTransfers, sources: outgoingFiles)
    }
    /// Attach an already authenticated host session after explicit pairing and
    /// permission negotiation. Requires state/message/ACK, optionally file.
    /// Host owns actual IO and must call detach on EOF/background/write failure.
    public func attach(session: SyncSession) throws -> SyncCompanionPump {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { throw SyncCompanionClientError.closed }
        guard connection == nil else { throw SyncCompanionClientError.connectionOwned }
        let files = session.allowedChannels.contains(.file)
        let pump = try SyncCompanionPump(session: session, state: state, outbox: messageOutbox, inbox: messageInbox,
            fileRequests: files ? fileRequests : nil, incomingFiles: files ? incomingFiles : nil)
        connection = pump; return pump
    }
    public func detach() {
        lock.lock(); defer { lock.unlock() }; connection?.close(); connection = nil
    }
    public func close() {
        lock.lock(); defer { lock.unlock() }
        guard !closed else { return }; closed = true
        connection?.close(); connection = nil
        incomingFiles.close(); outgoingTransferStore.close(); fileRequestStore.close(); inboxStore.close(); outboxStore.close(); stateStore.close(); identityStore.close()
    }
}
private struct ClientIdentity: Codable, Equatable { let schema: Int; let kind: String; let app: String; let device: String }
