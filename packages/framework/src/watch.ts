import {traceServiceWork,__setServiceTraceEnabled} from './service-trace.ts';
import { cancelActiveTouches } from "@pocketjs/framework/gesture";
import { dispatchAccessibilityAction } from "@pocketjs/framework/input";
import { registerServicePump } from "@pocketjs/framework/services";
import type { PodCapabilityId } from "./targets.ts";

export const RelativeAxis = Object.freeze({ Primary: 0, Secondary: 1 } as const);
export const RelativeAxisUnits = Object.freeze({ PerDegree: 1_000, PerTurn: 360_000 } as const);
export type RelativeAxisId = (typeof RelativeAxis)[keyof typeof RelativeAxis];
export type LifecycleState = "active" | "inactive" | "background";
export type SystemTheme = "light" | "dark";
export type HapticKind = "click" | "success" | "warning" | "error";
export type NavigationState = Readonly<{ canGoBack: boolean; rootSwipeEnabled?: boolean }>;
export type RelativeAxisStep = -1 | 0 | 1;

export interface DisplayMetrics {
  readonly logicalWidth: number;
  readonly logicalHeight: number;
  readonly physicalWidth: number;
  readonly physicalHeight: number;
  readonly density: number;
  /** Android ViewConfiguration, in physical px and px/s. Absent on older hosts. */
  readonly scrollConfiguration?: Readonly<{ touchSlopPx: number; minimumFlingVelocityPx: number; maximumFlingVelocityPx: number }>;
  readonly shape: "round" | "rect";
  readonly safeInsets: Readonly<{ top: number; right: number; bottom: number; left: number }>;
}

interface PodHost {
  takeEvents(): string | undefined;
  emit(line: string): void;
  displayMetrics(): string;
  capabilities(): string;
  fontMetrics?(slot:number):string|undefined;
  kvGet(key: string): string | undefined;
  kvSet(key: string, value: string): number;
  kvDelete(key: string): number;
  kvKeys(): string;
  kvApply?(mutations:string):number;
  kvFingerprint?(key:string):string|undefined;
}

type AxisHandler = (delta: number) => void;
type LifecycleHandler = (state: LifecycleState) => void;
type ThemeHandler = (theme: SystemTheme) => void;
type BackHandler = () => boolean | void;

const axisHandlers = new Map<RelativeAxisId, Set<AxisHandler>>();
const lifecycleHandlers = new Set<LifecycleHandler>();
export type CheckpointReason = "inactive" | "background" | "screen-off";
const checkpointHandlers = new Set<(reason:CheckpointReason)=>void>();
const themeHandlers = new Set<ThemeHandler>();
const backHandlers: BackHandler[] = [];
let navigationState: NavigationState = Object.freeze({ canGoBack: false });
const hostEventHandlers = new Set<(event: Record<string, unknown>) => void>();
/** Demand-only listener ownership; no per-event accounting. */
export function watchResourceCounts() {
 return {hostEvents:hostEventHandlers.size,lifecycle:lifecycleHandlers.size+checkpointHandlers.size,theme:themeHandlers.size,
  back:backHandlers.length,axis:[...axisHandlers.values()].reduce((n,v)=>n+v.size,0)};
}
/** Internal shared event fanout; services must not drain the host queue themselves. */
export function __onHostEvent(handler: (event: Record<string, unknown>) => void): () => void {
  return subscribe(hostEventHandlers, handler);
}
export interface RawScrollInput { vertical:number; uptimeMs:number; source:number }
/** Android scroll units before mouse scaling or axis-direction conversion. Positive is upward.
 * Delivered in addition to RelativeAxis. Consumers must avoid handling both for the same control.
 */
export function onRawScrollInput(handler:(event:RawScrollInput)=>void):()=>void {
  return __onHostEvent(event=>{
    if (!hasCapability("input.rotary") || event.t !== "input.scroll" || typeof event.vertical !== "number" || !Number.isFinite(event.vertical) || event.vertical === 0
      || typeof event.uptimeMs !== "number" || !Number.isFinite(event.uptimeMs) || event.uptimeMs < 0 || !Number.isInteger(event.source)) return;
    handler({vertical:event.vertical, uptimeMs:event.uptimeMs, source:event.source as number});
  });
}
let lifecycleState: LifecycleState = "active";
let themeState: SystemTheme = "dark";

