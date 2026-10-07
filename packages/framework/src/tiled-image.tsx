import {For,createEffect,createMemo,createSignal,onCleanup,untrack} from "solid-js";
import {Image,View,type NodeMirror} from "@pocketjs/framework/components";
import {getOps} from "@pocketjs/framework/host";
import {hostOperation,base64ToBytes,HostServiceError} from "./services";
import {__onHostEvent} from "./watch";
import {previewPlacement,type PreviewGeometry,type PreviewState} from "./image-preview.ts";
import {visibleImageTiles,imageTileRegion,type ImageTile,type ImageTileRegion} from "./image-tiles.ts";
interface Decoded {path:string;width:number;height:number;sourceWidth:number;sourceHeight:number;contentWidth?:number;contentHeight?:number;trace?:{decoderOpenMs:number;decodeAndCropMs:number;padMs:number;writeRgbaMs:number}}
interface TileEntry {tile:ImageTile;region:ImageTileRegion;handle:number;width:number;height:number;contentWidth:number;contentHeight:number;cancelled:boolean;started:boolean;op?:{cancel:()=>void};path?:string}
function uploadImage(bytes:Uint8Array,width:number,height:number){const ops=getOps();return ops.uploadTextureSampled?.(bytes,width,height,3,1)??ops.uploadTexture(bytes,width,height,3);}
// A full-resolution image can finish dozens of tiles in one frame. Bound file
// cleanup separately from decoding so cleanup cannot exhaust the host's
// 64-request lane or prevent the next image from opening.
const pendingDeletes=new Set<string>();
let activeDeletes=0,deleteBlocked=false;
function pumpDeletes(){
 if(deleteBlocked)return;
 while(activeDeletes<4&&pendingDeletes.size){
  const path=pendingDeletes.values().next().value!;pendingDeletes.delete(path);activeDeletes++;
  void hostOperation("file.delete",{path}).result.catch(error=>{
   if(error instanceof HostServiceError&&error.code==="busy"){pendingDeletes.add(path);deleteBlocked=true;}
  }).finally(()=>{activeDeletes--;if(!deleteBlocked)pumpDeletes();});
 }
}
__onHostEvent(event=>{if(deleteBlocked&&event.t==="service.result"){deleteBlocked=false;pumpDeletes();}});
function deleteDecoded(path:string){pendingDeletes.add(path);pumpDeletes();}
/** A retained overview plus bounded visible source tiles; four decodes in flight. */
export function TiledImage(props:{url:string;filePath?:string;sourceProvider:{acquire(url:string):{result:Promise<{path:string;release():void}>;cancel():void}};tileOptions?:import("./image-tiles").ImageTileOptions;decodeOptions?:{applyExif?:boolean;rgb565?:boolean};messages?:{size:string;data:string;upload:string};geometry:PreviewGeometry;state:PreviewState;onSize:(width:number,height:number)=>void;onError:(error:unknown)=>void;onVisibleTilesReady?:()=>void;onTileTiming?:(timing:{decodeMs:number;readMs:number;uploadMs:number;bytes:number;hostTrace?:Decoded["trace"]})=>void}){
 let overviewNode:NodeMirror|undefined,overviewHandle=-1,closed=false,active=0,ready=false;
 let initialOp:{cancel:()=>void}|undefined;
 let sourcePath=props.filePath,cached:{path:string;release():void}|undefined;
 const finishLease=()=>{if(closed&&active===0&&!initialOp){cached?.release();cached=undefined;}};
 const cache=new Map<string,TileEntry>(),[tiles,setTiles]=createSignal<TileEntry[]>([]),[source,setSource]=createSignal<{width:number;height:number}>();
 let reportedPlan="";
 const remove=(path:string|undefined)=>{if(path&&path!==sourcePath)deleteDecoded(path);};
 const removeEntry=(entry:TileEntry)=>{const path=entry.path;entry.path=undefined;remove(path);};
 async function pixels(decoded:Decoded,setOperation:(op:{cancel:()=>void})=>void,cancelled:()=>boolean){
  if(decoded.width<1||decoded.height<1||decoded.width>512||decoded.height>512||(decoded.width&(decoded.width-1))||(decoded.height&(decoded.height-1)))throw new Error(props.messages?.size??"Image size failure");
  const expected=decoded.width*decoded.height*4;
  const native=(globalThis as {pod?:{readDecodedRgba?:(path:string)=>Uint8Array|null}}).pod?.readDecodedRgba;
  const direct=native?.(decoded.path);
  if(direct?.length===expected)return direct;
  const result=new Uint8Array(expected);let offset=0;
  while(offset<result.length){const op=hostOperation<{dataBase64:string;size:number}>("file.read",{path:decoded.path,offset,maxBytes:Math.min(256*1024,result.length-offset)});setOperation(op);const chunk=await op.result;if(cancelled())throw new Error("cancelled");const bytes=base64ToBytes(chunk.dataBase64);if(!bytes.length||bytes.length>result.length-offset||chunk.size!==result.length)throw new Error(props.messages?.data??"Image data failure");result.set(bytes,offset);offset+=bytes.length;}
  return result;
 }
 const publish=()=>{if(closed)return;setTiles([...cache.values()].filter(entry=>entry.handle>=0&&!entry.cancelled));const expected=plan();const id=expected.map(tile=>tile.key).join("|");if(expected.length&&id!==reportedPlan&&expected.every(tile=>(cache.get(tile.key)?.handle??-1)>=0)){reportedPlan=id;props.onVisibleTilesReady?.();}};
 const release=(entry:TileEntry)=>{entry.cancelled=true;entry.op?.cancel();if(entry.handle>=0){getOps().freeTexture?.(entry.handle);entry.handle=-1;}removeEntry(entry);};
 async function loadTile(entry:TileEntry){
  try {
   const startedAt=props.onTileTiming?Date.now():0;
   const op=hostOperation<Decoded>("image.decodeRegion",{path:sourcePath,...entry.region,sampleSize:entry.tile.sampleSize,...props.decodeOptions,trace:!!props.onTileTiming});entry.op=op;const decoded=await op.result;entry.path=decoded.path;
   if(entry.cancelled||closed)return;
   const decodedAt=props.onTileTiming?Date.now():0;
   const bytes=await pixels(decoded,op=>entry.op=op,()=>closed||entry.cancelled);if(closed||entry.cancelled)return;
   const readAt=props.onTileTiming?Date.now():0;
   const handle=uploadImage(bytes,decoded.width,decoded.height);if(handle<0)throw new Error(props.messages?.upload??"Image upload failure");
   if(props.onTileTiming)props.onTileTiming({decodeMs:decodedAt-startedAt,readMs:readAt-decodedAt,uploadMs:Date.now()-readAt,bytes:bytes.length,hostTrace:decoded.trace});
   entry.handle=handle;entry.width=decoded.width;entry.height=decoded.height;entry.contentWidth=decoded.contentWidth??decoded.width;entry.contentHeight=decoded.contentHeight??decoded.height;publish();
  }catch(error){if(!closed&&!entry.cancelled)props.onError(error);}finally{entry.op=undefined;removeEntry(entry);active--;pump();finishLease();}
 }
 function pump(){if(closed||!ready)return;for(const entry of cache.values()){if(active>=4)break;if(entry.started||entry.cancelled)continue;entry.started=true;active++;void loadTile(entry);}}
 const plan=createMemo(()=>source()?visibleImageTiles({...props.geometry,imageWidth:source()!.width,imageHeight:source()!.height},props.state,props.tileOptions):[]);
 const keys=createMemo(()=>plan().map(tile=>tile.key).join("|"));
 createEffect(()=>{keys();untrack(()=>{const desired=new Map(plan().map(tile=>[tile.key,tile]));for(const [key,entry] of cache)if(!desired.has(key)){release(entry);cache.delete(key);}for(const [key,tile] of desired)if(!cache.has(key))cache.set(key,{tile,region:imageTileRegion(tile,source()!.width,source()!.height),handle:-1,width:0,height:0,contentWidth:0,contentHeight:0,cancelled:false,started:false});publish();pump();});});
 void (async()=>{
  let decodedPath:string|undefined;
  try {
   if(!props.filePath){const request=props.sourceProvider.acquire(props.url);initialOp=request;cached=await request.result;sourcePath=cached.path;if(closed)return;}
   const operation=hostOperation<Decoded>("image.decode",{path:sourcePath,maxDimension:512,...props.decodeOptions});initialOp=operation;const decoded=await operation.result;decodedPath=decoded.path;if(closed)return;
   const bytes=await pixels(decoded,op=>initialOp=op,()=>closed);if(closed)return;overviewHandle=uploadImage(bytes,decoded.width,decoded.height);if(overviewHandle<0)throw new Error(props.messages?.upload??"Image upload failure");if(overviewNode)getOps().setImage(overviewNode.id,overviewHandle);
   ready=true;props.onSize(decoded.sourceWidth,decoded.sourceHeight);setSource({width:decoded.sourceWidth,height:decoded.sourceHeight});pump();
  }catch(error){if(!closed)props.onError(error);}finally{initialOp=undefined;remove(decodedPath);finishLease();}
 })();
 onCleanup(()=>{closed=true;initialOp?.cancel();for(const entry of cache.values())release(entry);cache.clear();if(overviewHandle>=0)getOps().freeTexture?.(overviewHandle);void hostOperation("image.release",{}).result.catch(()=>{});finishLease();});
 const base=()=>previewPlacement(props.geometry);
 const unitX=()=>base().width/(source()?.width??1)*props.state.scale,unitY=()=>base().height/(source()?.height??1)*props.state.scale;
 const left=()=>base().left+props.state.x+(base().width-base().width*props.state.scale)/2,top=()=>base().top+props.state.y+(base().height-base().height*props.state.scale)/2;
 return <View class="absolute inset-0 overflow-hidden"><View class="absolute" style={{insetL:base().left,insetT:base().top,width:base().width,height:base().height,translateX:props.state.x,translateY:props.state.y,scale:props.state.scale,originX:0,originY:0}}><Image ref={node=>{overviewNode=node;if(overviewHandle>=0)getOps().setImage(node.id,overviewHandle);}} class="absolute inset-0" style={{width:base().width,height:base().height}}/></View>
  <For each={tiles()}>{entry=><View class="absolute overflow-hidden" style={{insetL:left()+entry.tile.x*unitX(),insetT:top()+entry.tile.y*unitY(),width:entry.tile.width*unitX(),height:entry.tile.height*unitY()}}><Image ref={node=>getOps().setImage(node.id,entry.handle)} class="absolute" style={{insetL:-entry.region.offsetX*unitX(),insetT:-entry.region.offsetY*unitY(),width:entry.width/entry.contentWidth*entry.region.width*unitX(),height:entry.height/entry.contentHeight*entry.region.height*unitY()}}/></View>}</For>
 </View>;
}
