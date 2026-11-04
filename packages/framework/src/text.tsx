import {createTextureUploader} from './texture-upload.ts';
export {prepareTextLayout,type TextLayoutSnapshot,type TextLayoutSliceOptions,type TextLayoutSliceStats} from "./text-layout.ts";
import {createEffect,createRenderEffect,createMemo,createSignal,onCleanup,untrack,type JSX} from "solid-js";
import {createElement,insertNode,insert,setProp,type NodeMirror} from "@pocketjs/framework/renderer";
import {getOps} from "@pocketjs/framework/host";
import {textRaster,fileChunks,base64ToBytes,hasTextureFileUpload,uploadTextureFile,type TextParagraphLine,type TextFontOptions} from "./services.ts";

const textures=createTextureUploader({uploadFile:uploadTextureFile});
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
 cache?:TextCacheState;
 stage:LoadStage;loadFailure?:SystemTextFailureStage;succeeded:boolean;
}
export interface SystemTextCache {close():void;stats():{retained:number;retainedBytes:number;entries:number}}
interface TextCacheState {id:number;closed:boolean;maxEntries:number;maxBytes:number;members:Set<Entry>;idle:Set<Entry>}
const textCaches=new WeakMap<SystemTextCache,TextCacheState>();let nextCacheId=1;
/** A component-scoped bitmap history; active owners keep their ordinary leases. */
export function createSystemTextCache(options:{maxEntries?:number;maxBytes?:number}={}):SystemTextCache {
 const maxEntries=options.maxEntries??24,maxBytes=options.maxBytes??8*1024*1024;
 if(!Number.isSafeInteger(maxEntries)||maxEntries<0||!Number.isSafeInteger(maxBytes)||maxBytes<0)throw new RangeError("Invalid system text cache bounds");
 const state:TextCacheState={id:nextCacheId++,closed:false,maxEntries,maxBytes,members:new Set(),idle:new Set()};
 const cache:SystemTextCache={close(){if(state.closed)return;state.closed=true;for(const entry of [...state.idle])discard(entry);},stats(){return {retained:state.idle.size,retainedBytes:[...state.idle].reduce((sum,e)=>sum+e.width*e.height*4,0),entries:state.members.size};}};
 textCaches.set(cache,state);return cache;
}
function discard(entry:Entry):void {
 if(entries.get(entry.key)===entry)entries.delete(entry.key);entry.cache?.idle.delete(entry);entry.cache?.members.delete(entry);entry.cancelled=true;
 if(entry.handle>=0){const handle=entry.handle;entry.handle=-1;try{textures.release(handle);}catch{console.warn("SystemTextLine","free failed");}}
}
function retain(entry:Entry):boolean {
 const cache=entry.cache;if(!cache||cache.closed||entry.handle<0||!entry.succeeded||cache.maxEntries===0)return false;
 cache.idle.delete(entry);cache.idle.add(entry);
 let bytes=[...cache.idle].reduce((sum,e)=>sum+e.width*e.height*4,0);
 while(cache.idle.size>cache.maxEntries||bytes>cache.maxBytes){const oldest=cache.idle.values().next().value!;bytes-=oldest.width*oldest.height*4;discard(oldest);}
 return !entry.cancelled;
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
   handle=textures.uploadFile(result.path,result.width,result.height,{take:()=>{entry.path=undefined;}})??-1;
  }else{
   entry.stage="read";stage="read";
   const bytes=new Uint8Array(result.width*result.height*4);let offset=0;
   while(offset<bytes.length){
    const part=await fileChunks.read(result.path,offset,Math.min(262144,bytes.length-offset));if(entry.cancelled)return;
    const chunk=base64ToBytes(part.dataBase64);if(!chunk.length||chunk.length>bytes.length-offset||part.size!==bytes.length)throw new Error("Incomplete system text pixels");bytes.set(chunk,offset);offset+=chunk.length;
   }
   if(entry.cancelled)return;
   entry.stage="upload";stage="upload";
   handle=textures.uploadRgba(bytes,result.width,result.height);
  }
  if(!Number.isInteger(handle)||handle<0)throw new Error("System text texture upload failed");
  // A synchronous host callback can release the last subscriber during upload.
  if(entry.cancelled){textures.release(handle);return;}
  entry.handle=handle;entry.width=result.width;entry.height=result.height;entry.carets=result.carets;
  entry.stage="delivery";
  // One failed consumer must not prevent delivery to other current consumers.
  for(const listener of entry.listeners)deliver(entry,listener);
 }catch{if(!entry.cancelled){entry.loadFailure=stage;countFailure(stage);}}
 finally{await remove(entry.path);entry.path=undefined;}
}
function pump(){while(active<2&&queue.length){const entry=queue.shift()!;if(entry.cancelled)continue;entry.started=true;entry.stage="raster";active++;void load(entry).finally(()=>{active--;pump();});}}
export function bindSystemTextLine(options:SystemTextLineOptions,node:NodeMirror,ready:(value:{width:number;height:number;carets:number[]})=>void,cache?:SystemTextCache):()=>void{
 return bindSystemTextLineKey(options,node,ready,JSON.stringify(options),cache);
}
function bindSystemTextLineKey(options:SystemTextLineOptions,node:NodeMirror,ready:(value:{width:number;height:number;carets:number[]})=>void,key:string,owner?:SystemTextCache):()=>void{
 const cache=owner?textCaches.get(owner):undefined;if(owner&&(!cache||cache.closed))throw new Error("System text cache is closed");
 if(cache)key="cache:"+cache.id+":"+key;
 let entry=entries.get(key);
 if(!entry){entry={id:nextEntryId++,key,options,refs:0,cancelled:false,started:false,handle:-1,width:0,height:0,carets:[],listeners:new Set(),stage:"queued",succeeded:false,cache};entries.set(key,entry);cache?.members.add(entry);queue.push(entry);}
 entry.cache?.idle.delete(entry);entry.refs++;
 const listener:Listener={node,ready,state:"pending"};entry.listeners.add(listener);
 if(entry.handle>=0)deliver(entry,listener);pump();
 let released=false;
 return()=>{
  if(released)return;released=true;
  entry!.listeners.delete(listener);
  // Detach errors cannot strand a reference or turn cancellation into failure.
  try{getOps().setImage(node.id,-1);}catch{console.warn("SystemTextLine","detach failed");}
  if(--entry!.refs>0){countSuccess(entry!);return;}
  if(!retain(entry!))discard(entry!);
 };
}
/** System-shaped text rendered inside the application's retained scene, with a bounded texture lifecycle. */
export function SystemTextLine(props:{options:SystemTextLineOptions;pixelScale:number;top?:number;cache?:SystemTextCache;fallback?:JSX.Element;renderFallback?:(visible:()=>boolean)=>JSX.Element;onCarets?:(carets:number[]|undefined)=>void}){
 // The line owns its clipping and optional native fallback. A specialized
 // fallback can consume visibility directly, avoiding a redundant native View.
 const root=createElement("view");
 const [texture,setTexture]=createSignal<{width:number;height:number}>();
 setProp(root,"accessibilityRole","text");
 let fallback:NodeMirror|undefined;
 if(props.renderFallback)insert(root,()=>props.renderFallback!(()=>!texture()));
 else {
  fallback=createElement("view");setProp(fallback,"accessibilityHidden",true);setProp(fallback,"class","absolute inset-0");
  insertNode(root,fallback);insert(fallback,()=>props.fallback);
 }
 const node=createElement("image");setProp(node,"accessibilityHidden",true);setProp(node,"class","absolute left-[0] top-[0]");insertNode(root,node);
 const requestKey=createMemo(()=>JSON.stringify(props.options));
 createEffect(()=>{
  const key=requestKey(),scale=props.pixelScale,options=untrack(()=>({...props.options})),onCarets=props.onCarets,cache=props.cache;
  setTexture(undefined);onCarets?.(undefined);
  const release=bindSystemTextLineKey(options,node,value=>{setTexture(value);onCarets?.(value.carets.map(x=>x/scale));},key,cache);
  onCleanup(()=>{release();onCarets?.(undefined);});
 });
 let rootStyle:Record<string,number>|undefined,imageStyle:Record<string,number>|undefined,fallbackStyle:Record<string,number>|undefined;
 createRenderEffect(()=>{
  const options=props.options,scale=props.pixelScale;
  setProp(root,"accessibilityLabel",options.text);setProp(root,"class",props.top===undefined?"relative overflow-hidden":"absolute left-[0] overflow-hidden");
  const style={width:options.widthPx/scale,height:options.heightPx/scale,...(props.top===undefined?{}:{insetT:props.top})};setProp(root,"style",style,rootStyle);rootStyle=style;
 });
 createRenderEffect(()=>{
  const value=texture(),options=props.options,scale=props.pixelScale;
  if(fallback){const hidden={opacity:value?0:1};setProp(fallback,"style",hidden,fallbackStyle);fallbackStyle=hidden;}
  const style={width:(value?.width??options.widthPx)/scale,height:(value?.height??options.heightPx)/scale,opacity:value?1:0};
  setProp(node,"style",style,imageStyle);imageStyle=style;
 });
 return root as unknown as JSX.Element;
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
