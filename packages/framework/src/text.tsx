export {prepareTextLayout,type TextLayoutSnapshot,type TextLayoutSliceOptions,type TextLayoutSliceStats} from "./text-layout.ts";
import {createEffect,createSignal,onCleanup,type JSX} from "solid-js";
import {View,Image,type NodeMirror} from "@pocketjs/framework/components";
import {getOps} from "@pocketjs/framework/host";
import {textRaster,fileChunks,base64ToBytes,hasTextureFileUpload,uploadTextureFile,type TextParagraphLine,type TextFontOptions} from "./services.ts";

export interface SystemTextLineOptions extends TextFontOptions {fontRevision?:string;text:string;widthPx:number;heightPx:number;baselinePx:number;fontSizePx:number;fontWeight?:number;italic?:boolean;underline?:boolean;strikethrough?:boolean;letterSpacingEm?:number;colorArgb?:number;align?:"left"|"center"|"right";textMotion?:"subpixel"|"static"|"animated";paragraph?:TextParagraphLine}
export type SystemTextFailureStage="raster"|"read"|"upload"|"set-image"|"ready-callback";
export type SystemTextEntryState="pending"|"ready"|"failed";
type LoadStage="queued"|"raster"|"read"|"upload"|"delivery";
interface Listener {
 node:NodeMirror;
 ready:(value:{width:number;height:number;carets:number[]})=>void;
 state:SystemTextEntryState;
 failureStage?:SystemTextFailureStage;
}
interface Entry {
 id:number;key:string;options:SystemTextLineOptions;refs:number;cancelled:boolean;started:boolean;
 handle:number;path?:string;width:number;height:number;carets:number[];listeners:Set<Listener>;
 stage:LoadStage;loadFailure?:SystemTextFailureStage;succeeded:boolean;
}
const entries=new Map<string,Entry>(),queue:Entry[]=[];let active=0,nextEntryId=1;
let succeededTotal=0,failedTotal=0,deliveredTotal=0;
const failuresByStage:Record<SystemTextFailureStage,number>={raster:0,read:0,upload:0,"set-image":0,"ready-callback":0};
const remove=async(path:string|undefined)=>{if(path)await fileChunks.remove(path).catch(()=>{});};