function host(): PodHost | null {
  const value = (globalThis as { pod?: unknown }).pod as Partial<PodHost> | undefined;
  return value && typeof value.takeEvents === "function" ? value as PodHost : null;
}

function subscribe<T>(set: Set<T>, handler: T): () => void {
  set.add(handler);
  return () => set.delete(handler);
}

/**
 * Subscribe to normalized relative motion. Positive deltas move toward the
 * next/increasing position; hosts normalize their native axis orientation
 * before emitting this ABI value.
 */
export function onAxisDelta(axis: RelativeAxisId, handler: AxisHandler): () => void {
  if (axis !== RelativeAxis.Primary && axis !== RelativeAxis.Secondary) {
    throw new Error(`PodJS: unknown relative axis ${axis}`);
  }
  let handlers = axisHandlers.get(axis);
  if (!handlers) axisHandlers.set(axis, handlers = new Set());
  handlers.add(handler);
  return () => handlers?.delete(handler);
}

/** Reduce normalized relative motion to a finite direction step. */
export function normalizedRelativeAxisStep(delta: number): RelativeAxisStep {
  if (!Number.isFinite(delta) || delta === 0) return 0;
  return delta > 0 ? 1 : -1;
}

export function getDisplayMetrics(): DisplayMetrics {
  const raw = host()?.displayMetrics?.();
  if (!raw) {
    return {
      logicalWidth: 240,
      logicalHeight: 240,
      physicalWidth: 240,
      physicalHeight: 240,
      density: 1,
      shape: "round",
      safeInsets: { top: 0, right: 0, bottom: 0, left: 0 },
    };
  }
  return Object.freeze(JSON.parse(raw) as DisplayMetrics);
}

export function capabilities(): readonly PodCapabilityId[] {
  return Object.freeze(JSON.parse(host()?.capabilities?.() ?? "[]") as PodCapabilityId[]);
}

export function hasCapability(capability: PodCapabilityId): boolean {
  return capabilities().includes(capability);
}

/** Synchronous durability boundary, before observers can suspend/dispose UI owners.
 * Callbacks must snapshot live state and use synchronous storage, never await IO.
 * KV writes (including an enclosing batch) are committed before event delivery returns. */
export function onLifecycleCheckpoint(handler:(reason:CheckpointReason)=>void):()=>void {
  return subscribe(checkpointHandlers,handler);
}
function checkpoint(reason:CheckpointReason):void {
  let failure:unknown;
  try {kv.batch(()=>{
    for(const handler of checkpointHandlers)try {handler(reason);}catch(error){failure??=error;}
  });} finally {kv.flush();}
  if(failure)throw failure;
}
export function lifecycle(): LifecycleState { return lifecycleState; }
export function onLifecycleChange(handler: LifecycleHandler): () => void {
  return subscribe(lifecycleHandlers, handler);
}
export function systemTheme(): SystemTheme { return themeState; }
export function onSystemThemeChange(handler: ThemeHandler): () => void {
  return subscribe(themeHandlers, handler);
}
export function onSystemBack(handler: BackHandler): () => void {
  backHandlers.push(handler);
  return () => {
    const index = backHandlers.lastIndexOf(handler);
    if (index >= 0) backHandlers.splice(index, 1);
  };
}

/** Publish app depth and optional root swipe policy. Omitting rootSwipeEnabled
 * restores normal swipe dismissal; this never changes hardware-back routing. */
