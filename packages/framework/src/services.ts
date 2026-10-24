import { bytesToBase64 as portableBytesToBase64 } from "../../../vendor/pocketjs/framework/src/bytes.ts";
import { __onHostEvent, hasCapability } from "./watch.ts";
import type { PodCapabilityId } from "./targets.ts";
import { platformMethods } from "./platform-contract.ts";

/** Native byte conversion avoids per-character guest work for file/stream payloads.
 * Older hosts retain the portable codec, including an empty result. */
export function bytesToBase64(bytes:Uint8Array):string {
  const native=(globalThis as {pod?:{encodeBase64?:(value:Uint8Array)=>string|null|undefined}}).pod?.encodeBase64;
  const encoded=native?.(bytes);
  return typeof encoded==='string'?encoded:portableBytesToBase64(bytes);
}

type NativeTextureFileHost = {uploadTextureFile?:(path:string,width:number,height:number,psm:number)=>number};
function nativeTextureFileHost():NativeTextureFileHost|undefined {
  return (globalThis as {pod?:NativeTextureFileHost}).pod;
}
/** Whether this host can consume a bounded decoder/raster RGBA file without
 * sending its pixels through JSON/Base64 and the JS heap. */
export function hasTextureFileUpload():boolean {
  return typeof nativeTextureFileHost()?.uploadTextureFile === "function";
}
/** Consume one host-generated RGBA file and return its native texture handle.
 * `null` means the optional fast path is unavailable; negative means rejected.
 * A supporting host owns cleanup as soon as this function is called. */
export function uploadTextureFile(path:string,width:number,height:number,psm:number):number|null {
  const upload=nativeTextureFileHost()?.uploadTextureFile;
  if(typeof upload!=="function")return null;
  const handle=upload(path,width,height,psm);
  return typeof handle==="number"&&Number.isInteger(handle)?handle:-1;
}

export type SqlValue = string | number | null;
export interface SqlStatement { sql: string; params?: readonly SqlValue[] }
export class HostServiceError extends Error {
  constructor(readonly code: string, message: string) { super(message); this.name = "HostServiceError"; }
}
export interface HostOperation<T> { result: Promise<T>; cancel(): void }
const pending = new Map<number, { resolve(value: unknown): void; reject(error: Error): void; status?:(value:unknown)=>void }>();
let nextId = 1;
/** Live promise/callback table, including the stats request if called by a guest. */
export const serviceResourceCounts=()=>({pending:pending.size});
const methods: Record<string, PodCapabilityId> = {
  ...platformMethods,
  "acoustic.gzip":"debug.acoustic", "acoustic.gunzip":"debug.acoustic",
  "acoustic.requestPermission":"debug.acoustic", "acoustic.pcm.open":"debug.acoustic", "acoustic.pcm.read":"debug.acoustic", "acoustic.pcm.write":"debug.acoustic", "acoustic.pcm.drain":"debug.acoustic", "acoustic.pcm.stop":"debug.acoustic",
  "acoustic.wifi.connect":"debug.acoustic", "acoustic.wifi.request":"debug.acoustic", "acoustic.wifi.close":"debug.acoustic",
  "app.finishTask": "host.lifecycle",
  "takeover.status": "host.takeover",
  "debug.external.subscribe":"host.lifecycle", "debug.external.unsubscribe":"host.lifecycle", "debug.external.reply":"host.lifecycle",
  "app.buildInfo": "host.lifecycle",
  "splash.hold": "host.lifecycle", "splash.release": "host.lifecycle",
  "cloud.enqueue":"host.lifecycle", "cloud.localTransfer":"host.lifecycle", "cloud.workState":"host.lifecycle",
  "cloud.schedule":"host.lifecycle", "cloud.cancel":"host.lifecycle", "cloud.acquire":"host.lifecycle", "cloud.release":"host.lifecycle",
  "secure.acquire":"data.secure", "secure.release":"data.secure",
  "debug.memoryStats": "host.lifecycle",
  "debug.performance.start": "host.lifecycle", "debug.performance.read": "host.lifecycle", "debug.performance.stop": "host.lifecycle",
  "app.activityState.read": "host.lifecycle", "app.activityState.write": "host.lifecycle",
  "rfcomm.requestPermission": "net.rfcomm", "rfcomm.status": "net.rfcomm", "rfcomm.listen": "net.rfcomm", "rfcomm.accept": "net.rfcomm", "rfcomm.close": "net.rfcomm",
  "tcp.listen":"net.tcp", "tcp.accept":"net.tcp", "tcp.close":"net.tcp", "tcp.addresses":"net.tcp",
  "discovery.networkSnapshot": "net.discovery", "discovery.networkSubscribe": "net.discovery", "discovery.networkUnsubscribe": "net.discovery",
  "provision.start": "net.ble.provision", "provision.receive": "net.ble.provision", "provision.check": "net.ble.provision", "provision.respond": "net.ble.provision", "provision.close": "net.ble.provision", "provision.requestPermission": "net.ble.provision",
  "discovery.mdns": "net.discovery", "discovery.status": "net.discovery", "discovery.requestPermission": "net.discovery", "discovery.bleReadCharacteristic": "net.discovery",
  "websocket.connect": "net.websocket", "websocket.readText": "net.websocket", "websocket.writeText": "net.websocket", "websocket.close": "net.websocket",
  "stream.read": "net.stream", "stream.write": "net.stream", "stream.close": "net.stream",
  "runtime.delay": "runtime.timer", "runtime.afterPaint":"runtime.timer",
  "display.state": "display.power", "display.keepAwake": "display.power",
  "audio.silenceState": "media.audio", "audio.silenceEnabled": "media.audio", "audio.silenceLease": "media.audio",
  "ui.toast": "system.toast",
  "http.download": "net.download",
  "browser.challengeCookies": "system.browser.challenge", "browser.acquireChallengeCookies": "system.browser.challenge",
  "browser.open": "system.browser",
  "browser.openInApp": "system.browser.inApp",
  "browser.updateTheme": "system.browser.inApp.theme",
  "browser.clearSiteData": "system.browser.siteData",
  "browser.availability": "system.browser.availability",
  "audio.start": "media.audio", "audio.pause": "media.audio", "audio.resume": "media.audio", "audio.setSpeed": "media.audio", "audio.stop": "media.audio", "audio.state": "media.audio",
  "audio.volumeState": "media.audio", "audio.setVolumeIndex": "media.audio",
  "tts.spectrum": "media.tts.spectrum",
  "tts.init": "media.tts", "tts.speak": "media.tts", "tts.stop": "media.tts", "tts.state": "media.tts",
  "video.cache.register":"media.video", "video.cache.window":"media.video", "video.cache.stats":"media.video", "video.cache.clear":"media.video",
  "video.prepare": "media.video", "video.play": "media.video", "video.pause": "media.video", "video.seek": "media.video", "video.stop": "media.video", "video.setBounds": "media.video", "video.state": "media.video",
  "video.frame.clear":"media.video", "video.frame.capture":"media.video", "video.frame.restore":"media.video", "video.captureBackdrop": "media.video", "video.setTransform": "media.video", "video.setVolume": "media.video", "video.setLoop": "media.video",
  "video.session.captureFrame":"media.video.sessions", "video.session.capabilities": "media.video.sessions", "video.session.prepare": "media.video.sessions", "video.session.play": "media.video.sessions", "video.session.pause": "media.video.sessions", "video.session.seek": "media.video.sessions", "video.session.stop": "media.video.sessions", "video.session.release": "media.video.sessions", "video.session.setBounds": "media.video.sessions", "video.session.setTransform": "media.video.sessions", "video.session.setVolume": "media.video.sessions", "video.session.setLoop": "media.video.sessions", "video.session.state": "media.video.sessions", "video.session.promote": "media.video.sessions", "video.session.transition": "media.video.sessions",
  "image.surface.open":"data.images", "image.surface.update":"data.images", "image.surface.state":"data.images", "image.surface.close":"data.images",
  "video.surface.open":"media.video", "video.surface.update":"media.video", "video.surface.state":"media.video", "video.surface.close":"media.video",
  "share.environment": "system.share", "share.text": "system.share",
  "image.decode": "data.images", "image.decodeRegion": "data.images", "image.release": "data.images", "browser.authorize": "system.browser.auth", "browser.authorizeWithStatus": "system.browser.auth.status",
  "sql.query": "data.sqlite", "sql.gzipJson": "data.sqlite", "sql.execute": "data.sqlite", "sql.transaction": "data.sqlite",
  "input.text": "input.text", "input.text.pending": "input.text.state", "input.text.dismiss": "input.text.state", "secure.get": "data.secure", "secure.set": "data.secure", "secure.delete": "data.secure", "secure.update": "data.secure",
  "input.inline.open": "input.text", "input.inline.close": "input.text", "input.inline.set": "input.text",
  "text.layout": "text.layout", "text.layoutBatch": "text.layout", "text.measure": "text.layout", "text.raster": "text.raster", "text.environment": "text.environment",
  "input.time": "input.time",
  "location.current": "device.location",
  "battery.percent": "device.battery",
  "crypto.verifyP256": "crypto.basic", "crypto.ecdhPublicKey": "crypto.basic", "crypto.ecdhUnwrap": "crypto.basic", "crypto.hkdfSha256": "crypto.basic",
  "crypto.largeAesGcmEncrypt": "crypto.basic", "crypto.largeAesGcmDecrypt": "crypto.basic", "crypto.gzip": "crypto.basic", "crypto.gunzip": "crypto.basic",
  "crypto.rsaOaepSha256": "crypto.basic", "crypto.random": "crypto.basic", "crypto.hash": "crypto.basic", "crypto.hmacSha256": "crypto.basic", "crypto.aesGcmEncrypt": "crypto.basic", "crypto.aesGcmDecrypt": "crypto.basic", "crypto.encrypt": "crypto.basic", "crypto.decrypt": "crypto.basic",
  "uri.parse": "data.uri", "file.htmlText": "data.files.chunks", "file.gzip": "data.files.chunks", "file.gzipBytes": "data.files.chunks", "file.gunzip": "data.files.chunks", "file.openText": "data.files.chunks", "file.readTextChunk": "data.files.chunks", "file.closeText": "data.files.chunks", "file.createTemp": "data.files.chunks", "file.hash": "data.files.chunks", "file.commit": "data.files.chunks", "file.read": "data.files.chunks", "file.write": "data.files.chunks", "file.list": "data.files.chunks", "file.touch": "data.files.chunks", "file.stat": "data.files.chunks", "file.delete": "data.files.chunks",
};
export interface ActivityState { saved:Record<string,unknown>; retained:Record<string,unknown> }
/** Android Activity lifecycle operations. Saved state follows the system Bundle;
 * retained state survives configuration recreation only. Neither is app KV. */
