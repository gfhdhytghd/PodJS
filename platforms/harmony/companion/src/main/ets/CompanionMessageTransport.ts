import { CompanionMessagePump } from './CompanionMessagePump';
import { CompanionMessageOutbox } from './CompanionMessageOutbox';
import { CompanionMessageInbox, CompanionIncomingMessage } from './CompanionMessageInbox';
import { CompanionSyncConnection } from './CompanionSyncAttempt';
import { CompanionSyncFrame } from './CompanionSyncAuth';
import { CompanionStreamTimer } from './CompanionPacketStream';
import { decodeSyncObject } from './CompanionSyncExchange';
import { encodeSyncUtf8 } from './CompanionStatePump';
import { CompanionState } from './CompanionState';
import { CompanionChannelPump } from './CompanionChannelPump';
import { CompanionFileChannels } from './CompanionFilePump';
import { CompanionFileSender } from './CompanionFileSender';
import { CompanionFileRequests, CompanionFileTerminalReceipt } from './CompanionFileRequests';

export interface CompanionMessageClock { now(): number; }
export class CompanionMessageTransportStop {
  reason: string;
  error: Error | null;
  constructor(reason: string, error: Error | null) { this.reason = reason; this.error = error; }
}
export class CompanionStateWait {
  constructor(readonly done: Promise<number>, private stop: () => void) {}
  cancel(): void { this.stop(); }
}
class StateWaiter {
  resolve: (cursor: number) => void = () => {};
  reject: (error: Error) => void = () => {};
  cancelTimer: () => void = () => {};
  done: Promise<number> = new Promise<number>((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
}
/** Foreground message connection owner with optional shared state/file channels. Receives into durable inbox without
 * blocking on application work. Host reads inbox.pending and explicitly calls
 * acknowledge after idempotent business writes. No scanning/reconnect/polling. */
export class CompanionMessageTransport {
  readonly stopped: Promise<CompanionMessageTransportStop>;
  private resolveStopped: (result: CompanionMessageTransportStop) => void = () => {};
  private connection: CompanionSyncConnection;
  private pump: CompanionMessagePump | null = null;
  private channels: CompanionChannelPump | null = null;
  private clock: CompanionMessageClock;
  private closed: boolean = false;
  private inFlight: string | null = null;
  private stateInFlight: string | null = null;
  private fileInFlight: string | null = null;
  private fileRequests: CompanionFileRequests | null = null;
  private fileSender: CompanionFileSender | null = null;
  private fileProgress: string = 'idle';
  private tail: Promise<void> = Promise.resolve();
  private queued: number = 0;
  private cancelTimer: () => void = () => {};
  private stateSource: CompanionState | null;
  private unsubscribeState: () => void = () => {};
  private automaticOutgoing: boolean = false;
  private outgoingRequested: boolean = false;
  private outgoingScheduled: boolean = false;
  private stateWaiters: StateWaiter[] = [];
  private scheduler: CompanionStreamTimer;
  constructor(connection: CompanionSyncConnection, outbox: CompanionMessageOutbox, inbox: CompanionMessageInbox,
    clock: CompanionMessageClock, timer: CompanionStreamTimer, durationMs: number, state: CompanionState | null = null, files: CompanionFileChannels | null = null) {
    if (!Number.isInteger(durationMs) || durationMs < 100 || durationMs > 120000) throw new Error('invalid message transport duration');
    if (state === null && files !== null) throw new Error('file transport requires shared channel configuration');
    if (state === null) this.pump = new CompanionMessagePump(connection.session, outbox, inbox);
    else this.channels = new CompanionChannelPump(connection.session, state, outbox, inbox, files);
    this.connection = connection; this.clock = clock; this.stateSource = state;
    this.scheduler = timer;
    this.fileRequests = files === null ? null : files.requests;
    this.stopped = new Promise<CompanionMessageTransportStop>(resolve => { this.resolveStopped = resolve; });
    try {
      this.cancelTimer = timer.schedule(durationMs, () => this.stop('deadline', new Error('message transport deadline')));
      if (this.closed) { this.cancelTimer(); return; }
      this.receiveLoop();
    } catch (error) { this.stop('failed', error as Error); }
  }
  close(): void { this.stop('closed', new Error('message transport closed')); }
  allowsChannel(channel: string): boolean { this.check(); return this.connection.session.allowsChannel(channel); }
  /** Bounded, individually cancellable wait for durable ACKs of current state.
   * Timeout/cancel removes only the waiter, leaving the shared connection live. */
  synchronizeState(durationMs: number = 30000): CompanionStateWait {
    if (!this.allowsChannel('state') || this.stateSource === null) throw new Error('state channel not authorized');
    if (!Number.isInteger(durationMs) || durationMs < 100 || durationMs > 30000) throw new Error('invalid state wait duration');
    if (this.stateWaiters.length >= 8) throw new Error('state wait capacity exceeded');
    const waiter = new StateWaiter(); this.stateWaiters.push(waiter);
    const result = new CompanionStateWait(waiter.done, () => this.finishStateWaiter(waiter, new Error('state wait cancelled')));
    try {
      waiter.cancelTimer = this.scheduler.schedule(durationMs, () => this.finishStateWaiter(waiter, new Error('state wait deadline')));
      if (this.stateWaiters.includes(waiter)) this.driveOutgoing(); else waiter.cancelTimer();
    } catch (error) { this.finishStateWaiter(waiter, error as Error); }
    return result;
  }
  private finishStateWaiter(waiter: StateWaiter, error: Error | null, cursor: number = 0): void {
    const index = this.stateWaiters.indexOf(waiter); if (index < 0) return;
    this.stateWaiters.splice(index, 1); waiter.cancelTimer();
    if (error !== null) waiter.reject(error); else waiter.resolve(cursor);
  }
  private async resolveStateWaiters(): Promise<void> {
    if (this.stateWaiters.length === 0 || this.stateSource === null) return;
    const state = await this.stateSource.acknowledgement(this.connection.session.peerId()); this.check();
    if (state.synchronized) for (const waiter of this.stateWaiters.slice()) this.finishStateWaiter(waiter, null, state.appliedCursor);
  }
  private check(): void { if (this.closed) throw new Error('message transport stopped'); }
  private stop(reason: string, error: Error | null): void {
    if (this.closed) return; this.closed = true;
    for (const waiter of this.stateWaiters.slice()) this.finishStateWaiter(waiter, error === null ? new Error('state wait transport ended') : error);
    this.resolveStopped(new CompanionMessageTransportStop(reason, error));
    try { this.cancelTimer(); } catch (_) {}
    this.outgoingRequested = false;
    try { this.unsubscribeState(); } catch (_) {}
    try { if (this.pump !== null) this.pump.close(); } catch (_) {}
    try { if (this.channels !== null) this.channels.close(); } catch (_) {}
    try { this.connection.close(); } catch (_) {}
  }
  /** Opt-in event-driven state/message draining for a foreground owner. Wake
   * again after adding messages to the outbox. State changes and matching ACKs
   * wake it automatically; no polling, reconnect or automatic business ACK.
   * Pending messages never prevent independent state batches from advancing. */
  driveOutgoing(): void {
    this.check();
    if (!this.automaticOutgoing) {
      this.automaticOutgoing = true;
      try {
        if (this.stateSource !== null && this.allowsChannel('state')) this.unsubscribeState = this.stateSource.subscribe(() => this.scheduleOutgoing());
      } catch (error) { this.stop('failed', error as Error); throw error; }
    }
    this.scheduleOutgoing();
  }
  private scheduleOutgoing(): void {
    if (this.closed || !this.automaticOutgoing) return;
    this.outgoingRequested = true;
    if (this.outgoingScheduled) return;
    this.outgoingScheduled = true;
    Promise.resolve().then(async () => {
      while (!this.closed && this.outgoingRequested) {
        this.outgoingRequested = false;
        if (this.channels !== null && this.allowsChannel('state')) await this.sendState();
        if (!this.closed && this.allowsChannel('message')) await this.sendNext();
      }
    }).catch((error: Error) => this.stop('failed', error)).finally(() => {
      this.outgoingScheduled = false;
      if (this.outgoingRequested) this.scheduleOutgoing();
    });
  }
  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    if (this.closed) return Promise.reject(new Error('message transport stopped'));
    if (this.queued >= 8) { this.stop('failed', new Error('message transport queue full')); return Promise.reject(new Error('message transport queue full')); }
    this.queued++;
    const task = this.tail.then(async () => { this.check(); const result = await work(); this.check(); return result; });
    this.tail = task.then(() => { this.queued--; }, (error: Error) => { this.queued--; this.stop('failed', error); });
    // Resolves/rejects callers at the deadline even if a storage/OS promise stalls.
    return Promise.race([task, this.stopped.then((result: CompanionMessageTransportStop) => {
      throw result.error === null ? new Error('message transport ended') : result.error;
    })]);
  }
  private async write(frame: CompanionSyncFrame): Promise<void> {
    this.check(); await this.connection.stream.write(new Uint8Array(encodeSyncUtf8(JSON.stringify(frame), 2097152))); this.check();
  }
  /** Sends at most one unacknowledged outgoing message. Call again after ACK or
   * after enqueueing new work. false is not a claim that the peer is caught up. */
  async sendNext(): Promise<boolean> {
    if (!this.allowsChannel('message')) return Promise.reject(new Error('message channel not authorized'));
    return this.enqueue(async () => {
      if (this.inFlight !== null) return false;
      const frame = this.channels !== null ? await this.channels.sendMessage(this.clock.now()) : await (this.pump as CompanionMessagePump).sendNext(this.clock.now()); this.check();
      if (frame === null) return false;
      this.inFlight = frame.messageId; await this.write(frame); return true;
    });
  }
  /** Shared-mode state send. Message business ACKs do not gate this channel. */
  async sendState(): Promise<boolean> {
    if (!this.allowsChannel('state')) return Promise.reject(new Error('state channel not authorized'));
    return this.enqueue(async () => {
      if (this.channels === null) throw new Error('state channel not configured');
      if (this.stateInFlight !== null) return false;
      const frame = await this.channels.sendState(); this.check();
      if (frame === null) { await this.resolveStateWaiters(); return false; }
      this.stateInFlight = frame.messageId; await this.write(frame); return true;
    });
  }
  async sendFile(): Promise<boolean> {
    if (!this.allowsChannel('file')) return Promise.reject(new Error('file channel not authorized'));
    return this.enqueue(async () => {
      if (this.channels === null) throw new Error('file channel not configured');
      if (this.fileInFlight !== null) return false;
      const frame = await this.channels.sendFile(); this.check();
      if (frame === null) return false;
      this.fileInFlight = frame.messageId; await this.write(frame); return true;
    });
  }
  fileStatus(): string { return this.fileProgress; }
  /** Serialize receipt consumption with the driver, so observing the durable
   * reply before its advance callback finishes cannot restart a completed file. */
  consumeFileTerminal(receipt: CompanionFileTerminalReceipt): Promise<boolean> {
    const expected = new CompanionFileTerminalReceipt(receipt.peer, receipt.messageId, receipt.transferId, receipt.digest, receipt.phase);
    return this.enqueue(async () => {
      if (this.fileRequests === null || expected.peer !== this.connection.session.peerId()) throw new Error('file receipt peer mismatch');
      if (this.fileInFlight !== null || this.fileSender !== null) return false;
      const consumed = await this.fileRequests.consumeTerminal(expected); this.check();
      if (consumed) this.fileProgress = 'idle'; return consumed;
    });
  }
  /** Opt-in automatic advancement after durable replies. Consent waits pause
   * without polling; call driveFile again after the user approves on the peer. */
  async driveFile(sender: CompanionFileSender): Promise<string> {
    if (!this.allowsChannel('file')) return Promise.reject(new Error('file channel not authorized'));
    return this.enqueue(async () => {
      if (!sender.matchesQueue(this.fileRequests, this.connection.session.peerId())) throw new Error('file sender queue mismatch');
      if (this.fileSender !== null && this.fileSender !== sender) throw new Error('file sender already attached');
      this.fileSender = sender; return this.advanceFile(true);
    });
  }
  private async advanceFile(explicit: boolean): Promise<string> {
    if (this.fileSender === null || this.channels === null) throw new Error('file sender not configured');
    if (this.fileInFlight !== null) return 'awaiting_reply';
    this.fileProgress = await this.fileSender.step(() => this.closed); this.check();
    if (this.fileProgress === 'complete' || this.fileProgress === 'cancelled') { this.fileSender = null; return this.fileProgress; }
    if (this.fileProgress === 'waiting_consent' && !explicit) return this.fileProgress;
    const frame = await this.channels.sendFile(); this.check();
    if (frame !== null) { this.fileInFlight = frame.messageId; await this.write(frame); }
    return this.fileProgress;
  }
  async acknowledge(delivery: CompanionIncomingMessage): Promise<void> {
    if (!this.allowsChannel('message') || !this.allowsChannel('ack')) throw new Error('message acknowledgement not authorized');
    // Snapshot only the authenticated identity/token fields used by the pump.
    const copy = new CompanionIncomingMessage(delivery.peer, delivery.messageId, delivery.envelope, delivery.digest, delivery.status);
    return this.enqueue(async () => {
      const ack = this.channels !== null ? await this.channels.acknowledgeMessage(copy, this.clock.now()) : await (this.pump as CompanionMessagePump).acknowledge(copy, this.clock.now());
      this.check(); await this.write(ack);
    });
  }
  private async receiveLoop(): Promise<void> {
    try {
      while (!this.closed) {
        const packet = await this.connection.stream.read(); this.check();
        if (packet === null) { this.stop('eof', null); return; }
        const frame = decodeSyncObject(packet, 2097152) as CompanionSyncFrame;
        await this.enqueue(async () => {
          if (this.channels !== null) {
            const result = await this.channels.receive(frame, this.clock.now()); this.check();
            if (result.reply !== null) await this.write(result.reply);
            if (result.status === 'ack') {
              if (result.channel === 'state' && frame.messageId === this.stateInFlight) { this.stateInFlight = null; this.scheduleOutgoing(); }
              if (result.channel === 'message' && frame.messageId === this.inFlight) { this.inFlight = null; this.scheduleOutgoing(); }
            }
            if (result.channel === 'file' && (result.status === 'reply' || result.status === 'duplicate_reply') && frame.messageId === this.fileInFlight) {
              this.fileInFlight = null;
              if (this.fileSender !== null) await this.advanceFile(false);
            }
          } else {
            const result = await (this.pump as CompanionMessagePump).receive(frame, this.clock.now()); this.check();
            if (result.reply !== null) await this.write(result.reply);
            if (result.status === 'ack' && frame.messageId === this.inFlight) { this.inFlight = null; this.scheduleOutgoing(); }
          }
        });
      }
    } catch (error) { this.stop('failed', error as Error); }
  }
}
