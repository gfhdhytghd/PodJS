import Foundation

public enum SyncFileSenderStatus: Sendable { case idle, queued, awaitingReply, waitingConsent, complete, cancelled }
public enum SyncFileSenderError: Error, Equatable, Sendable { case peer, queueOwnership, identity, observationChanged }

/// Internal driver, serialized by the owning client. It queues at most one
/// operation per step and never performs IO on a network, polls or grants consent.
/// Host must give it exclusive ownership of the peer's file request queue.
enum SyncFileSender {
    static func step(peer: String, local: String, requests: SyncFileRequests,
                     transfers: SyncOutgoingTransfers, sources: SyncOutgoingFiles) throws -> SyncFileSenderStatus {
        guard peer != local else { throw SyncFileSenderError.peer }
        let pending = try requests.next(peer: peer), completed = try requests.completed(peer: peer)
        guard completed.count <= 1, pending == nil || completed.isEmpty else { throw SyncFileSenderError.queueOwnership }
        let records = try transfers.list().filter { $0.peer == peer }
        let outstanding = pending ?? completed.first
        let record: SyncOutgoingTransfer
        if let outstanding {
            guard let registered = records.first(where: { $0.manifest.transferID == outstanding.request.transferID }) else { throw SyncFileSenderError.identity }
            record = registered
            if outstanding.request.method == .offer, outstanding.request.manifest != record.manifest { throw SyncFileSenderError.identity }
        } else {
            guard let next = records.first(where: { ![.complete,.cancelled].contains($0.phase) }) else { return .idle }
            record = next
        }
        if pending != nil {
            guard ![.complete,.cancelled].contains(record.phase) else { throw SyncFileSenderError.queueOwnership }
            return .awaitingReply
        }
        let id = record.manifest.transferID
        var next: SyncFileRequest
        var status: SyncFileSenderStatus = .queued
        if let observation = completed.first {
            guard let reply = observation.reply else { throw SyncFileSenderError.identity }
            // Includes strict original-request/registered-source validation.
            // A failed write leaves the exact receipt available for a retry.
            try transfers.observeCompleted(observation)
            if reply.phase == .complete || reply.phase == .cancelled {
                guard try requests.consumeCompleted(observation) else { throw SyncFileSenderError.observationChanged }
                return reply.phase == .complete ? .complete : .cancelled
            }
            if record.phase == .cancelRequested {
                next = try .operation(.cancel, transferID: id)
            } else if [.offered,.accepting,.cancelling].contains(reply.phase) {
                next = try .operation(.status, transferID: id); status = .waitingConsent
            } else if observation.request.method == .missing {
                guard let missing = reply.missing else { throw SyncFileSenderError.identity }
                if let first = missing.first {
                    let bytes = try sources.readChunk(transferID: id, index: first)
                    next = try .chunk(transferID: id, index: first, bytes: bytes)
                } else { next = try .operation(.finish, transferID: id) }
            } else { next = try .operation(.missing, transferID: id) }
            // Validate/read the next source block before consuming the receipt.
            guard try requests.consumeCompleted(observation) else { throw SyncFileSenderError.observationChanged }
        } else {
            // A crash between consume/enqueue loses only this transient choice.
            // Reoffer is idempotent and obtains current peer state. It also
            // establishes an unknown transfer before a requested cancellation.
            next = try .offer(record.manifest)
        }
        _ = try requests.enqueue(peer: peer, messageID: UUID().uuidString, request: next)
        return status
    }
}