export const application = Object.freeze({
  buildInfo: ():Promise<{debugBuild:boolean}> => hostOperation<{debugBuild:boolean}>("app.buildInfo",{}).result,
  finishTask: (): Promise<void> => hostOperation<void>("app.finishTask", {}).result,
  activityState: Object.freeze({
    read: ():Promise<ActivityState> => hostOperation<ActivityState>("app.activityState.read",{}).result,
    write: (state:ActivityState):Promise<void> => hostOperation<void>("app.activityState.write",{saved:state.saved,retained:state.retained}).result,
  }),
});
function emit(value: unknown): void {
  const host = (globalThis as unknown as { pod?: { emit(line: string): void } }).pod;
  if (!host) throw new HostServiceError("unavailable", "PodJS host is unavailable");
  host.emit(JSON.stringify(value));
}
__onHostEvent(event => {
  if (typeof event.id !== "number") return;
  if (event.t === "browser.auth.status") {
    const request = pending.get(event.id);
    try { request?.status?.(event.value); } catch { /* Observers cannot break service settlement. */ }
    return;
  }
  if (event.t !== "service.result") return;
  const request = pending.get(event.id);
  if (!request) return;
  pending.delete(event.id);
  if (event.ok === true) request.resolve(event.value);
  else request.reject(new HostServiceError(String(event.code ?? "host_error"), String(event.message ?? "Host operation failed")));
});
/** Cancellation stops delivery; a mutation already committed by the host is not undone. */
export function hostOperation<T>(method: string, args: Record<string, unknown>): HostOperation<T> {
  return serviceOperation(method, args);
}
function serviceOperation<T>(method: string, args: Record<string, unknown>, status?:(value:unknown)=>void): HostOperation<T> {
  const id = nextId++;
  const result = new Promise<T>((resolve, reject) => {
    const capability = methods[method];
    if (!capability || !hasCapability(capability)) { reject(new HostServiceError("unsupported", `Unsupported host service: ${method}`)); return; }
    if (pending.size >= 64) { reject(new HostServiceError("busy", "Too many pending host operations")); return; }
    pending.set(id, { resolve: value => resolve(value as T), reject, status });
    try { emit({ t: "service.request", version: 1, id, method, args }); }
    catch (error) { pending.delete(id); reject(error); }
  });
  return { result, cancel() {
    const request = pending.get(id);
    if (!request) return;
    pending.delete(id);
    try { request.status?.({type:"cancelled"}); } catch { /* Cancellation must still settle. */ }
    request.reject(new HostServiceError("cancelled", "Operation cancelled"));
    emit({ t: "service.cancel", version: 1, id });
  } };
}
export interface DisplayPowerState {interactive:boolean;resumed:boolean;applied:boolean;windowKeepScreenOn:boolean;owners:string[]}
export const displayPower={
  state:()=>hostOperation<DisplayPowerState>("display.state",{}).result,
  keepAwake:(owner:string,active:boolean)=>hostOperation<DisplayPowerState>("display.keepAwake",{owner,active}).result,
  onChange:(listener:(state:DisplayPowerState)=>void)=>__onHostEvent(event=>{if(hasCapability("display.power")&&event.t==='display.state'&&event.value&&typeof event.value==='object')listener(event.value as DisplayPowerState);}),
};
export interface SilenceState {enabled:boolean;playing:boolean;owners:string[]}
export const silencePlayback={
  state:()=>hostOperation<SilenceState>("audio.silenceState",{}).result,
  setEnabled:(enabled:boolean)=>hostOperation<SilenceState>("audio.silenceEnabled",{enabled}).result,
  lease:(owner:string,active:boolean)=>hostOperation<SilenceState>("audio.silenceLease",{owner,active}).result,
};

/** traceLabel is a code-authored diagnostic call-site name, never user input.
 * Hosts may ignore it; default operation/result semantics are unchanged. */
