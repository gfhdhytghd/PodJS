import { CompanionConnectionClient, CompanionConnectionOwner, CompanionConnectionStatus } from '@podjs/companion/src/main/ets/CompanionConnectionOwner';
import { CompanionPairedAttempt } from '@podjs/companion/src/main/ets/CompanionPairedAttempt';
import { CompanionMessageTransport, CompanionStateWait } from '@podjs/companion/src/main/ets/CompanionMessageTransport';
import { CompanionRegisteredFileDriver } from '@podjs/companion/src/main/ets/CompanionRegisteredFileDriver';
import { CompanionIncomingMessage } from '@podjs/companion/src/main/ets/CompanionMessageInbox';

export interface InstalledConnectionClient extends CompanionConnectionClient {
  appId(): string;
  localId(): string;
  createRegisteredFileDriver(peer: string): CompanionRegisteredFileDriver;
}
/** Native host lifecycle gate. The caller supplies an explicitly selected,
 * already approved pairing attempt; this controller never creates credentials. */
export class InstalledSyncConnection {
  private active: boolean = false;
  private generation: number = 0;
  private opening: boolean = false;
  private pumpGeneration: number = -1;
  private owner: CompanionConnectionOwner | null = null;
  private files: CompanionRegisteredFileDriver | null = null;
  private grants: string[] = [];
  constructor(private client: () => Promise<InstalledConnectionClient>, private capability: (name: string) => boolean) {}
  private channels(): string[] {
    const channels: string[] = [];
    if (this.capability('companion.sync.state')) channels.push('state');
    if (this.capability('companion.sync.message')) channels.push('message');
    if (this.capability('companion.sync.file')) channels.push('file');
    if (channels.length !== 0) channels.push('ack'); return channels;
  }
  setActive(active: boolean): void {
    this.active = active; if (!active) this.disconnect();
  }
  status(): CompanionConnectionStatus { return this.owner === null ? new CompanionConnectionStatus('idle', '', '') : this.owner.status(); }
  synchronizeState(peer: string): CompanionStateWait {
    if (!this.active || this.owner === null || this.owner.status().peer !== peer || !this.grants.includes('state')) throw new Error('No approved state connection');
    if (JSON.stringify(this.channels()) !== JSON.stringify(this.grants)) { this.disconnect(); throw new Error('Installed connection grants changed'); }
    const transport = this.owner.activeTransport(); if (transport === null) throw new Error('No approved state connection');
    return transport.synchronizeState(30000);
  }
  async acknowledgeMessage(delivery: CompanionIncomingMessage): Promise<void> {
    if (!this.active || this.owner === null || this.owner.status().peer !== delivery.peer || !this.grants.includes('message')) return;
    if (JSON.stringify(this.channels()) !== JSON.stringify(this.grants)) { this.disconnect(); return; }
    const transport = this.owner.activeTransport(); if (transport !== null) await transport.acknowledge(delivery);
  }
  disconnect(): void {
    this.generation++; this.opening = false;
    if (this.files !== null) this.files.close(); this.files = null;
    const owner = this.owner; this.owner = null; this.grants = [];
    if (owner !== null) owner.disconnect();
  }
  async connect(attempt: CompanionPairedAttempt, peer: string, initiator: boolean): Promise<CompanionMessageTransport> {
    if (!this.active) throw new Error('Installed connection requires foreground');
    if (this.opening || this.owner !== null) throw new Error('Installed connection already active');
    const grants = this.channels(); if (grants.length === 0) throw new Error('Installed sync is unavailable');
    const generation = ++this.generation; this.opening = true;
    const current = (): boolean => this.active && this.generation === generation && JSON.stringify(this.channels()) === JSON.stringify(grants);
    try {
      const client = await this.client();
      if (!current()) throw new Error('Installed connection cancelled');
      const owner = new CompanionConnectionOwner(client); this.owner = owner; this.grants = grants;
      const transport = await owner.connect(attempt, client.appId(), client.localId(), peer, initiator, 120000, grants);
      if (!current()) throw new Error('Installed connection cancelled');
      if (grants.includes('file')) this.files = client.createRegisteredFileDriver(peer);
      transport.stopped.then(() => { if (this.owner === owner) this.disconnect(); });
      return transport;
    } catch (error) {
      if (this.generation === generation) this.disconnect();
      throw error;
    } finally { if (this.generation === generation) this.opening = false; }
  }
  /** Called by the native foreground timer. It does not extend the transport's
   * two-minute deadline. Only one file pump can be in flight. */
  async pump(): Promise<void> {
    if (!this.active || this.owner === null) return;
    if (JSON.stringify(this.channels()) !== JSON.stringify(this.grants)) { this.disconnect(); return; }
    if (this.pumpGeneration === this.generation) return;
    const transport = this.owner.activeTransport(); if (transport === null) return;
    const generation = this.generation; this.pumpGeneration = generation;
    try {
      transport.driveOutgoing();
      if (this.files !== null) await this.files.step();
    } catch (_) { if (this.generation === generation) this.disconnect(); }
    finally { if (this.pumpGeneration === generation) this.pumpGeneration = -1; }
  }
}
