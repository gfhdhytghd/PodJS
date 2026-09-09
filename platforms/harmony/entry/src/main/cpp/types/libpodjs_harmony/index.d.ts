export interface Preflight { ok: boolean; error?: string }
export const preflight: (target: string, abi: number) => Preflight;
/** Host-owned, false before successful package validation and boot. */
export const hasCapability: (capability: string) => boolean;
/** Private host storage. Open exactly once per process with UIAbility filesDir.
 * IO runs on workers. Writes resolve only after file+directory fsync; rejection
 * may follow rename, so the coordinator must reread instead of assuming rollback. */
export const journalOpen: (filesDir: string) => Promise<void>;
export const journalRead: () => Promise<string | null>;
export const journalWrite: (json: string) => Promise<void>;
/** Host steady clock for elapsed-time throttling, independent of wall-clock edits. */
export const monotonicMillis: () => number;
export const boot: (js: Uint8Array, pak: Uint8Array, manifest: Uint8Array, filesDir: string) => boolean;
export const rotary: (millidegrees: number) => boolean;
/** Host-only, single consumer. Removes one effect; null means empty/not booted. */
export const pollEffect: () => string | null;
/** Single foreground listener, coalesced native-frame wakeups. Null unregisters.
 * The callback must drain a bounded batch via pollEffect; remaining effects
 * trigger another wakeup on a later visible frame. No background timer. */
export const setEffectListener: (listener: (() => void) | null) => boolean;
/** Host-only UTF-8 JSON object. Queued for the next frame, never executed inline.
 * Maximum 1 MiB/event, 256 events and 4 MiB total between successful frames.
 * False means not queued (not booted, invalid payload or backpressure).
 * The caller must retain/retry durable events; never treat false as delivery. */
export const postEvent: (json: Uint8Array) => boolean;
export interface AccessibilitySnapshot {
  json: string;
  /** Exact unsigned 64-bit semantic hash, encoded as 16 hexadecimal digits. */
  hash: string;
  logicalWidth: number;
  logicalHeight: number;
}
export const accessibilityEnabled: (enabled: boolean) => boolean;
export const accessibilityStateLabels: (labels: string[]) => boolean;
/** Returns null until committed content changes; never drives a frame. */
export const accessibilitySnapshot: () => AccessibilitySnapshot | null;
/** action: 1 activate, 2 increment, 4 decrement. Success means queued. */
export const accessibilityAction: (nodeId: number, hash: string, action: number) => boolean;
/** Trusted host only: approved installed bundle config, never guest arguments. */
export const backgroundOpen: (config: string) => number;
export const backgroundExecute: (handle: number) => Promise<string>;
export const backgroundCancel: (handle: number) => void;
export const backgroundClose: (handle: number) => void;
/** Host-only OS filesDir. CAS failure returns false; IO errors may follow commit,
 * so reread after rejection. Cross-process lock contention has code 'busy'. */
export const backgroundStoreRead: (filesDir: string) => Promise<string | null>;
export const companionStateRead: (filesDir: string, appId: string) => Promise<string | null>;
export const companionStateCompareExchange: (filesDir: string, appId: string, expected: string | null, desired: string) => Promise<boolean>;
export const backgroundStoreCompareExchange: (filesDir: string, expected: string | null, desired: string) => Promise<boolean>;
/** Production host path: acquire a private cross-process execution lease before
 * journal claim/recovery. Retained through execute and result commit until close. */
export const backgroundOpenLeased: (config: string, filesDir: string) => Promise<number>;
export const backgroundOpenSchedulerLease: (filesDir: string) => Promise<number>;
/** Replace the inert lease-holder config with host-approved code under the same
 * lease, before execute. Rejected after cancel, close, or execution start. */
export const backgroundConfigure: (handle: number, config: string) => void;