export const sqlite = {
  /** Code-authored SELECT/fields. Re-encodes on the SQL worker; returns no text. */
  gzipJson(args:{sql:string;params?:readonly SqlValue[];keyColumn:string;expectedHashColumn?:string;fields:readonly {name:string;column:string;omitNull?:boolean;filePrefix?:string;filePattern?:string;directory?:string}[];maxRows?:number;maxInputBytes?:number;chunkSize?:number}) {
    if(args.fields.some(f=>f.filePrefix)&&!hasCapability('data.files.chunks'))throw new Error('Missing capability: data.files.chunks');
    return hostOperation<{key:string;oversized?:boolean;sha256:string;size:number;chunkSize:number;chunkHashes:string[]}[]>('sql.gzipJson',{...args,format:'android-json-gzip-v1'}).result;
  },
  query(sql: string, params: readonly SqlValue[] = [], traceLabel?:string): Promise<Record<string, unknown>[]> {
    return hostOperation<Record<string, unknown>[]>("sql.query", { sql, params, ...(traceLabel?{traceLabel}:{}) }).result;
  },
  async execute(sql: string, params: readonly SqlValue[] = [], traceLabel?:string): Promise<void> {
    await hostOperation("sql.execute", { sql, params, ...(traceLabel?{traceLabel}:{}) }).result;
  },
  /** Committed changes() receipt for one transactional statement. */
  async executeChanges(sql:string,params:readonly SqlValue[]=[],traceLabel?:string):Promise<number> {
    const counts=await hostOperation<number[]>("sql.transaction",{statements:[{sql,params}],reportChanges:true,...(traceLabel?{traceLabel}:{})}).result;
    if(counts.length!==1||!Number.isSafeInteger(counts[0])||counts[0]!<0)throw Error('Invalid SQL change receipt');
    return counts[0]!;
  },
  async transaction(statements: readonly SqlStatement[]): Promise<void> {
    await hostOperation("sql.transaction", { statements }).result;
  },
  /** SQLite changes() after each statement, returned only after commit. */
  transactionChanges(statements: readonly SqlStatement[]): Promise<number[]> {
    return hostOperation<number[]>("sql.transaction", { statements, reportChanges: true }).result;
  },
};
export const textInput = {
  /** Stable owner key opts a non-secret draft into configuration-only recovery. */
  pending(restorationKey:string):HostOperation<boolean> {
    if(typeof restorationKey!=="string" || !/^[a-zA-Z0-9_.:-]{1,160}$/.test(restorationKey)) throw new HostServiceError("invalid_argument","Invalid input restoration key");
    return hostOperation("input.text.pending",{restorationKey});
  },
  dismiss(restorationKey:string):HostOperation<void> {
    if(typeof restorationKey!=="string" || !/^[a-zA-Z0-9_.:-]{1,160}$/.test(restorationKey)) throw new HostServiceError("invalid_argument","Invalid input restoration key");
    return hostOperation("input.text.dismiss",{restorationKey});
  },
  prompt(title: string, value = "", options?:{restorationKey:string}): HostOperation<string | null> {
    if(options && (typeof options.restorationKey!=="string" || !/^[a-zA-Z0-9_.:-]{1,160}$/.test(options.restorationKey) || Object.keys(options).some(k=>k!=="restorationKey"))) throw new HostServiceError("invalid_argument","Invalid input restoration key");
    if(options && !hasCapability("input.text.state")) return {result:Promise.reject(new HostServiceError("unsupported","input.text.state is unavailable")),cancel:()=>{}};
    return hostOperation("input.text", { title, value,...options });
  },
  async openInline(value:string,bounds:{x:number;y:number;width:number;height:number},onChange:(value:string)=>void,onSubmit?:()=>void,options:{hint?:string;fontSizeSp?:number;fontAsset?:string;fontWeight?:number;includeFontPadding?:boolean;inputType?:"text"|"uri";multiline?:boolean;keepExisting?:boolean;selectionStart?:number;textAlign?:"start"|"center"|"end";horizontalPaddingDp?:number;verticalPaddingDp?:number;autoFocus?:boolean;document?:{id:string;role:"title"|"body";viewportHeight:number;viewportOverhangDp?:number;metadata?:string;unfocusedCaretColor?:string}}={}):Promise<{close():Promise<void>;setValue(value:string):Promise<void>}> {
    let session:number|undefined,closed=false;
    const early:Record<string,unknown>[]=[];
    const deliver=(event:Record<string,unknown>)=>{
      if(closed||event.session!==session)return;
      if(event.t==="input.inline.change"&&typeof event.value==="string")onChange(event.value);
      if(event.t==="input.inline.submit")onSubmit?.();
    };
    const unsubscribe=__onHostEvent(event=>{
      if(session===undefined){if(early.length<32&&event.t?.toString().startsWith("input.inline."))early.push(event);return;}
      deliver(event);
    });
    try{session=await hostOperation<number>("input.inline.open",{...bounds,value,hint:options.hint??"搜索",fontSizeSp:options.fontSizeSp??17,...(options.fontAsset===undefined?{}:{fontAsset:options.fontAsset}),...(options.fontWeight===undefined?{}:{fontWeight:Math.max(100,Math.min(900,Math.trunc(options.fontWeight)))}),...(options.includeFontPadding===undefined?{}:{includeFontPadding:options.includeFontPadding}),inputType:options.inputType??"text",multiline:options.multiline??false,keepExisting:options.keepExisting??false,...(options.selectionStart===undefined?{}:{selectionStart:Math.max(0,Math.min(value.length,Math.trunc(options.selectionStart)))}),...(options.textAlign===undefined?{}:{textAlign:options.textAlign}),...(options.horizontalPaddingDp===undefined?{}:{horizontalPaddingDp:Math.max(0,Math.min(64,options.horizontalPaddingDp))}),...(options.verticalPaddingDp===undefined?{}:{verticalPaddingDp:Math.max(0,Math.min(64,options.verticalPaddingDp))}),...(options.autoFocus===undefined?{}:{autoFocus:options.autoFocus}),...(options.document===undefined?{}:{document:options.document})}).result;}
    catch(error){closed=true;unsubscribe();throw error;}
    for(const event of early)deliver(event);
    return {async setValue(value:string){
      if(closed)return;
      await hostOperation<void>("input.inline.set",{session,value}).result;
    },async close(){
      if(closed)return;
      closed=true;unsubscribe();
      await hostOperation<void>("input.inline.close",{session}).result;
    }};
  },
};
/** A local wall-clock time in minutes after midnight; dismissing returns null. */
export const timeInput = {
  prompt(minutes: number): HostOperation<number | null> {
    if (!Number.isInteger(minutes) || minutes < 0 || minutes > 1439) throw new HostServiceError("invalid_argument", "Time must be integer minutes from 0 to 1439");
    return hostOperation("input.time", { minutes });
  },
};
export interface LocationFix { latitude: number; longitude: number; timestamp: number }
export const location = {
  /** Requests foreground permission when needed. No background tracking is started. */
  current(): HostOperation<LocationFix | null> { return hostOperation("location.current", {}); },
};

export const secureStorage = {
  /** Android app-private cross-guest/process try-lease; release only after acquired=true.
   * The host also releases all owned leases when this guest closes. */
  async acquire(key:string):Promise<boolean> { return (await hostOperation<{acquired:boolean}>("secure.acquire",{key}).result).acquired; },
  async release(key:string):Promise<void> { await hostOperation("secure.release",{key}).result; },
  /** Publish a bounded set of updates/deletions in one encrypted store transaction. */
  async update(values:Record<string,string|null>):Promise<void> { await hostOperation("secure.update",{values}).result; },
  async get(key: string): Promise<string | undefined> { const value = await hostOperation<{exists:boolean;value?:string}>("secure.get",{key}).result; return value.exists ? value.value : undefined; },
  async set(key: string,value: string): Promise<void> { await hostOperation("secure.set",{key,value}).result; },
  async delete(key: string): Promise<void> { await hostOperation("secure.delete",{key}).result; },
};
/** Android STREAM_MUSIC indices, shared by audio, video and speech playback. */
export interface MediaVolumeState { ok:boolean; min:number; max:number; current:number; fixed:boolean }
export const mediaVolume = {
  state(): Promise<MediaVolumeState> { return hostOperation<MediaVolumeState>("audio.volumeState", {}).result; },
  setIndex(index:number): Promise<MediaVolumeState> {
    if (!Number.isInteger(index) || index < 0) throw new HostServiceError("invalid_argument", "Volume index must be a nonnegative integer");
    return hostOperation<MediaVolumeState>("audio.setVolumeIndex", {index}).result;
  },
};
export const audio = {
  play(url: string): HostOperation<unknown> { return hostOperation("audio.start",{url}); },
  pause(): Promise<unknown> { return hostOperation("audio.pause",{}).result; },
  resume(): Promise<unknown> { return hostOperation("audio.resume",{}).result; },
  setSpeed(speed:number): Promise<unknown> { if(!Number.isFinite(speed)||speed<.5||speed>2)throw new HostServiceError("invalid_argument","Audio speed must be between 0.5 and 2");return hostOperation("audio.setSpeed",{speed}).result; },
  stop(): Promise<unknown> { return hostOperation("audio.stop",{}).result; },
  state(): Promise<{state:string;positionMs:number;durationMs:number}> { return hostOperation<{state:string;positionMs:number;durationMs:number}>("audio.state",{}).result; },
};
export interface SpeechState { ok:boolean; initialized:boolean; state:string; language?:string; voice?:string; engine?:string; utteranceId?:string }
export interface SpeechProgressEvent { type: "start" | "range" | "done" | "error" | "stop"; utteranceId:string; start?:number; end?:number; elapsedMs:number }
/** Synthesis-time PCM levels, independent of speaker volume; not playback-clock synchronized.
 * Engines without PCM callbacks produce no frames. Reset has empty levels; frame has five [0,1] bands. */