function entryState(entry:Entry):SystemTextEntryState {
 if(entry.loadFailure)return "failed";
 let pending=entry.handle<0;
 for(const listener of entry.listeners){if(listener.state==="failed")return "failed";if(listener.state!=="ready")pending=true;}
 return pending?"pending":"ready";
}
function countSuccess(entry:Entry):void {
 if(!entry.cancelled&&!entry.succeeded&&entry.refs>0&&entryState(entry)==="ready"){
  entry.succeeded=true;succeededTotal++;
 }
}
function countFailure(stage:SystemTextFailureStage):void {
 failedTotal++;failuresByStage[stage]++;
 // Host errors can include the input text or a private file path. The public
 // diagnostic and warning report only the operation stage.
 console.warn("SystemTextLine",stage,"failed");
}
function deliver(entry:Entry,listener:Listener):void {
 if(entry.cancelled||!entry.listeners.has(listener)||listener.state!=="pending")return;
 let stage:SystemTextFailureStage="set-image";
 try{
  getOps().setImage(listener.node.id,entry.handle);
  if(entry.cancelled||!entry.listeners.has(listener))return;
  stage="ready-callback";
  listener.ready({width:entry.width,height:entry.height,carets:entry.carets});
  if(entry.cancelled||!entry.listeners.has(listener))return;
  listener.state="ready";deliveredTotal++;
 }catch{
  if(entry.cancelled||!entry.listeners.has(listener))return;
  listener.state="failed";listener.failureStage=stage;countFailure(stage);
 }
 countSuccess(entry);
}
async function load(entry:Entry){
 let stage:SystemTextFailureStage="raster";
 try{
  // Keep delivery alive after release: the host may already have created its file.
  // At most two jobs run; their late results are drained and removed without upload.
  const operation=textRaster.line(entry.options);
  const result=await operation.result;entry.path=result.path;if(entry.cancelled)return;
  if(!Number.isInteger(result.width)||!Number.isInteger(result.height)||result.width<1||result.width>512||result.height<1||result.height>512||(result.width&(result.width-1))||(result.height&(result.height-1)))throw new Error("Invalid system text texture size");
  if(result.carets.length!==entry.options.text.length+1||!result.carets.every(Number.isFinite))throw new Error("Invalid system text caret positions");
  let handle:number;
  if(hasTextureFileUpload()){
   entry.stage="upload";stage="upload";
   // The supporting host consumes this exact temporary path on every outcome.
   entry.path=undefined;
   handle=uploadTextureFile(result.path,result.width,result.height,3)??-1;
  }else{
   entry.stage="read";stage="read";
   const bytes=new Uint8Array(result.width*result.height*4);let offset=0;
   while(offset<bytes.length){
    const part=await fileChunks.read(result.path,offset,Math.min(262144,bytes.length-offset));if(entry.cancelled)return;
    const chunk=base64ToBytes(part.dataBase64);if(!chunk.length||chunk.length>bytes.length-offset||part.size!==bytes.length)throw new Error("Incomplete system text pixels");bytes.set(chunk,offset);offset+=chunk.length;
   }
   if(entry.cancelled)return;
   entry.stage="upload";stage="upload";
   handle=getOps().uploadTexture(bytes,result.width,result.height,3);
  }
  if(!Number.isInteger(handle)||handle<0)throw new Error("System text texture upload failed");
  // A synchronous host callback can release the last subscriber during upload.
  if(entry.cancelled){getOps().freeTexture?.(handle);return;}
  entry.handle=handle;entry.width=result.width;entry.height=result.height;entry.carets=result.carets;
  entry.stage="delivery";
  // One failed consumer must not prevent delivery to other current consumers.
  for(const listener of entry.listeners)deliver(entry,listener);
 }catch{if(!entry.cancelled){entry.loadFailure=stage;countFailure(stage);}}
 finally{await remove(entry.path);entry.path=undefined;}
}
function pump(){while(active<2&&queue.length){const entry=queue.shift()!;if(entry.cancelled)continue;entry.started=true;entry.stage="raster";active++;void load(entry).finally(()=>{active--;pump();});}}
export function bindSystemTextLine(options:SystemTextLineOptions,node:NodeMirror,ready:(value:{width:number;height:number;carets:number[]})=>void):()=>void{
 const key=JSON.stringify(options);let entry=entries.get(key);
 if(!entry){entry={id:nextEntryId++,key,options,refs:0,cancelled:false,started:false,handle:-1,width:0,height:0,carets:[],listeners:new Set(),stage:"queued",succeeded:false};entries.set(key,entry);queue.push(entry);}
 entry.refs++;
 const listener:Listener={node,ready,state:"pending"};entry.listeners.add(listener);
 if(entry.handle>=0)deliver(entry,listener);pump();
 let released=false;
 return()=>{
  if(released)return;released=true;
  entry!.listeners.delete(listener);
  // Detach errors cannot strand a reference or turn cancellation into failure.
  try{getOps().setImage(node.id,-1);}catch{console.warn("SystemTextLine","detach failed");}
  if(--entry!.refs>0){countSuccess(entry!);return;}
  if(entries.get(key)===entry)entries.delete(key);
  entry!.cancelled=true;
  if(entry!.handle>=0){const handle=entry!.handle;entry!.handle=-1;try{getOps().freeTexture?.(handle);}catch{console.warn("SystemTextLine","free failed");}}
 };
}
/** System-shaped text rendered inside the application's retained scene, with a bounded texture lifecycle. */
export function SystemTextLine(props:{options:SystemTextLineOptions;pixelScale:number;fallback?:JSX.Element;onCarets?:(carets:number[]|undefined)=>void}){
 let node:NodeMirror|undefined;
 const [texture,setTexture]=createSignal<{width:number;height:number}>();
 createEffect(()=>{
  const options={...props.options},scale=props.pixelScale;if(!node)return;
  setTexture(undefined);props.onCarets?.(undefined);
  const release=bindSystemTextLine(options,node,value=>{setTexture(value);props.onCarets?.(value.carets.map(x=>x/scale));});
  onCleanup(()=>{release();props.onCarets?.(undefined);});
 });
 return <View accessibilityRole="text" accessibilityLabel={props.options.text} class="relative overflow-hidden" style={{width:props.options.widthPx/props.pixelScale,height:props.options.heightPx/props.pixelScale}}>
  <View accessibilityHidden class="absolute inset-0" style={{opacity:texture()?0:1}}>{props.fallback}</View>
  <Image accessibilityHidden ref={value=>{node=value;}} class="absolute left-[0] top-[0]" style={{width:(texture()?.width??props.options.widthPx)/props.pixelScale,height:(texture()?.height??props.options.heightPx)/props.pixelScale,opacity:texture()?1:0}}/>
 </View>;
}
/** Numeric-only ownership, computed on demand without walking status listeners. */
export function systemTextMemoryStats() {
 let refs=0,listeners=0,textures=0,textureBytes=0,utf16Units=0,paragraphUtf16Units=0,keyUtf16Units=0,caretUnits=0;
 for(const entry of entries.values()){
  refs+=entry.refs;listeners+=entry.listeners.size;utf16Units+=entry.options.text.length;
  paragraphUtf16Units+=entry.options.paragraph?.text.length??0;keyUtf16Units+=entry.key.length;caretUnits+=entry.carets.length;
  if(entry.handle>=0){textures++;textureBytes+=entry.width*entry.height*4;}
 }
 return {...systemTextResourceCounts(),queueSlots:queue.length,refs,listeners,textures,textureBytes,utf16Units,paragraphUtf16Units,keyUtf16Units,caretUnits};
}

