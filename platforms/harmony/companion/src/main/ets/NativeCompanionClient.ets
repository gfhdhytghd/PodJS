import { common } from '@kit.AbilityKit';
import { CompanionState } from './CompanionState';
import { createCompanionState } from './NativeCompanionState';
import { CompanionMessageOutbox } from './CompanionMessageOutbox';
import { CompanionMessageInbox } from './CompanionMessageInbox';
import { createCompanionMessageOutbox, createCompanionMessageInbox } from './NativeCompanionOutbox';
import { CompanionFileRequests } from './CompanionFileRequests';
import { createCompanionFileRequests } from './NativeCompanionFileRequests';
import { CompanionIncomingFiles } from './CompanionIncomingFiles';
import { createCompanionIncomingFiles } from './NativeCompanionIncomingFiles';
import { CompanionFileChannels } from './CompanionFilePump';
import { CompanionFileSender, CompanionFileSource } from './CompanionFileSender';
import { CompanionFileManifest, validateFileManifest } from './CompanionFileWire';
import { NativeCompanionSyncCrypto } from './NativeCompanionSync';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionMessageTransport } from './CompanionMessageTransport';
import { CompanionStreamTimer } from './CompanionPacketStream';
import { CompanionOutgoingFiles, CompanionOutgoingFile, CompanionStoredFileSource } from './CompanionOutgoingFiles';
import { createCompanionOutgoingFiles, NativeCompanionImportCrypto } from './NativeCompanionOutgoingFiles';
import { importCompanionFile } from './CompanionFileImporter';
import { CompanionConnectionClient } from './CompanionConnectionOwner';
import { openCompanionGuestFile } from './NativeCompanionGuestFile';
import { CompanionOutgoingTransfers, CompanionOutgoingTransfer } from './CompanionOutgoingTransfers';
import { createCompanionOutgoingTransfers } from './NativeCompanionOutgoingTransfers';
import { CompanionFileIdentity, resolveCompanionFileIdentity } from './CompanionFileIdentity';
import { CompanionRegisteredFileSender } from './CompanionRegisteredFileSender';
import { CompanionRegisteredFileDriver } from './CompanionRegisteredFileDriver';
import { cleanupTerminalOutgoingSource } from './CompanionOutgoingCleanup';

class ClientTimer implements CompanionStreamTimer {
  schedule(milliseconds: number, expire: () => void): () => void {
    const id = setTimeout(expire, milliseconds); return () => clearTimeout(id);
  }
}
/** App-scoped durable channels. Construction grants no permissions and starts
 * no network work. The app obtains an authenticated connection separately. */