export function setNavigationState(state: NavigationState): void {
  if (typeof state?.canGoBack !== "boolean") {
    throw new Error("PodJS: navigation state requires boolean canGoBack");
  }
  if (state.rootSwipeEnabled !== undefined && typeof state.rootSwipeEnabled !== "boolean") {
    throw new Error("PodJS: rootSwipeEnabled must be boolean");
  }
  navigationState = Object.freeze({ canGoBack: state.canGoBack, ...(state.rootSwipeEnabled === undefined ? {} : { rootSwipeEnabled: state.rootSwipeEnabled }) });
  host()?.emit(JSON.stringify({ t: "navigation", ...navigationState }));
}

export function navigationStateSnapshot(): NavigationState { return navigationState; }

export const haptics = Object.freeze({
  perform(kind: HapticKind): void {
    if (!["click", "success", "warning", "error"].includes(kind)) {
      throw new Error(`PodJS: unknown haptic kind ${kind}`);
    }
    host()?.emit(JSON.stringify({ t: "haptic", kind }));
  },
});

function validateKvKey(key: string): void {
  let bytes = 0;
  for (let i = 0; i < key.length; i++) {
    const code = key.charCodeAt(i);
    if (code < 0x80) bytes += 1;
    else if (code < 0x800) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff && i + 1 < key.length
      && key.charCodeAt(i + 1) >= 0xdc00 && key.charCodeAt(i + 1) <= 0xdfff) {
      bytes += 4; i++;
    } else bytes += 3;
  }
  if (!key || bytes > 128) {
    throw new Error("PodJS KV keys must contain 1..128 UTF-8 bytes");
  }
}

type KvMutation={op:'set';key:string;value:unknown}|{op:'delete';key:string};
let kvBatch:Map<string,string|undefined>|undefined;
function commitKv(staged:Map<string,string|undefined>):void {
  if(!staged.size)return;
  const mutations:KvMutation[]=[...staged].map(([key,value])=>value===undefined?{op:'delete',key}:{op:'set',key,value:JSON.parse(value)});
  const encoded=JSON.stringify(mutations);
  if(traceServiceWork('kv.batchWrite',()=>host()!.kvApply!(encoded),encoded.length)!==0)throw new Error('PodJS KV batch write failed');
  staged.clear();
}
export const kv = Object.freeze({
  /** Commit staged values now. Unbatched writes are already durable. A later
   * callback failure cannot roll back this explicit checkpoint boundary. */
  flush():void {if(kvBatch)commitKv(kvBatch);},
  /** Explicit synchronous transaction. Nested calls share staged reads/writes.
   * Old hosts execute the callback with legacy per-write persistence. */
  batch<T>(work:()=>T):T {
    const apply=host()?.kvApply;
    if(!apply||kvBatch)return work();
    const staged=new Map<string,string|undefined>();kvBatch=staged;
    let result:T;
    try {result=work();if(result&&typeof (result as {then?:unknown}).then==='function')throw new TypeError('PodJS KV batch callback must be synchronous');}
    finally {kvBatch=undefined;}
    commitKv(staged);
    return result;
  },
  get<T = unknown>(key: string): T | undefined {
    validateKvKey(key);
    return traceServiceWork("kv.get",()=>{const value = kvBatch?.has(key)?kvBatch.get(key):host()?.kvGet(key);
    return value === undefined ? undefined : JSON.parse(value) as T;});
  },
  /** SHA-256 of the authoritative canonical raw value, computed/cached by the
   * native store. Undefined also means an old host cannot provide a witness. */
  fingerprint(key:string):string|undefined {
    validateKvKey(key);if(kvBatch?.has(key))return undefined;
    return host()?.kvFingerprint?.(key);
  },
  set(key: string, value: unknown): void {
    validateKvKey(key);
    traceServiceWork("kv.set",()=>{const encoded = traceServiceWork("kv.encode",()=>JSON.stringify(value));
    if (encoded === undefined) throw new Error("PodJS KV values must be JSON serializable");
    if(kvBatch){kvBatch.set(key,encoded);return;}
    if (traceServiceWork("kv.write",()=>host()?.kvSet(key, encoded),encoded.length) !== 0) throw new Error(`PodJS KV write failed for ${key}`);});
  },
  delete(key: string): boolean {
    validateKvKey(key);
    if(kvBatch){const exists=kvBatch.has(key)?kvBatch.get(key)!==undefined:host()?.kvGet(key)!==undefined;kvBatch.set(key,undefined);return exists;}
    return host()?.kvDelete(key) === 0;
  },
  keys(): string[] {
    const original=JSON.parse(host()?.kvKeys() ?? "[]") as string[];
    if(!kvBatch)return original;
    const keys=new Set<string>(original);
    for(const [key,value]of kvBatch){if(value===undefined)keys.delete(key);else keys.add(key);}
    // Rust string ordering compares Unicode scalar values (UTF-8), while
    // JS default sort compares UTF-16 code units for supplementary characters.
    return [...keys].sort((a,b)=>{let i=0,j=0;while(i<a.length&&j<b.length){const left=a.codePointAt(i)!,right=b.codePointAt(j)!;if(left!==right)return left-right;i+=left>0xffff?2:1;j+=right>0xffff?2:1;}return a.length-b.length;});
  },
});

