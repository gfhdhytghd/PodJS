import Foundation
import CPodJSSync

public struct SyncOutgoingSource: Codable, Sendable {
    public enum Phase: String, Codable, Sendable { case importing, registering, preparing, staging, completing, complete, removing, removed }
    public let manifest: SyncFileManifest
    public internal(set) var phase: Phase
    public internal(set) var peer: String? = nil
}
/// Host-only source staging facade. Complete sources are immutable; reads
/// reverify native bytes. IDs survive removal and cannot collide with incoming
/// files. This is not yet an OS file importer or durable network transfer driver.
public final class SyncOutgoingFiles: Sendable {
    private let files: SyncIncomingFiles
    public init(sharedFiles: SyncIncomingFiles) { self.files = sharedFiles }
    public func list() throws -> [SyncOutgoingSource] { try files.listSources() }
    public func prepare(_ manifest: SyncFileManifest) throws { try files.prepareSource(manifest) }
    public func writeChunk(transferID: String, index: Int, bytes: Data) throws { try files.writeSource(id: transferID, index: index, bytes: bytes) }
    public func finish(transferID: String) throws { try files.finishSource(id: transferID) }
    public func readChunk(transferID: String, index: Int) throws -> Data { try files.readSource(id: transferID, index: index) }
    /// Explicit host removal, not remote cancel. Retains the ID tombstone.
    public func remove(transferID: String) throws { try files.removeSource(id: transferID) }
    /// Host-selected local file only. Synchronous bounded IO belongs on a worker.
    /// The native descriptor is retained across both passes; source changes fail
    /// verification. Fresh IDs only. No picker/security-scoped access is granted.
    public func importFile(sourceURL: URL, transferID: String, mime: String = "", cancellation: SyncCancellation? = nil) throws -> SyncFileManifest {
        let source = try SyncHostFileSource(url: sourceURL, transferID: transferID, mime: mime, cancellation: cancellation)
        return try files.importSource(source)
    }
}

// Confined to a single import call; never exposed to network or guest input.
final class SyncHostFileSource {
    private let handle: OpaquePointer
    let manifest: SyncFileManifest
    private let cancellation: SyncCancellation?
    init(url: URL, transferID: String, mime: String, cancellation: SyncCancellation? = nil) throws {
        self.cancellation = cancellation; try cancellation?.check()
        guard url.isFileURL, !url.path.utf8.contains(0), !transferID.utf8.contains(0), !mime.utf8.contains(0),
              pod_runtime_abi_version() == 2 else { throw SyncIncomingFileError.manifest }
        guard let handle = withSyncCancellation(cancellation, { token in url.path.withCString { path in transferID.withCString { id in mime.withCString { mime in
            pod_sync_file_source_open_cancellable(path, id, mime, token)
        } } } }) else { try cancellation?.check(); throw SyncIncomingFileError.manifest }
        do {
            guard let raw = pod_sync_file_source_manifest(handle) else { throw SyncIncomingFileError.manifest }
            self.manifest = try JSONDecoder().decode(SyncFileManifest.self, from: Data(String(cString: raw).utf8))
        } catch { pod_sync_file_source_close(handle); throw error }
        self.handle = handle
    }
    deinit { pod_sync_file_source_close(handle) }
    func check() throws { try cancellation?.check(); guard pod_sync_file_source_check(handle) else { throw SyncIncomingFileError.manifest } }
    func read(index: Int) throws -> Data {
        try cancellation?.check()
        guard index >= 0 else { throw SyncIncomingFileError.manifest }
        var length = 0
        guard let raw = pod_sync_file_source_read(handle, index, &length), (1...65536).contains(length) else { throw SyncIncomingFileError.manifest }
        let bytes = Data(bytes: raw, count: length); try cancellation?.check(); return bytes
    }
}