export class NativeCompanionClient implements CompanionConnectionClient {
  readonly state: CompanionState;
  readonly outbox: CompanionMessageOutbox;
  readonly inbox: CompanionMessageInbox;
  readonly fileRequests: CompanionFileRequests;
  readonly incomingFiles: CompanionIncomingFiles;
  readonly outgoingFiles: CompanionOutgoingFiles;
  readonly outgoingTransfers: CompanionOutgoingTransfers;
  private app: string;
  private local: string;
  private context: common.UIAbilityContext;
  private crypto: NativeCompanionSyncCrypto = new NativeCompanionSyncCrypto();
  private transport: CompanionMessageTransport | null = null;
  private attachedPeer: string = '';
  private registeredFileDriver: CompanionRegisteredFileDriver | null = null;
  constructor(context: common.UIAbilityContext, appId: string, localId: string) {
    this.app = appId; this.local = localId;
    this.context = context;
    this.state = createCompanionState(context, appId, localId);
    this.outbox = createCompanionMessageOutbox(context, appId, localId);
    this.inbox = createCompanionMessageInbox(context, appId, localId);
    this.fileRequests = createCompanionFileRequests(context, appId, localId);
    this.incomingFiles = createCompanionIncomingFiles(context, appId, localId);
    this.outgoingTransfers = createCompanionOutgoingTransfers(context, appId, localId);
    this.outgoingFiles = createCompanionOutgoingFiles(context, appId, this.outgoingTransfers);
  }
  createFileSender(peer: string, manifest: CompanionFileManifest, source: CompanionFileSource): CompanionFileSender {
    return new CompanionFileSender(this.fileRequests, peer, manifest, source, this.crypto);
  }
  appId(): string { return this.app; }
  localId(): string { return this.local; }
  resolveFileIdentity(transferId: string): Promise<CompanionFileIdentity | null> {
    return resolveCompanionFileIdentity(transferId, this.incomingFiles, this.outgoingTransfers, this.fileRequests);
  }
  async createStoredFileSender(peer: string, transferId: string): Promise<CompanionFileSender> {
    const files = await this.outgoingFiles.list();
    const file = files.find((item: CompanionOutgoingFile) => item.manifest.transfer_id === transferId);
    if (file === undefined || file.phase !== 'complete') throw new Error('outgoing source not complete');
    return this.createFileSender(peer, file.manifest, new CompanionStoredFileSource(this.outgoingFiles, transferId));
  }
  async createRegisteredFileSender(peer: string, transferId: string): Promise<CompanionRegisteredFileSender> {
    const identity = await this.resolveFileIdentity(transferId);
    if (identity === null || identity.direction !== 'outgoing' || identity.peer !== peer) throw new Error('registered sender identity mismatch');
    const records = (await this.outgoingTransfers.list()).filter((record: CompanionOutgoingTransfer) => record.manifest.transfer_id === transferId);
    if (records.length !== 1 || records[0].peer !== peer) throw new Error('registered sender identity changed');
    return new CompanionRegisteredFileSender(this.fileRequests, peer, records[0].manifest,
      new CompanionStoredFileSource(this.outgoingFiles, transferId), this.crypto, this.outgoingTransfers);
  }
  createRegisteredFileDriver(peer: string): CompanionRegisteredFileDriver {
    if (this.transport === null || this.attachedPeer !== peer) throw new Error('file driver requires attached peer');
    if (!this.transport.allowsChannel('file')) throw new Error('file driver channel not authorized');
    if (this.registeredFileDriver !== null) return this.registeredFileDriver;
    const driver = new CompanionRegisteredFileDriver(this, this.transport, peer,
      (cancelled: () => boolean) => cleanupTerminalOutgoingSource(this, peer, cancelled));
    this.registeredFileDriver = driver;
    this.transport.stopped.then(() => { driver.close(); if (this.registeredFileDriver === driver) this.registeredFileDriver = null; }); return driver;
  }
  importFile(transferId: string, size: number, mime: string, source: CompanionFileSource,
    cancelled: () => boolean = () => false, fresh: boolean = false, peer: string = ''): Promise<CompanionFileManifest> {
    return importCompanionFile(this.outgoingFiles, source, new NativeCompanionImportCrypto(), transferId, size, mime, cancelled, fresh, peer);
  }
  /** Installed watch guest source only. The stable read lease is always released
   * after import, including cancellation/failure; no network offer is implied. */
  async importGuestFile(transferId: string, path: string, mime: string,
    cancelled: () => boolean = () => false, fresh: boolean = false, peer: string = ''): Promise<CompanionFileManifest> {
    if (cancelled()) throw new Error('Guest import cancelled');
    const source = await openCompanionGuestFile(this.context, path);
    try { return await this.importFile(transferId, source.size, mime, source, cancelled, fresh, peer); }
    finally { source.close(); }
  }
  /** Host-generated fresh transfer ID. Source snapshot is durable before intent
   * registration. No wire request or pairing is created here; the foreground
   * driver discovers the registry later. Fresh import and registration share
   * one source lease; recovery consults durable registration before removal. */
  async queueGuestFile(peer: string, transferId: string, path: string, mime: string,
    cancelled: () => boolean = () => false): Promise<CompanionOutgoingTransfer> {
    if (typeof peer !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(peer) || peer === this.local) throw new Error('invalid file peer');
    if (typeof path !== 'string' || path.length === 0 || path.length > 1024) throw new Error('invalid guest file path');
    const metadata = new CompanionFileManifest(); metadata.transfer_id = transferId; metadata.mime = mime; metadata.sha256 = '0'.repeat(64);
    validateFileManifest(metadata);
    const check = (): void => { if (cancelled()) throw new Error('Guest file offer cancelled'); };
    check();
    if (await this.resolveFileIdentity(transferId) !== null) throw new Error('file identity already exists');
    check();
    const sources = await this.outgoingFiles.list(); check();
    if (sources.some((file: CompanionOutgoingFile) => file.manifest.transfer_id === transferId)) throw new Error('file source already exists');
    const manifest = await this.importGuestFile(transferId, path, mime, cancelled, true, peer); check();
    // Recheck after the long import; later cross-journal changes are still not
    // made atomic by this read-only guard and must be rejected by each driver.
    const identity = await this.resolveFileIdentity(transferId);
    if (identity === null || identity.direction !== 'outgoing' || identity.peer !== peer) throw new Error('file identity already exists or changed');
    check();
    return this.outgoingTransfers.register(peer, manifest, cancelled);
  }
  /** One foreground owner at a time. close before replacing a connection. */
  attach(connection: CompanionSyncConnection, durationMs: number): CompanionMessageTransport {
    if (this.transport !== null) throw new Error('companion client already attached');
    if (connection.session.appId() !== this.app || connection.session.localId() !== this.local) throw new Error('companion connection identity mismatch');
    const files = new CompanionFileChannels(this.fileRequests, this.incomingFiles, this.crypto);
    const transport = new CompanionMessageTransport(connection, this.outbox, this.inbox,
      { now: () => Date.now() }, new ClientTimer(), durationMs, this.state, files);
    this.transport = transport;
    this.attachedPeer = connection.session.peerId();
    transport.stopped.then(() => { if (this.transport === transport) { this.transport = null; this.attachedPeer = ''; } });
    return transport;
  }
  close(): void {
    if (this.registeredFileDriver !== null) this.registeredFileDriver.close(); this.registeredFileDriver = null;
    const transport = this.transport; this.transport = null; this.attachedPeer = '';
    if (transport !== null) transport.close();
  }
}
