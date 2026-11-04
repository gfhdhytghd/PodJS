/** Numeric/coarse work telemetry. No data, paths, identities or exception text.
 * Elapsed time is inclusive wall time, not thread CPU accounting. */
export const CPU_WORK_STAGES=['crc32','byte-compare','utf8-encode','utf8-decode','base64-encode','base64-decode','sha256','gunzip','json-scan','json-parse','json-stringify','canonical-json','journal-rebuild','journal-diff','sort','archive-index','manifest-validate'] as const;
export type CpuInputScale='bytes'|'chars'|'items';
export interface CpuWorkMetric {event:'cpu-work';stage:string;scale:CpuInputScale;sizeClass:number;elapsedMillis:number;outcome:'started'|'progress'|'complete'|'failed'}
type Sink=(metric:CpuWorkMetric)=>void|Promise<void>;
const CPU_TICKET=Symbol("cpu-work-ticket");
export function cpuWorkTicket(metric:CpuWorkMetric):number{return (metric as CpuWorkMetric&{[CPU_TICKET]?:number})[CPU_TICKET]??0;}
interface Scope {sink:Sink;active:Set<Ticket>;closed:boolean;sequence:number}
interface Ticket {ready:void|Promise<void>;pulse():void;finish(failed?:boolean):void}
let scope:Scope|undefined;
export function cpuSizeClass(scale:CpuInputScale,size:number){const bounds=scale==='items'?[32,512,8192,50000,200000]:[65536,1048576,8388608,67108864,268435456];const n=Number.isFinite(size)?Math.max(0,size):Infinity;const at=bounds.findIndex(limit=>n<=limit);return at<0?5:at;}
/** Restore the prior observer even after failure. No observer means no IO. */
export function observeCpuWork(sink:Sink){const previous=scope,owned:Scope={sink,active:new Set(),closed:false,sequence:0};scope=owned;return ()=>{owned.closed=true;owned.active.clear();if(scope===owned)scope=previous;};}
function begin(stage:string,scale:CpuInputScale,size:number):Ticket|undefined {
 const owner=scope;if(!owner||owner.active.size>=32||size<(scale==='items'?32:scale==='chars'?32768:65536))return;
 const token=++owner.sequence;const started=Date.now(),sizeClass=cpuSizeClass(scale,size);let last=started,closed=false;
 const emit=(outcome:CpuWorkMetric['outcome'])=>{if(owner.closed)return;try{const promise=owner.sink({[CPU_TICKET]:token,event:'cpu-work',stage,scale,sizeClass,elapsedMillis:Math.min(300000,Math.max(0,Date.now()-started)),outcome} as CpuWorkMetric);return promise?.catch(()=>{});}catch{/* A diagnostic cannot change computation. */}};
 const ticket:Ticket={ready:undefined,pulse(){const now=Date.now();if(!closed&&now-last>=5000){last=now;void emit('progress');}},finish(failed=false){if(closed)return;closed=true;owner.active.delete(ticket);void emit(failed?'failed':'complete');}};
 owner.active.add(ticket);ticket.ready=emit('started');return ticket;
}
export function pulseCpuWork(){for(const ticket of scope?.active??[])ticket.pulse();}
export function cpuWork<T>(stage:string,scale:CpuInputScale,size:number,run:()=>T):T {const ticket=begin(stage,scale,size);let failed=true;try{const result=run();failed=false;return result;}finally{ticket?.finish(failed);}}
export function cpuWorkAsync<T>(stage:string,scale:CpuInputScale,size:number,run:()=>Promise<T>):Promise<T> {const ticket=begin(stage,scale,size);return ticket?observedAsync(ticket,run):run();}
async function observedAsync<T>(ticket:Ticket,run:()=>Promise<T>):Promise<T> {let failed=true;try{try{await ticket?.ready;}catch{/* Observation failure cannot change work. */}const result=await run();failed=false;return result;}finally{ticket?.finish(failed);}}
export function cpuJsonParse<T=any>(source:string):T{if(source.length<32768)return JSON.parse(source);return cpuWork('json-parse','chars',source.length,()=>JSON.parse(source));}
// Canonical JSON invokes this for every scalar/key. Those calls never produce
// telemetry; keep them outside the observer/closure path entirely.
export function cpuJsonStringify(value:unknown):string {if(!Array.isArray(value)||value.length<32)return JSON.stringify(value);return cpuWork('json-stringify','items',value.length,()=>JSON.stringify(value));}
export function cpuSort<T>(values:T[],compare?:(a:T,b:T)=>number):T[]{if(values.length<32)return values.sort(compare);return cpuWork('sort','items',values.length,()=>values.sort(compare));}

/** Observe a lazy CPU traversal without buffering it or losing iterator cleanup. */
export async function* cpuWorkAsyncIterable<T>(stage:string,scale:CpuInputScale,size:number,run:()=>AsyncIterable<T>):AsyncGenerator<T>{
 const ticket=begin(stage,scale,size);let failed=true;
 try{await ticket?.ready;yield* run();failed=false;}finally{ticket?.finish(failed);}
}
