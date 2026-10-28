/** Transport-independent, session-local policies. Never persist signed sources. */
export type PlaybackFailure = 'network' | 'expired' | 'restricted' | 'decode' | 'other';
export function playbackFailure(error: unknown): PlaybackFailure {
  const value = error as { code?: unknown; status?: unknown } | null;
  const status = Number(value?.status), code = String(value?.code ?? '');
  if ([401, 403, 412, 429].includes(status) || ['permission_denied', 'restricted'].includes(code)) return 'restricted';
  if (status === 410 || code === 'source_expired') return 'expired';
  if (code === 'decode_error' || code === 'decoder_error') return 'decode';
  if (code === 'network_error' || code === 'cdn_error' || code === 'timeout' || status >= 500) return 'network';
  return 'other';
}
export class PlaybackRecoveryBudget {
  private started?: number;
  private attempts = 0;
  private refreshes = 0;
  constructor(private now = Date.now, readonly deadlineMs = 30_000, readonly maxAttempts = 6) {}
  reset() { this.started = undefined; this.attempts = this.refreshes = 0; }
  remaining() { return this.started === undefined ? this.deadlineMs : Math.max(0, this.deadlineMs - Math.max(0, this.now() - this.started)); }
  check() { if (this.remaining() <= 0) throw new Error('playback_recovery_deadline'); }
  claim(refresh = false) {
    this.started ??= this.now(); this.check();
    if (this.attempts >= this.maxAttempts || refresh && this.refreshes >= 1) throw new Error('playback_recovery_exhausted');
    this.attempts++; if (refresh) this.refreshes++;
  }
  /** Reject late completions; cancel is supplied by the transport when available. */
  async run<T>(work: () => Promise<T>, timer: (ms: number) => { result: Promise<unknown>; cancel(): void }, cancel?: () => void): Promise<T> {
    this.check(); const timeout = timer(this.remaining());
    try { return await Promise.race([work().then(value => { this.check(); return value; }), timeout.result.then(() => { cancel?.(); throw new Error('playback_recovery_deadline'); })]); }
    finally { timeout.cancel(); }
  }
}
/** A prepare watchdog belongs to one UI/host generation and disarms at first frame. */
export class PlaybackPrepareDeadline {
  private revision=0;
  private timer?:{cancel():void};
  cancel(){this.revision++;this.timer?.cancel();this.timer=undefined;}
  arm(ms:number, timer:(ms:number)=>{result:Promise<unknown>;cancel():void}, current:()=>boolean, expire:()=>void){
    this.cancel();const revision=this.revision;
    const operation=timer(Math.max(1,Math.floor(ms)));this.timer=operation;
    void operation.result.then(()=>{if(this.revision===revision&&current()){this.timer=undefined;expire();}},()=>{});
  }
}
/** Known Unix-second CDN expiry fields only; opaque signatures are never decoded. */
export function mediaSourceExpiry(urls: readonly string[], now: number, ttlMs = 300_000, marginMs = 30_000): number {
  let expires = now + ttlMs;
  for (const url of urls) {
    const query = url.split('?')[1]?.split('#')[0] ?? '';
    for (const field of query.split('&')) {
      const match = /^(?:deadline|expires)=(\d{9,13})$/i.exec(field);
      if (!match) continue;
      const value = Number(match[1]);
      // These fields use Unix seconds. Do not guess units from opaque values.
      if (value <= 10_000_000_000) expires = Math.min(expires, value * 1000 - marginMs);
    }
  }
  return Math.max(now, expires);
}
export interface MediaVariant { bitrate: number; width: number; height: number }
export interface PlaybackFeedback { throughputBps?: number; rebufferCount?: number; maxWidth?: number; maxHeight?: number }
/** Conservative next-source selection; never changes a playing track or enables a codec. */
export function selectSustainableVariant<T extends MediaVariant>(variants: readonly T[], preferred: T | undefined, feedback: PlaybackFeedback): T | undefined {
  const pool = variants.filter(v => !(feedback.maxWidth && v.width > feedback.maxWidth) && !(feedback.maxHeight && v.height > feedback.maxHeight));
  if (!pool.length) return undefined;
  const ceiling = Number.isFinite(feedback.throughputBps) && feedback.throughputBps! > 0 ? feedback.throughputBps! * .65 : Infinity;
  const target = feedback.rebufferCount && preferred?.bitrate ? Math.min(ceiling, preferred.bitrate * .7) : ceiling;
  const sustainable = pool.filter(v => v.bitrate > 0 && v.bitrate <= target);
  if (preferred && pool.includes(preferred) && preferred.bitrate <= target) return preferred;
  return sustainable.sort((a,b) => b.bitrate - a.bitrate)[0] ?? [...pool].filter(v => v.bitrate > 0).sort((a,b) => a.bitrate - b.bitrate)[0] ?? pool[0];
}
