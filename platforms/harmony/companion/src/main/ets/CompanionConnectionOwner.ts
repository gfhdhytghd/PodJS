import { CompanionPairedAttempt } from './CompanionPairedAttempt';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionMessageTransport } from './CompanionMessageTransport';

export interface CompanionConnectionClient {
  attach(connection: CompanionSyncConnection, durationMs: number): CompanionMessageTransport;
  close(): void;
}
export class CompanionConnectionStatus {
  readonly phase: string;
  readonly peer: string;
  readonly reason: string;
  constructor(phase: string, peer: string, reason: string) { this.phase = phase; this.peer = peer; this.reason = reason; }
}

/** Exclusive foreground owner for one client. Do not attach that client outside
 * this owner. The host selects transport/peer and obtains permissions first.
 * A stored pairing signer is acquired by the supplied paired attempt; this class
 * never imports credentials, reconnects automatically or acknowledges messages. */
export class CompanionConnectionOwner {
  private generation: number = 0;
  private attempt: CompanionPairedAttempt | null = null;
  private connection: CompanionSyncConnection | null = null;
  private transport: CompanionMessageTransport | null = null;
  private current: CompanionConnectionStatus = new CompanionConnectionStatus('idle', '', '');
  private listeners: Set<(status: CompanionConnectionStatus) => void> = new Set();
  constructor(private client: CompanionConnectionClient) {}
  status(): CompanionConnectionStatus { return this.current; }
  activeTransport(): CompanionMessageTransport | null { return this.transport; }
  subscribe(listener: (status: CompanionConnectionStatus) => void): () => void {
    this.listeners.add(listener); try { listener(this.current); } catch (_) {}
    return () => { this.listeners.delete(listener); };
  }
  private publish(phase: string, peer: string, reason: string): void {
    this.current = new CompanionConnectionStatus(phase, peer, reason);
    for (const listener of Array.from(this.listeners)) { try { listener(this.current); } catch (_) {} }
  }
  disconnect(): void {
    this.generation++;
    const peer = this.current.peer;
    this.cleanup(); this.publish('closed', peer, 'closed');
  }
  private cleanup(): void {
    const attempt = this.attempt, connection = this.connection, transport = this.transport;
    this.attempt = null; this.connection = null; this.transport = null;
    try { if (attempt !== null) attempt.cancel(); } catch (_) {}
    try { if (transport !== null) transport.close(); } catch (_) {}
    try { if (connection !== null) connection.close(); } catch (_) {}
    try { this.client.close(); } catch (_) {}
  }
  async connect(attempt: CompanionPairedAttempt, app: string, local: string, peer: string,
    initiator: boolean, durationMs: number = 120000, channels: string[] = ['state', 'message', 'file', 'ack']): Promise<CompanionMessageTransport> {
    if (this.attempt !== null || this.transport !== null) throw new Error('companion connection already active');
    if (!Number.isInteger(durationMs) || durationMs < 100 || durationMs > 120000) throw new Error('invalid foreground connection duration');
    if (!Array.isArray(channels) || channels.length < 2 || channels.length > 4 || new Set(channels).size !== channels.length ||
      !channels.includes('ack') || !channels.every((channel: string) => ['state', 'message', 'file', 'ack'].includes(channel))) throw new Error('invalid foreground channel grants');
    const grants = channels.slice();
    const epoch = ++this.generation; this.attempt = attempt;
    this.publish('connecting', peer, '');
    try {
      if (epoch !== this.generation) throw new Error('companion connection cancelled');
      const connection = await attempt.start(app, local, peer, initiator, grants);
      if (epoch !== this.generation) { connection.close(); throw new Error('late companion connection'); }
      this.connection = connection;
      const transport = this.client.attach(connection, durationMs); this.transport = transport;
      transport.stopped.then(result => {
        if (epoch !== this.generation || this.transport !== transport) return;
        this.cleanup(); this.publish(result.reason === 'failed' ? 'failed' : 'closed', peer, result.reason);
      });
      transport.driveOutgoing();
      this.publish('connected', peer, '');
      if (epoch !== this.generation) throw new Error('companion connection cancelled');
      return transport;
    } catch (error) {
      if (epoch === this.generation) { this.cleanup(); this.publish('failed', peer, 'connect_failed'); }
      throw error;
    } finally { if (epoch === this.generation) this.attempt = null; }
  }
}