type PodEvent =
  | ({t:"input.scroll"} & RawScrollInput)
  | { t: "axis"; axis: RelativeAxisId; delta: number }
  | { t: "lifecycle"; state: LifecycleState }
  | { t: "checkpoint"; reason: "screen-off" }
  | { t: "theme"; theme: SystemTheme }
  | { t: "back" }
  | { t: "touchCancel" }
  | { t: "accessibility.action"; nodeId: number; action: string };

export function __pumpPodEvents(): void {
  const batch = host()?.takeEvents();
  if (!batch) return;
  const events = JSON.parse(batch) as PodEvent[];
  for (const event of events) {
    if (event.t === "touchCancel" || (event.t === "lifecycle" && event.state !== "active")) cancelActiveTouches();
    if ((event as {t:string}).t === 'diagnostic.serviceTrace') __setServiceTraceEnabled((event as unknown as {enabled?:boolean}).enabled===true);
    if(event.t === "lifecycle" && event.state !== "active")checkpoint(event.state);
    else if(event.t === "checkpoint" && event.reason === "screen-off")checkpoint("screen-off");
    for (const handler of hostEventHandlers) handler(event as unknown as Record<string, unknown>);
    if (event.t === "accessibility.action") {
      dispatchAccessibilityAction(event.nodeId, event.action);
    } else if (event.t === "axis" && Number.isInteger(event.delta) && event.delta !== 0) {
      for (const handler of axisHandlers.get(event.axis) ?? []) handler(event.delta);
    } else if (event.t === "lifecycle") {
      lifecycleState = event.state;
      for (const handler of lifecycleHandlers) handler(event.state);
    } else if (event.t === "theme") {
      themeState = event.theme;
      for (const handler of themeHandlers) handler(event.theme);
    } else if (event.t === "back") {
      for (let i = backHandlers.length - 1; i >= 0; i--) {
        if (backHandlers[i]() === true) break;
      }
    }
  }
}

// Host events are realm-scoped input, not component-scoped UI lifecycle.
// A module-level onFrame hook is cleared by mount() before the app component
// is created; service pumps deliberately survive that reset and run at the
// start of every mounted frame.
registerServicePump(__pumpPodEvents);

export interface FontMetrics {
  readonly cellWidth:number; readonly cellHeight:number; readonly baseline:number;
  readonly lineHeight:number; readonly rasterDensity:number;
}
/** Loaded atlas geometry in logical pixels; rasterDensity describes coverage resolution only. */
export function getFontMetrics(slot:number):FontMetrics|undefined {
  if(!Number.isInteger(slot)||slot<0||slot>255)return undefined;
  const raw=host()?.fontMetrics?.(slot);if(!raw)return undefined;
  try {
    const value=JSON.parse(raw) as FontMetrics;
    if(![value.cellWidth,value.cellHeight,value.baseline,value.lineHeight,value.rasterDensity].every(Number.isFinite)||value.cellWidth<=0||value.cellHeight<=0||value.lineHeight<=0||value.rasterDensity<=0||value.baseline<0)return undefined;
    return value;
  }catch{return undefined;}
}