export interface SpeechSpectrumEvent {
  type: "frame" | "reset"; source: "synthesis-pcm"; utteranceId: string;
  sampleRateHz: number; levels: readonly number[];
}
export const speech = {
  /** Opt in explicitly; disabled by default. Stop (also used for pause) clears the spectrum. */
  setSpectrumEnabled(enabled:boolean): Promise<{enabled:boolean}> {
    if(typeof enabled!=="boolean")throw new HostServiceError("invalid_argument","Spectrum enabled must be boolean");
    return hostOperation<{enabled:boolean}>("tts.spectrum",{enabled}).result;
  },
  /** Subscribe before enabling. Unsubscribing removes this listener; disable explicitly when no longer needed. */
  onSpectrum(listener:(event:SpeechSpectrumEvent)=>void):()=>void {
    if(!hasCapability("media.tts.spectrum"))throw new HostServiceError("unsupported","Unsupported capability: media.tts.spectrum");
    return __onHostEvent(event=>{
      if(!hasCapability("media.tts.spectrum")||event.t!=="speech.spectrum"||event.source!=="synthesis-pcm"||typeof event.utteranceId!=="string")return;
      if(event.type!=="frame"&&event.type!=="reset")return;
      if(typeof event.sampleRateHz!=="number"||!Number.isInteger(event.sampleRateHz)||event.sampleRateHz<0||(event.type==="frame"&&event.sampleRateHz===0))return;
      if(!Array.isArray(event.levels)||event.levels.length!==(event.type==="frame"?5:0)||event.levels.some(value=>typeof value!=="number"||!Number.isFinite(value)||value<0||value>1))return;
      listener({type:event.type,source:"synthesis-pcm",utteranceId:event.utteranceId,sampleRateHz:event.sampleRateHz,levels:[...event.levels]});
    });
  },
  onProgress(listener:(event:SpeechProgressEvent)=>void):()=>void {
    return __onHostEvent(event=>{
      if(event.t!=="speech.progress"||typeof event.utteranceId!=="string"||typeof event.type!=="string"||!["start","range","done","error","stop"].includes(event.type))return;
      if(event.type==="range"&&(!Number.isInteger(event.start)||!Number.isInteger(event.end)))return;
      listener({type:event.type as SpeechProgressEvent["type"],utteranceId:event.utteranceId,start:event.start as number|undefined,end:event.end as number|undefined,elapsedMs:typeof event.elapsedMs==="number"&&Number.isFinite(event.elapsedMs)?Math.max(0,event.elapsedMs):0});
    });
  },
  initialize(language="zh-CN"): Promise<SpeechState> { return hostOperation<SpeechState>("tts.init",{language}).result; },
  /** Prefer installed offline voices in order, then a supported language. An empty speak language preserves the selected voice. */
  initializeLocal(preferredLanguages:readonly string[]=["zh-CN","zh","en"]): Promise<SpeechState> {
    if(!preferredLanguages.length||preferredLanguages.some(language=>typeof language!=="string"||!language.trim()))throw new HostServiceError("invalid_argument","At least one nonempty language is required");
    return hostOperation<SpeechState>("tts.init",{preferredOfflineLanguages:preferredLanguages}).result;
  },
  state(): Promise<SpeechState> { return hostOperation<SpeechState>("tts.state",{}).result; },
  speak(text: string,language="zh-CN",rate=1,utteranceId?:string): HostOperation<SpeechState> { return hostOperation("tts.speak",{text,language,rate,...(utteranceId?{utteranceId}:{})}); },
  stop(): Promise<unknown> { return hostOperation("tts.stop",{}).result; },
};
export interface VideoBounds { x:number; y:number; width:number; height:number; translateX?:number; translateY?:number; visible?:boolean; roundClip?:{cx:number;cy:number;radius:number} }
export interface VideoPrepare { url?:string; videoUrl?:string; audioUrl?:string; type?:string; videoMime?:string; videoWidth?:number; videoHeight?:number; headers?:Record<string,string>; bounds:VideoBounds; autoplay?:boolean; muted?:boolean; loop?:boolean; backgroundPlayback?:boolean }
export interface VideoFrameSource {url?:string;videoUrl?:string;audioUrl?:string;headers?:Record<string,string>}
export interface VideoFrame {available:true;kind:"actual_frame";path:string;width:number;height:number;positionMs:number;capturedAt:number;capturedGeneration:number}
export interface VideoFrameCapture {key:string;source:VideoFrameSource;sourceGeneration:number}
export interface VideoState { sourceGeneration?:number; /** Completed native repeats since prepare; seeks do not increment it. */ completedLoops?:number; loop?:boolean; hasRenderedFirstFrame?:boolean; errorCode?:string; ok:boolean; state:string; positionMs:number; durationMs:number; width:number; height:number; volume:number; volumeMax:number }
export const video = {
  /** Capture only a rendered frame from the exact still-current source generation. */
  clearFrames():Promise<{removedFiles:number}> {return hostOperation<{removedFiles:number}>("video.frame.clear",{}).result;},
  captureFrame(options:VideoFrameCapture):Promise<VideoFrame> {return hostOperation<VideoFrame>("video.frame.capture",options as unknown as Record<string,unknown>).result;},
  /** Image-only recovery: exact source/credential binding; never authorizes playback. */
  restoreFrame(key:string,source:VideoFrameSource):Promise<VideoFrame|{available:false}> {return hostOperation<VideoFrame|{available:false}>("video.frame.restore",{key,source}).result;},
  prepare(options: VideoPrepare): HostOperation<VideoState> { return hostOperation<VideoState>("video.prepare", options as unknown as Record<string,unknown>); },
  play(): Promise<VideoState> { return hostOperation<VideoState>("video.play", {}).result; },
  pause(): Promise<VideoState> { return hostOperation<VideoState>("video.pause", {}).result; },
  seek(positionMs:number): Promise<VideoState> { return hostOperation<VideoState>("video.seek", {positionMs}).result; },
  stop(): Promise<VideoState> { return hostOperation<VideoState>("video.stop", {}).result; },
  setBounds(bounds:VideoBounds): Promise<VideoState> { return hostOperation<VideoState>("video.setBounds", bounds as unknown as Record<string,unknown>).result; },
  state(): Promise<VideoState> { return hostOperation<VideoState>("video.state", {}).result; },
  captureBackdrop(): Promise<{ok:boolean}> { return hostOperation<{ok:boolean}>("video.captureBackdrop", {}).result; },
  setTransform(transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}): Promise<VideoState> { return hostOperation<VideoState>("video.setTransform", transform as Record<string,unknown>).result; },
  /** Change repeat mode on the current player without preparing or seeking. */
  setLoop(loop:boolean): Promise<VideoState> { return hostOperation<VideoState>("video.setLoop", {loop}).result; },
  setVolume(volume:number): Promise<VideoState> { return hostOperation<VideoState>("video.setVolume", {volume}).result; },
};

export interface VideoSessionCapabilities {
  capability:"media.video.sessions"; maxSessions:number; decoderLimit:number; decoderInUse:number;
  targetBufferBytesPerSession:number; targetBufferBytesTotal:number; prewarmTimeoutMs:number;
  prewarmMode:"hidden_first_frame"; surfacePromotion:boolean; verticalTransition:boolean;
  surfaceComposition:"android_texture_view"; backgroundPlayback:false;
  decoderMime?:string|null; decoderWidth?:number; decoderHeight?:number; decoderName?:string|null;
  decoderAdvertisedInstances?:number; decoderSizeSupported?:boolean; decoderRuntimeDowngraded?:boolean;
}
export interface VideoSessionPrepare extends Omit<VideoPrepare,"backgroundPlayback"> {
  sessionId:string;
  /** Decode silently to a retained hidden first frame. Prewarm never requests audio focus. */
  prewarm?:boolean;
}
export interface VideoSessionState {
  sourceGeneration?:number;
  completedLoops?:number; loop?:boolean;
  errorCode?:string;
  ok:boolean; sessionId:string; state:string; positionMs:number; durationMs:number;
  width:number; height:number; muted:boolean; volume:number; prewarming:boolean;
  presented:boolean; hasRenderedFirstFrame:boolean; targetBufferBytes:number;
}
export interface VideoSessionTransition {
  fromSessionId:string; toSessionId:string; progress:number; direction:"up"|"down"; bounds?:VideoBounds;
}
export interface VideoSessionTransitionState {
  fromSessionId:string; toSessionId:string; progress:number; direction:"up"|"down";
  fromOffsetYPx:number; toOffsetYPx:number; composited:true;
}
/** Two retained Android TextureView sessions for immersive pager playback.
 * The capability is intentionally separate from legacy media.video so an old
 * one-player host rejects every request before transport.
 */