export const systemTextResourceCounts=()=>({active,queued:queue.filter(value=>!value.cancelled).length,cached:entries.size});

export interface SystemTextEntryStatus {
 readonly id:number;
 readonly status:SystemTextEntryState;
 readonly stage:LoadStage|"ready"|"failed";
 readonly refs:number;
 readonly uploaded:boolean;
 readonly readyListeners:number;
 readonly pendingListeners:number;
 readonly failedListeners:number;
 readonly failureStages:readonly SystemTextFailureStage[];
}
export interface SystemTextResourceStatus {
 readonly active:number;readonly queued:number;readonly cached:number;
 readonly pending:number;readonly ready:number;readonly failed:number;
 readonly totals:Readonly<{
  /** Cache entries that have reached ready at least once in this JS runtime. */
  succeeded:number;
  /** Non-cancelled failed operations, including individual listener delivery. */
  failed:number;
  /** Successful setImage and ready-callback pairs for current-at-delivery listeners. */
  delivered:number;
  failuresByStage:Readonly<Record<SystemTextFailureStage,number>>;
 }>;
 readonly entries:readonly SystemTextEntryStatus[];
}
/** A new, deeply frozen diagnostic snapshot without text, keys or file paths.
 * Ready means uploaded and successfully delivered to every current listener;
 * active/queued reaching zero alone cannot distinguish success from fallback.
 * Totals are monotonic for this JS runtime; cancellation is not a failure.
 */
export function systemTextResourceStatus():SystemTextResourceStatus {
 let pending=0,ready=0,failed=0;
 const current:SystemTextEntryStatus[]=[];
 for(const entry of entries.values()){
  const status=entryState(entry);
  if(status==="pending")pending++;else if(status==="ready")ready++;else failed++;
  let readyListeners=0,pendingListeners=0,failedListeners=0;
  const stages=new Set<SystemTextFailureStage>();if(entry.loadFailure)stages.add(entry.loadFailure);
  for(const listener of entry.listeners){
   if(listener.state==="ready")readyListeners++;else if(listener.state==="failed")failedListeners++;else pendingListeners++;
   if(listener.failureStage)stages.add(listener.failureStage);
  }
  current.push(Object.freeze({id:entry.id,status,stage:status==="pending"?entry.stage:status,refs:entry.refs,uploaded:entry.handle>=0,
   readyListeners,pendingListeners,failedListeners,failureStages:Object.freeze([...stages])}));
 }
 return Object.freeze({...systemTextResourceCounts(),pending,ready,failed,
  totals:Object.freeze({succeeded:succeededTotal,failed:failedTotal,delivered:deliveredTotal,failuresByStage:Object.freeze({...failuresByStage})}),
  entries:Object.freeze(current)});
}
