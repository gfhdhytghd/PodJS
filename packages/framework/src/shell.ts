import {ShellCommandError} from './shell-error.ts';
export {ShellCommandError} from './shell-error.ts';
import {__onHostEvent} from './watch.ts';
import {hostOperation} from './services.ts';

export type ShellContext = {isActive():boolean};
export type ShellHandler = (args:Record<string,unknown>,context:ShellContext)=>unknown|Promise<unknown>;
let registered=false;
/** Code-authored handlers only. The Android host also enforces the packaged allowlist. */
export function registerShellCommands(handlers:Record<string,ShellHandler>):()=>void {
 if(registered)throw new Error("Shell handlers already registered");registered=true;
 const active=new Map<number,{cancelled:boolean}>();
 let disposed=false;
 const emit=(value:unknown)=>(globalThis as {pod?:{emit(line:string):void}}).pod?.emit(JSON.stringify(value));
 const stop=__onHostEvent(event=>{
  if(event.t==='shell.cancel'){const request=active.get(event.id as number);if(request)request.cancelled=true;return;}
  if(event.t!=='shell.command'||typeof event.id!=='number'||typeof event.method!=='string')return;
  const id=event.id,request={cancelled:false};active.set(id,request);
  const handler=Object.prototype.hasOwnProperty.call(handlers,event.method)?handlers[event.method]:undefined;
  void Promise.resolve().then(()=>{
   if(disposed||request.cancelled)throw new ShellCommandError('unavailable');
   if(!handler)throw new ShellCommandError('not_found');
   return handler(event.args as Record<string,unknown>,{isActive:()=>!disposed&&!request.cancelled});
  }).then(value=>{
   if(!disposed&&!request.cancelled)emit({t:'shell.result',id,json:JSON.stringify(value??null)});
  }).catch(error=>{
   if(!disposed&&!request.cancelled)emit({t:'shell.result',id,error:error instanceof ShellCommandError?error.code:'command_failed'});
  }).finally(()=>active.delete(id));
 });
 emit({t:'shell.ready',commands:Object.keys(handlers)});
 return ()=>{if(disposed)return;disposed=true;registered=false;for(const request of active.values())request.cancelled=true;active.clear();stop();emit({t:'shell.ready',commands:[]});};
}
/** Numeric host counters only. No SQL text, URLs, paths or account material. */
export const shellDiagnostics=()=>hostOperation<ShellDiagnostics>('shell.diagnostics',{}).result;
export interface ShellDiagnostics {
 pssKb:number; decoderInstances:number; decoderLeases:number;
 /** Current local PSS plus missing system-reported graphics. The protected
  * component may be cached by Android; dumpsys remains the acceptance meter. */
 pssLocalKb?:number;pssGraphicsKb?:number;pssCorrectionKb?:number;
 pssProtectedQueryAgeMs?:number;pssProtectedMayBeCached?:boolean;
 sql:Record<string,{count:number;queueNs:number;workNs:number;cpuNs:number;failures:number}>;
 videoSessions:{active:number;created:number;released:number;
  /** Frame rate declared by the playing track, when supplied by its container. */
  surfaceViews?:{attached:number;available:number;idleAvailable:number;playingWidth:number;playingHeight:number;playingFormatFrameRate?:number;directOutputs?:number};
  mediaMemory?:Partial<Record<"VIDEO_CODEC"|"AUDIO_CODEC"|"RETRIEVER"|"COMPRESSED_BUFFER"|"SURFACE"|"KEYFRAME"|"FRAME_CAPTURE",{estimatedBytes:number;allocations:number}>>};
}