export const videoSessions = {
  captureFrame(sessionId:string,options:VideoFrameCapture):Promise<VideoFrame> {return hostOperation<VideoFrame>("video.session.captureFrame",{sessionId,...options}).result;},
  capabilities(profile:Pick<VideoPrepare,"videoMime"|"videoWidth"|"videoHeight">={}):Promise<VideoSessionCapabilities> { return hostOperation<VideoSessionCapabilities>("video.session.capabilities",profile as Record<string,unknown>).result; },
  prepare(options:VideoSessionPrepare):HostOperation<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.prepare",options as unknown as Record<string,unknown>); },
  play(sessionId:string):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.play",{sessionId}).result; },
  pause(sessionId:string):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.pause",{sessionId}).result; },
  seek(sessionId:string,positionMs:number):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.seek",{sessionId,positionMs}).result; },
  stop(sessionId:string):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.stop",{sessionId}).result; },
  release(sessionId:string):Promise<{sessionId:string;released:boolean}> { return hostOperation<{sessionId:string;released:boolean}>("video.session.release",{sessionId}).result; },
  setBounds(sessionId:string,bounds:VideoBounds):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.setBounds",{sessionId,bounds}).result; },
  state(sessionId:string):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.state",{sessionId}).result; },
  setTransform(sessionId:string,transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.setTransform",{sessionId,...transform}).result; },
  setLoop(sessionId:string,loop:boolean):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.setLoop",{sessionId,loop}).result; },
  setVolume(sessionId:string,volume:number):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.setVolume",{sessionId,volume}).result; },
  /** Reveals the retained target first frame before hiding the replaced session. */
  promote(sessionId:string,options:{replaceSessionId?:string;bounds?:VideoBounds;autoplay?:boolean}={}):Promise<VideoSessionState> { return hostOperation<VideoSessionState>("video.session.promote",{sessionId,...options}).result; },
  /** Atomically translates both native surfaces. Call promote after progress reaches one. */
  transition(options:VideoSessionTransition):Promise<VideoSessionTransitionState> { return hostOperation<VideoSessionTransitionState>("video.session.transition",options as unknown as Record<string,unknown>).result; },
};

export interface DownloadResult { status:number; headers:Record<string,string>; url:string; path:string; size:number }
export const http = {
  download(url:string,path:string,maxBytes=16*1024*1024): HostOperation<DownloadResult> { return hostOperation("http.download",{url,path,maxBytes}); },
};
function browserUrl(url: string): string {
  if (typeof url !== "string" || url.length === 0 || url.length > 8192) throw new HostServiceError("invalid_argument", "Browser URL must be a non-empty HTTP or HTTPS URL");
  const match = /^(https?):\/\/([^/\\?#:]+)(?::\d{1,5})?(?:[/?#].*)?$/i.exec(url);
  if (!match || /[\u0000-\u0020]/.test(url)) throw new HostServiceError("invalid_argument", "Browser URL must be a valid HTTP or HTTPS URL");
  return url;
}
/** Opt-in embedded browser. The promise resolves after opening, not after closing.
 * Omit options to retain the external-browser contract. Use updateTheme for an open session;
 * omitted font settings preserve site fonts, as in native WatchRSS.
 */
export interface BrowserOpenOptions {
  mode: "inApp";
  scaleMode?: "standard" | "expanded" | "shrunk";
  /** Save owned current GET URL, viewport mode, zoom and scroll; excludes DOM/history/forms. */
  restoreActivity?: boolean;
  theme?: { dark: boolean; backgroundColor?: string; textZoom?: number; fontFamily?: "sans-serif" | "serif" | "monospace" };
}
function inAppBrowserArgs(url: string, options: BrowserOpenOptions): Record<string, unknown> {
  const invalid = () => { throw new HostServiceError("invalid_argument", "Invalid embedded browser URL or options"); };
  if (typeof url !== "string" || !url.trim() || url.length > 8192 || /[\u0000-\u001f\u007f]/.test(url)) invalid();
  url = url.trim();
  if (!url.startsWith("/") && !/^file:\/\/\//i.test(url)) browserUrl(url);
  if (!options || options.mode !== "inApp" || Object.keys(options).some(k => !["mode", "scaleMode", "theme", "restoreActivity"].includes(k))) invalid();
  if (options.scaleMode !== undefined && !["standard", "expanded", "shrunk"].includes(options.scaleMode)) invalid();
  if(options.restoreActivity !== undefined && typeof options.restoreActivity !== "boolean") invalid();
  const theme = options.theme;
  if (theme !== undefined) {
    if (!theme || typeof theme !== "object" || typeof theme.dark !== "boolean" || Object.keys(theme).some(k => !["dark", "backgroundColor", "textZoom", "fontFamily"].includes(k))) invalid();
    if (theme.backgroundColor !== undefined && !/^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(theme.backgroundColor)) invalid();
    if (theme.textZoom !== undefined && (!Number.isInteger(theme.textZoom) || theme.textZoom < 50 || theme.textZoom > 200)) invalid();
    if (theme.fontFamily !== undefined && !["sans-serif", "serif", "monospace"].includes(theme.fontFamily)) invalid();
  }
  return {url, ...options};
}
export interface BrowserAvailability {
  status: "available" | "no_provider" | "initialization_failed";
  provider?: string;
  message?: string;
}
export interface BrowserSiteDataResult {
  origin: string;
  cookies: { status: "root_cleared" | "remaining" | "failed"; remainingNames?: string[]; message?: string };
  webStorage: { status: "deletion_requested" | "failed"; message?: string };
}
export const browser = {
  /** Update the owned live browser without navigation, reload or changing its viewport. */
  updateTheme(theme: NonNullable<BrowserOpenOptions["theme"]>): HostOperation<{updated:boolean}> {
    inAppBrowserArgs("https://validation.invalid", {mode:"inApp",theme});
    return hostOperation("browser.updateTheme", {theme});
  },
  /** Probes the embedded Android WebView, not the external browser handler. */
  availability(): HostOperation<BrowserAvailability> { return hostOperation("browser.availability", {}); },
  /** Root-path cookies only, matching Android WatchRSS. Cookie domains are shared
   * across ports and subdomains; parent-domain expiry requires listing that parent.
   * WebStorage has no completion callback: deletion_requested is not verification.
   * Requires bare HTTPS origins (no path, query, credentials or fragment).
   */
  clearSiteData(args: { origins: readonly string[] }): HostOperation<BrowserSiteDataResult[]> {
    return hostOperation("browser.clearSiteData", { origins: args.origins });
  },
  open(url: string, options?: BrowserOpenOptions): HostOperation<void> {
    if(options?.restoreActivity && !hasCapability("system.browser.inApp.state")) return {result:Promise.reject(new HostServiceError("unsupported","system.browser.inApp.state is unavailable")),cancel:()=>{}};
    return options === undefined
      ? hostOperation<void>("browser.open", {url: browserUrl(url)})
      : hostOperation<void>("browser.openInApp", inAppBrowserArgs(url, options));
  },
};
export interface SiteChallengeOptions {
  url:string;
  /** Must also be used for the ordinary HTTP request; tokens may bind to it. */
  userAgent:string;
  /** Only these cookies leave CookieManager, never unrelated account cookies. */
  cookieNames:readonly string[];
  /** Queue plus WebView deadline, default 60s, maximum 120s. */
  timeoutMs?:number;
}
export const browserChallenge = {
  cookies:(args:SiteChallengeOptions):HostOperation<{cookie:string;origin:string}> => hostOperation("browser.challengeCookies",{...args}),
  acquire:(args:SiteChallengeOptions):HostOperation<{cookie:string;origin:string}> => hostOperation("browser.acquireChallengeCookies",{...args}),
};
export type BrowserAuthStatus =
  | {type:"qr";kind:"login"|"secondVerify";imageBase64:string;mime:"image/png"|"image/jpeg"}
  | {type:"qrCleared"}
  | {type:"pageState";loading:boolean;loginPanelVisible?:boolean}
  | {type:"verifying";active:boolean}
  | {type:"cancelled"};
import {BrowserQrWait,type BrowserQrWaitOptions,type BrowserQrWaitStatus} from './browser-qr-wait.ts';
export interface BrowserAuthOptions {
  url:string; cookieOrigin:string; requiredCookieNames:readonly string[]; userAgent?:string;
  /** Legacy: data-image string/null. With onStatus: also accepts an atomic status snapshot. */
  qrExtractionScript?:string;
  onStatus?:(status:BrowserAuthStatus)=>void;
  /** Optional waiting state for QR extraction. Hosts/UI may render it; never tied to page loading. */
  qrWait?:BrowserQrWaitOptions;
  onQrWait?:(status:BrowserQrWaitStatus)=>void;
  /** Opt in to completion after an observed login panel disappears, verification ends,
   * and required cookies exist. Older hosts retain their manual Done action. */
  completeOnLoginPanelHidden?:boolean;
}
function isBrowserAuthStatus(value:unknown):value is BrowserAuthStatus {
  if (!value || typeof value!=="object") return false;
  const v=value as Record<string,unknown>;
  switch(v.type) {
    case "qr": return (v.kind==="login"&&v.mime==="image/png"||v.kind==="secondVerify"&&v.mime==="image/jpeg")
      && typeof v.imageBase64==="string" && v.imageBase64.length>0 && v.imageBase64.length<=256*1024
      && v.imageBase64.length%4===0 && /^[A-Za-z0-9+/]+={0,2}$/.test(v.imageBase64);
    case "qrCleared": case "cancelled": return true;
    case "pageState": return typeof v.loading==="boolean" && (v.loginPanelVisible===undefined||typeof v.loginPanelVisible==="boolean");
    case "verifying": return typeof v.active==="boolean";
    default: return false;
  }
}
export const browserAuth = {
  authorize(args:BrowserAuthOptions): HostOperation<{cookie:string;origin:string}> {
    const {onStatus,onQrWait,...wire}=args;
    if(onStatus===undefined&&onQrWait===undefined&&args.qrWait===undefined)return hostOperation("browser.authorize",wire);
    if(onStatus!==undefined&&typeof onStatus!=="function")throw new HostServiceError("invalid_argument","onStatus must be a function");
    if(onQrWait!==undefined&&typeof onQrWait!=="function")throw new HostServiceError("invalid_argument","onQrWait must be a function");
    const wait=new BrowserQrWait(args.qrWait);
    let cancelled=false;
    const publish=(status:BrowserQrWaitStatus)=>{try{onQrWait?.(status);}catch{ /* Observers must not change authorization settlement. */ }};
    publish(wait.status);
    const operation=serviceOperation<{cookie:string;origin:string}>("browser.authorizeWithStatus",wire,value=>{
      if(cancelled||!hasCapability("system.browser.auth.status")||!isBrowserAuthStatus(value))return;
      if(value.type==="cancelled")cancelled=true;
      publish(wait.update(value));
      onStatus?.(value);
    });
    const finish=()=>{cancelled=true;publish(wait.finish());};
    return {result:operation.result.then(value=>{finish();return value;},error=>{finish();throw error;}),cancel:()=>{operation.cancel();finish();}};
  },
};
/** Read-only snapshot of the native Douyin selectors. No cookie-based auto-completion. */
export const DOUYIN_AUTH_STATUS_SCRIPT = `(function(){
  var verify=document.getElementById('uc-second-verify');
  var verifying=!!verify&&verify.tagName.toLowerCase()==='div';
  var panel=!!document.querySelector('article#douyin_login_comp_flat_panel,#douyin-login-new-id,div.douyin_login_new_class,#login-panel-new');
  if(!panel)panel=Array.from(document.querySelectorAll('div')).some(function(d){return (d.textContent||'').trim().startsWith('登录后免费');});
  var qr=null;
  if(!verifying){
    var images=Array.from(document.querySelectorAll('img[aria-label="二维码"]'));
    ['image/jpeg','image/png'].some(function(mime){
      var prefix='data:'+mime+';base64,';
      var img=images.find(function(i){return (i.getAttribute('src')||'').startsWith(prefix);});
      if(!img)return false;
      qr={kind:mime==='image/jpeg'?'secondVerify':'login',mime:mime,imageBase64:img.getAttribute('src').slice(prefix.length)};
      return true;
    });
  }
  return {qr:qr,verifying:verifying,loading:document.readyState!=='complete',loginPanelVisible:panel};
})()`;
export interface ImageDecodeResult {path:string;width:number;height:number;sourceWidth?:number;sourceHeight?:number;contentWidth?:number;contentHeight?:number;trace?:{decoderOpenMs:number;decodeAndCropMs:number;padMs:number;writeRgbaMs:number}}
export interface ImageDecodeOptions {maxDimension?:number;applyExif?:boolean;rgb565?:boolean}
export interface ImageRegionOptions {x:number;y:number;width:number;height:number;sampleSize?:number;applyExif?:boolean;rgb565?:boolean;trace?:boolean}
export const images = {
  /** Promise compatibility entry; cancellation-aware consumers use decodeOperation. */
  decode(path:string,maxDimension=512):Promise<ImageDecodeResult> {return images.decodeOperation(path,{maxDimension}).result;},
  decodeOperation(path:string,options:ImageDecodeOptions={}):HostOperation<ImageDecodeResult> {
    return hostOperation<ImageDecodeResult>('image.decode',{path,...options,maxDimension:options.maxDimension??512});
  },
  decodeRegionOperation(path:string,options:ImageRegionOptions):HostOperation<ImageDecodeResult> {
    return hostOperation<ImageDecodeResult>('image.decodeRegion',{path,...options});
  },
};
export const uri = {
  /** java.net.URI components on Android; this parses without URL normalization. */
  parse(value:string,syntax?:"java"|"android"):Promise<{valid:boolean;scheme:string|null;host:string|null;rawPath:string|null;rawQuery:string|null}> {
    return hostOperation<{valid:boolean;scheme:string|null;host:string|null;rawPath:string|null;rawQuery:string|null}>("uri.parse",{value,...(syntax?{syntax}:{})}).result;
  },
};
export const sharing = {
  environment():Promise<{sdkInt:number;wearOs:boolean}> {return hostOperation<{sdkInt:number;wearOs:boolean}>("share.environment",{}).result;},
  /** Opens the chooser only. Selecting a recipient belongs to the user. */
  text(text:string,chooserTitle?:string):HostOperation<void>{return hostOperation<void>("share.text",{text,...(chooserTitle===undefined?{}:{chooserTitle})});},
};

export const fileChunks = {
  /** Extract visible HTML text into a temporary UTF-8 file; caller removes it. */
  htmlText(path:string,maxOutputBytes=32*1024*1024):Promise<{path:string;size:number;sha256:string}> {
    return hostOperation<{path:string;size:number;sha256:string}>("file.htmlText",{path,maxOutputBytes}).result;
  },
  gzip(path:string,maxOutputBytes=32*1024*1024):Promise<{path:string;size:number;sha256:string}> {
    return hostOperation<{path:string;size:number;sha256:string}>("file.gzip",{path,maxOutputBytes}).result;
  },
  /** Bounded in-memory path for sync bodies up to 64 KiB. */
  gzipBytes(dataBase64:string):Promise<{dataBase64:string}> {
    return hostOperation<{dataBase64:string}>("file.gzipBytes",{dataBase64}).result;
  },
  gunzip(path:string,maxOutputBytes=32*1024*1024,allowPlain=false):Promise<{path:string;size:number;sha256:string}> {
    return hostOperation<{path:string;size:number;sha256:string}>("file.gunzip",{path,maxOutputBytes,allowPlain}).result;
  },
  openText(path:string,options:{strictUtf8?:boolean}={}):Promise<{id:string}> {return hostOperation<{id:string}>("file.openText",{path,...options}).result;},
  readTextChunk(id:string,maxChars=16384):Promise<{text:string;eof:boolean}> {return hostOperation<{text:string;eof:boolean}>("file.readTextChunk",{id,maxChars}).result;},
  closeText(id:string):Promise<{closed:boolean}> {return hostOperation<{closed:boolean}>("file.closeText",{id}).result;},
  createTemp(directory:string):Promise<{path:string}> {
    return hostOperation<{path:string}>("file.createTemp",{directory}).result;
  },
  write(path:string,dataBase64:string):Promise<{size:number}> {
    return hostOperation<{size:number}>("file.write",{path,dataBase64,atomicReplace:true}).result;
  },
  append(path:string,dataBase64:string,expectedOffset:number):Promise<{size:number}> {
    return hostOperation<{size:number}>("file.write",{path,dataBase64,append:true,expectedOffset}).result;
  },
  hash(path:string):Promise<{size:number;sha256:string}> {
    return hostOperation<{size:number;sha256:string}>("file.hash",{path}).result;
  },
  commit(path:string,target:string,expectedSize:number,expectedSha256:string):Promise<{path:string;size:number;sha256:string;reused:boolean}> {
    return hostOperation<{path:string;size:number;sha256:string;reused:boolean}>("file.commit",{path,target,expectedSize,expectedSha256}).result;
  },
  /** Enumerate one directory inside the app file root. Links and oversized directories fail closed. */
  list(path:string):Promise<{entries:{path:string;file:boolean;directory:boolean;size:number;modified:number;changeToken?:string}[]}> {
    return hostOperation<{entries:{path:string;file:boolean;directory:boolean;size:number;modified:number;changeToken?:string}[]}>("file.list",{path}).result;
  },
  touch(path:string):Promise<{modified:number}> {return hostOperation<{modified:number}>("file.touch",{path}).result;},
  stat(path:string): Promise<{exists:boolean;file:boolean;directory:boolean;size:number;modified:number;changeToken?:string}> {
    return hostOperation<{exists:boolean;file:boolean;directory:boolean;size:number;modified:number;changeToken?:string}>("file.stat",{path}).result;
  },
  /** Decode a byte range using Android's replacement behavior for malformed UTF-8. */
  readText(path:string,offset=0,maxBytes=65536): Promise<{text:string;bytesRead:number;offset:number;size:number;eof:boolean}> {
    return hostOperation<{text:string;bytesRead:number;offset:number;size:number;eof:boolean}>("file.read",{path,offset,maxBytes,encoding:"utf8"}).result;
  },
  read(path:string,offset=0,maxBytes=65536): Promise<{dataBase64:string;offset:number;size:number;eof:boolean}> {
    return hostOperation<{dataBase64:string;offset:number;size:number;eof:boolean}>("file.read",{path,offset,maxBytes}).result;
  },
  async remove(path:string): Promise<void> { await hostOperation("file.delete",{path}).result; },
};
export { base64ToBytes, utf8ToString, stringToUtf8 } from "../../../vendor/pocketjs/framework/src/bytes.ts";

/** Wall-clock delay driven by the host, independent of UI frame cadence. */
/** Host timers accept 1..120000ms (Android rejects 0). A 0/sub-millisecond request is a
 * "yield to the next turn" and is clamped to 1ms so callers cannot stall on a rejected timer. */
export function hostDelayMilliseconds(milliseconds:number):number {
  return Number.isFinite(milliseconds)?Math.min(120000,Math.max(1,Math.ceil(milliseconds))):1;
}
export function delay(milliseconds:number):HostOperation<void> {
  return hostOperation<void>("runtime.delay",{milliseconds:hostDelayMilliseconds(milliseconds)});
}

/** Public-key RSA OAEP with SHA-256, MGF1 SHA-256 and an empty label. */
export function rsaOaepSha256(publicKeyPem:string,inputBase64:string):HostOperation<{ciphertextBase64:string}>{return hostOperation("crypto.rsaOaepSha256",{publicKeyPem,inputBase64});}

export interface TextLayoutLine { start:number; end:number; visibleEnd:number; topPx:number; bottomPx:number; baselinePx:number }
export interface TextFontOptions {
  /** Relative to application files; missing or unreadable font files use system fallback. */
  fontPath?:string;
  /** Logical resources.assets name; packaged under pod-assets/. Mutually exclusive with fontPath. */
  fontAsset?:string; fontFaceIndex?:number; variationSettings?:string;
}
export interface TextLayoutOptions extends TextFontOptions {
  text:string; widthPx:number; fontSizePx:number; lineHeightPx?:number;
  fontWeight?:number; italic?:boolean; letterSpacingEm?:number;
  lineBreak?:"simple"|"paragraph"|"balanced";
  lineHeightMode?:"span"|"spacing"; fallbackLineSpacing?:boolean;
  hyphenation?:"none"|"full"; justify?:boolean; align?:"left"|"center"|"right";
  textMotion?:"subpixel"|"static"|"animated"; colorArgb?:number; underline?:boolean; strikethrough?:boolean;
}
export interface TextParagraphLine extends TextLayoutOptions {lineIndex:number}
export interface TextEnvironment {
  /** Physical pixels per dp in the current host Activity context. */
  density:number;
  /** System setting, not a universal SP multiplier: Android may scale SP nonlinearly. */
  fontScale:number;
  /** Platform SP conversions in request order, including duplicates; empty when omitted. */
  spValuesPx:number[];
}
/** System or local-file font, with optional paragraph shaping. Dimensions are physical pixels; offsets are UTF-16. */
export const textLayout = {
  /** Fresh Activity Resources snapshot. At most 128 finite SP values in 0..8192.
   * Uses platform TypedValue SP conversion; not a promise of Compose version parity. */
  environment(options:{spValues?:readonly number[]}={}):Promise<TextEnvironment> {
    return hostOperation<TextEnvironment>("text.environment",{...options}).result;
  },
  measure(options:TextFontOptions & {text:string;fontSizePx:number;fontWeight?:number;italic?:boolean;letterSpacingEm?:number;textMotion?:"subpixel"|"static"|"animated"}):HostOperation<{widthPx:number}>{return hostOperation("text.measure",{...options});},
  layout(options:TextLayoutOptions):HostOperation<{lines:TextLayoutLine[]}> {
    return hostOperation("text.layout", {...options});
  },
  /** At most 32 paragraphs and 262,144 UTF-16 units per request. */
  layoutBatch(paragraphs:readonly TextLayoutOptions[]):HostOperation<{results:({lines:TextLayoutLine[]}|{error:string})[]}> {
    return hostOperation("text.layoutBatch", {paragraphs});
  },
};

/** System text shaping and color-font fallback for one physical-pixel text line. */
export const textRaster = {
  line(options:TextFontOptions & {text:string;widthPx:number;heightPx:number;baselinePx:number;fontSizePx:number;fontWeight?:number;italic?:boolean;underline?:boolean;strikethrough?:boolean;letterSpacingEm?:number;colorArgb?:number;align?:"left"|"center"|"right";textMotion?:"subpixel"|"static"|"animated";paragraph?:TextParagraphLine}):HostOperation<{path:string;width:number;height:number;contentWidth:number;contentHeight:number;carets:number[]}> { return hostOperation("text.raster",{...options}); },
};

/** Classic Bluetooth byte streams. Availability is gated by the host capability;
 * opening a listener does not discover, pair with, or trust a remote peer. */
export const rfcomm = {
  requestPermission: () => hostOperation<"granted"|"required">("rfcomm.requestPermission",{}),
  status: () => hostOperation<{available:boolean;permission:"granted"|"required";enabled:boolean}>("rfcomm.status",{}).result,
  listen: (name:string,uuid:string) => hostOperation<{id:string}>("rfcomm.listen",{name,uuid}),
  accept: (id:string,timeoutMs=120000) => hostOperation<{id:string;remoteAddress:string;remoteName:string|null}>("rfcomm.accept",{id,timeoutMs}),
  close: (id:string) => hostOperation<{closed:boolean}>("rfcomm.close",{id}).result,
};
/** Guest-owned TCP listeners. Closing/cancelling accept releases the listening socket.
 * Accepted connections use byteStreams; addresses reports the active Wi-Fi IPv4 route. */
export const tcp = {
 listen:(port=0,address:"0.0.0.0"|"127.0.0.1"="0.0.0.0")=>hostOperation<{id:string;port:number}>("tcp.listen",{port,address}),
 accept:(id:string,timeoutMs=120000)=>hostOperation<{id:string;remoteAddress:string}>("tcp.accept",{id,timeoutMs}),
 close:(id:string)=>hostOperation<{closed:boolean}>("tcp.close",{id}).result,
 addresses:()=>hostOperation<{addresses:string[]}>("tcp.addresses",{}).result,
};
export const byteStreams = {
  read: (id:string,maxBytes:number,timeoutMs:number) => hostOperation<{eof:boolean;dataBase64:string}>("stream.read",{id,maxBytes,timeoutMs}),
  write: (id:string,bytes:Uint8Array,timeoutMs=120000) => hostOperation<{written:number}>("stream.write",{id,dataBase64:bytesToBase64(bytes),timeoutMs}),
  close: (id:string) => hostOperation<{closed:boolean}>("stream.close",{id}).result,
};

/** Text messages for negotiation; binary messages use byteStreams with the same id.
 * initialTextHandshake drops binary messages before the first text message and
 * ignores later text messages. The guest must validate that first message. */
export const webSockets = {
 connect:(url:string,options:{headers?:Record<string,string>;timeoutMs?:number;bindLocalWifi?:boolean;initialTextHandshake?:boolean}={})=>hostOperation<{id:string}>("websocket.connect",{url,...options,timeoutMs:options.timeoutMs??4000}),
 readText:(id:string,timeoutMs=4000)=>hostOperation<{text:string}>("websocket.readText",{id,timeoutMs}),
 writeText:(id:string,text:string)=>hostOperation<{sent:boolean}>("websocket.writeText",{id,text}),
 close:(id:string)=>hostOperation<{closed:boolean}>("websocket.close",{id}).result,
};

/** Bounded IPv4 mDNS lookup; callers authenticate any discovered endpoint. */
export interface NetworkSnapshot {localWifi?:boolean;metered?:boolean;active:boolean;internet:boolean;validated:boolean;wifi:boolean;cellular:boolean;bluetooth:boolean}
export const discovery = {
 networkSnapshot:()=>hostOperation<NetworkSnapshot>("discovery.networkSnapshot",{}).result,
 requestPermission:()=>hostOperation<"granted"|"required">("discovery.requestPermission",{}).result,
 status:()=>hostOperation<{available:boolean;enabled:boolean;permission:"granted"|"required"}>("discovery.status",{}).result,
 bleReadCharacteristic:(options:{advertisedServiceUuid:string;manufacturerId:number;manufacturerDataBase64:string;serviceUuid:string;characteristicUuid:string;bondedOnly?:boolean;scanTimeoutMs?:number;readTimeoutMs?:number})=>hostOperation<{address:string;bonded:boolean;dataBase64:string}>("discovery.bleReadCharacteristic",{...options,bondedOnly:options.bondedOnly??true,scanTimeoutMs:options.scanTimeoutMs??8000,readTimeoutMs:options.readTimeoutMs??12000}),
 mdns:(options:{serviceType:string;namePrefix?:string;expectedPort:number;windowMs?:number})=>hostOperation<{endpoints:Array<{address:string;port:number;name:string}>}>("discovery.mdns",{...options,windowMs:options.windowMs??1500}),
};

export interface NetworkChange {source:"default"|"wifi";reason:"available"|"lost"|"capabilities"}
let nextNetworkSubscription=1;
/** Returns a disposer immediately, including while native registration is pending. */
export function onNetworkChange(listener:(event:NetworkChange)=>void,onError?:(error:unknown)=>void):()=>void {
 const subscriptionId="network-"+nextNetworkSubscription++;let stopped=false;
 const off=__onHostEvent(event=>{
  if(stopped||!hasCapability("net.discovery")||event.t!=="network.changed"||event.subscriptionId!==subscriptionId)return;
  const value=event.value as Partial<NetworkChange>|undefined;
  if(value&&(value.source==="default"||value.source==="wifi")&&(value.reason==="available"||value.reason==="lost"||value.reason==="capabilities"))listener(value as NetworkChange);
 });
 const pending=hostOperation<{subscriptionId:string}>("discovery.networkSubscribe",{subscriptionId});
 void pending.result.catch(error=>{if(!stopped){stopped=true;off();onError?.(error);}});
 return ()=>{if(stopped)return;stopped=true;off();try{pending.cancel();}finally{void hostOperation("discovery.networkUnsubscribe",{subscriptionId}).result.catch(()=>{});}};
}

/** Native-owned scene texture; controls do not take audio focus or change system volume. */
export interface MediaSurfacePresentationFrame {leftPx:number;topPx:number;widthPx:number;heightPx:number;scale:number;opacity:number}
export interface MediaSurfaceStyle {fit?:"crop"|"fit"|"fill";zoom?:number;focusX?:number;focusY?:number;rotationDegrees?:number;blurPx?:number;blurEdgeMode?:"decal"|"clamp";brightness?:number;/** Additive RGB offset in [-1, 1], after brightness/saturation; alpha is unchanged. Default: 0. */brightnessBias?:number;saturation?:number;colorArgb?:number;overlayColorArgb?:number;overlayOpacity?:number;speed?:number;loop?:boolean;active?:boolean;/** Native placement inside the fixed output viewport; omitted resets to the full viewport. */presentationFrame?:MediaSurfacePresentationFrame}
export type MediaSurfaceTextureSampling="nearest"|"linear";
export interface MediaSurfaceOpen extends MediaSurfaceStyle {id:string;path?:string;posterPath?:string;widthPx:number;heightPx:number;/** Creation-only scene texture sampling. Omitted preserves nearest. */textureSampling?:MediaSurfaceTextureSampling}
export interface MediaSurfaceState {id:string;texture:number;width:number;height:number;contentWidth:number;contentHeight:number;state:string;error:string|null;frames:number;playerGeneration:number;positionMs:number;speed:number;loop:boolean;muted:boolean;/** Older hosts omit this field; an explicit linear request requires confirmation. */textureSampling?:MediaSurfaceTextureSampling;/** Effective native frame, or null/omitted for the full viewport default. */presentationFrame?:MediaSurfacePresentationFrame|null}
export type MediaSurfaceKind="image"|"video";
export const mediaSurfaces={
 open(kind:MediaSurfaceKind,options:MediaSurfaceOpen):Promise<MediaSurfaceState>{return hostOperation<MediaSurfaceState>(kind+".surface.open",options as unknown as Record<string,unknown>).result;},
 update(kind:MediaSurfaceKind,id:string,style:MediaSurfaceStyle):Promise<MediaSurfaceState>{return hostOperation<MediaSurfaceState>(kind+".surface.update",{id,...style}).result;},
 state(kind:MediaSurfaceKind,id:string):Promise<MediaSurfaceState>{return hostOperation<MediaSurfaceState>(kind+".surface.state",{id}).result;},
 close(kind:MediaSurfaceKind,id:string):Promise<{closed:boolean}>{return hostOperation<{closed:boolean}>(kind+".surface.close",{id}).result;},
};

/** Debug/PERF_TRACE host gate. Unsupported/release hosts reject; no GC or
 * allocator trim is performed by a sample. See docs/memory-attribution.md. */
export interface DebugMemorySnapshot {
 native:Record<string,unknown>;renderer:Record<string,number>|null;
 text:Record<string,unknown>;java:Record<string,unknown>;
 pid:number;nativeAtMs:number;textAtMs:number;sampleAtMs:number;
}
export const debugMemoryStats=()=>hostOperation<DebugMemorySnapshot>("debug.memoryStats",{}).result;

/** Android debug-build-only native frame metrics; unsupported hosts reject. */
export interface DebugPerformanceSnapshot {
 sessionId:number;source:string;scenario:string;frames:number;jank:number;jankPct:number;avgMs:number;maxMs:number;drop:number;
 lastFrame:Record<string,number>;
}
export const debugPerformance={
 start:(scenario:string)=>hostOperation<DebugPerformanceSnapshot>('debug.performance.start',{scenario}).result,
 read:(sessionId:number)=>hostOperation<DebugPerformanceSnapshot>('debug.performance.read',{sessionId}).result,
 stop:(sessionId:number)=>hostOperation<DebugPerformanceSnapshot>('debug.performance.stop',{sessionId}).result,
};

export interface TakeoverStatus {
  protocol: number;
  phase: "NONE" | "DETECTED" | "SNAPSHOTTED" | "COPYING" | "VERIFYING" | "READY" | "ACTIVATING" | "ACTIVE" | "PAUSED" | "BLOCKED";
  reason?: string;
  resumePhase?: string;
  requiredBytes?: string;
  availableBytes?: string;
}
/** Read-only first-launch migration status; unsupported hosts reject via capability gating. */
export const takeover = Object.freeze({
  status: (): Promise<TakeoverStatus> => hostOperation<TakeoverStatus>("takeover.status", {}).result,
});

/** Android 12+ non-exportable P-256 key. keyId is scoped to this host application. */
export const deviceAgreement = {
  publicKey(keyId:string):Promise<{publicKeySpki:string}> {return hostOperation<{publicKeySpki:string}>("crypto.ecdhPublicKey",{keyId}).result;},
  /** Authenticated 32-byte key unwrap; neither the private key nor ECDH secret is returned. */
  unwrap(args:{keyId:string;publicKeySpki:string;saltBase64:string;infoBase64:string;nonceBase64:string;ciphertextBase64:string;aadBase64:string}):Promise<{dataBase64:string}> {return hostOperation<{dataBase64:string}>("crypto.ecdhUnwrap",args).result;},
};

/** Large crypto/compression results use private temporary files, avoiding the 1 MiB
 * host event limit. Caller must remove the returned file after consuming it. */
export interface CryptoFileResult {path:string;size:number;sha256:string}
export const cryptoFiles = {
  encrypt(args:{inputPath:string;keyBase64:string;aadBase64:string}):Promise<CryptoFileResult & {nonceBase64:string}> {return hostOperation<CryptoFileResult & {nonceBase64:string}>("crypto.largeAesGcmEncrypt",args).result;},
  decrypt(args:{inputPath:string;keyBase64:string;aadBase64:string;nonceBase64:string}):Promise<CryptoFileResult> {return hostOperation<CryptoFileResult>("crypto.largeAesGcmDecrypt",args).result;},
  gzip(inputPath:string):Promise<CryptoFileResult> {return hostOperation<CryptoFileResult>("crypto.gzip",{inputPath}).result;},
  gunzip(inputPath:string,maxBytes:number):Promise<CryptoFileResult> {return hostOperation<CryptoFileResult>("crypto.gunzip",{inputPath,maxBytes}).result;},
};

/** Debug-only, bounded PCM16 little-endian stream. One session and one pending
 * IO per session; read/write are pull/ack operations (4096 samples maximum).
 * Host stops PCM on background, focus loss, cancellation or its watchdog. */
export interface AcousticPcmSession {session:number;sampleRate:22050;chunkSamples:4096}
export const debugAcoustic = Object.freeze({
 requestPermission:(guided=false)=>hostOperation<'granted'|'required'>('acoustic.requestPermission',{guided}),
 open:(mode:'record'|'play')=>hostOperation<AcousticPcmSession>('acoustic.pcm.open',{mode}),
 read:(session:number)=>hostOperation<{pcm16Base64:string}>('acoustic.pcm.read',{session}),
 write:(session:number,pcm16Base64:string)=>hostOperation<{samples:number}>('acoustic.pcm.write',{session,pcm16Base64}),
 drain:(session:number)=>hostOperation<void>('acoustic.pcm.drain',{session}),
 stop:(session:number)=>hostOperation<void>('acoustic.pcm.stop',{session}),
 connect:(ssid:string,passphrase:string)=>hostOperation<{session:number}>('acoustic.wifi.connect',{ssid,passphrase}),
 request:(session:number,endpoint:{host:string;port:number;token:string;path:'pullRemoteInput'|'pushFavorites'|'pushWatchLater';body?:string})=>hostOperation<Record<string,unknown>>('acoustic.wifi.request',{session,...endpoint}),
 close:(session:number)=>hostOperation<void>('acoustic.wifi.close',{session}),
});

// Realm-local foreground scheduler; pure codecs also work in headless bundles.
// Match the runtime.timer minimum on Android, including portable codec jobs.
(globalThis as {__podYieldCodecTurn?:()=>Promise<void>}).__podYieldCodecTurn=()=>hasCapability("runtime.timer")?delay(1).result:new Promise<void>(resolve=>setTimeout(resolve,0));

export interface DebugExternalEvent {deadline?:number;id:number;kind:'intent'|'dump';action:string;extras:Record<string,string|number|boolean>;args:string[]}
export interface DebugExternalReply {code?:number;data?:string;result?:{code:number;extras?:Record<string,string|number|boolean>}}
export const debugExternal={
 async listen(handler:(event:DebugExternalEvent)=>Promise<DebugExternalReply>):Promise<()=>void>{
  if(!(await application.buildInfo()).debugBuild)throw new HostServiceError('unavailable','Debug external interface unavailable');
  let closed=false;
  const off=__onHostEvent(event=>{
   if(closed||event.t!=='debug.external'||typeof event.id!=='number')return;
   if(typeof event.deadline!=='number'||event.deadline<=Date.now())return;
   void Promise.resolve().then(()=>{if(closed||Number(event.deadline)<=Date.now())throw new Error("Debug request expired");return handler(event as unknown as DebugExternalEvent);}).catch(()=>({code:2,data:'debug_action_failed'})).then(result=>hostOperation('debug.external.reply',{id:event.id,...result}).result).catch(()=>{});
  });
  try{await hostOperation('debug.external.subscribe',{}).result;}catch(error){off();throw error;}
  return ()=>{closed=true;off();void hostOperation('debug.external.unsubscribe',{}).result.catch(()=>{});};
 }
};

/** Actual system capacity, or null when the device cannot report it. */
export const battery = Object.freeze({percent:():Promise<number|null>=>hostOperation<number|null>("battery.percent",{}).result});
