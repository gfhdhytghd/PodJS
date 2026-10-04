import {createEffect,createSignal,onCleanup,untrack,type JSX} from "solid-js";
import {View,Image,type NodeMirror} from "@pocketjs/framework/components";
import {getOps} from "@pocketjs/framework/host";
import {mediaSurfaces,type MediaSurfaceKind,type MediaSurfaceStyle,type MediaSurfaceState,type MediaSurfaceTextureSampling} from "./services.ts";
let sequence=0;
const owners=new Set<string>();
const presentationKeys=["leftPx","topPx","widthPx","heightPx","scale","opacity"] as const;
const snapshotStyle=(style:MediaSurfaceStyle):MediaSurfaceStyle=>({...style,...(style.presentationFrame===undefined?{}:{presentationFrame:{...style.presentationFrame}})});
function confirmPresentation(style:MediaSurfaceStyle,state:MediaSurfaceState){
 const requested=style.presentationFrame,actual=state.presentationFrame;
 if(requested===undefined){if(actual===undefined||actual===null)return;}
 else if(actual&&typeof actual==="object"&&!Array.isArray(actual)&&Number.isInteger(actual.widthPx)&&Number.isInteger(actual.heightPx)&&presentationKeys.every(key=>Number.isFinite(actual[key])&&Math.fround(actual[key])===Math.fround(requested[key])))return;
 throw Error("Media surface presentation frame unavailable");
}
export function bindMediaSurface(options:{kind:MediaSurfaceKind;path?:string;posterPath?:string;widthPx:number;heightPx:number;textureSampling?:MediaSurfaceTextureSampling;style:MediaSurfaceStyle},node:NodeMirror,ready:(state:MediaSurfaceState)=>void,onError?:(error:unknown)=>void){
 const id="media-surface-"+(++sequence),kind=options.kind,textureSampling=options.textureSampling,initialStyle=snapshotStyle(options.style);let closed=false,loaded=false,updating=false,dirty=false,style=initialStyle;
 owners.add(id);getOps().setImage(node.id,-1);
 const close=()=>{if(closed)return;closed=true;owners.delete(id);
  // Node teardown can make detaching fail; it must not retain the native session.
  try{getOps().setImage(node.id,-1);}catch{}finally{void mediaSurfaces.close(kind,id).catch(()=>{});}
 };
 const fail=(error:unknown)=>{if(closed)return;close();if(onError)onError(error);else console.warn("MediaSurface",String(error));};
 const flush=async()=>{if(!loaded||closed||updating)return;updating=true;try{while(dirty&&!closed){dirty=false;const sentStyle=snapshotStyle(style),value=await mediaSurfaces.update(kind,id,sentStyle);if(!closed){confirmPresentation(sentStyle,value);ready(value);}}}catch(error){fail(error);}finally{updating=false;}};
 // The owner ID exists before open completes. Disposal can always close a late
 // creation, including when its reply has not yet reached the guest.
 void mediaSurfaces.open(kind,{id,path:options.path,posterPath:options.posterPath,widthPx:options.widthPx,heightPx:options.heightPx,...initialStyle,...(textureSampling===undefined?{}:{textureSampling})}).then(value=>{
  if(closed)return;
  if(textureSampling==="linear"&&value.textureSampling!=="linear")throw Error("Media surface linear texture sampling unavailable");
  confirmPresentation(initialStyle,value);
  if(!Number.isInteger(value.texture)||value.texture<0)throw Error("Media surface texture unavailable");loaded=true;getOps().setImage(node.id,value.texture);ready(value);void flush();
 }).catch(fail);
 return {id,update(next:MediaSurfaceStyle){style=snapshotStyle(next);dirty=true;void flush();},close};
}
/** A native-owned texture remains in the scene: normal transforms, clipping and stacking apply. */
export function MediaSurface(props:{kind:MediaSurfaceKind;path?:string;posterPath?:string;revision?:string;widthPx:number;heightPx:number;pixelScale:number;textureSampling?:MediaSurfaceTextureSampling;style:MediaSurfaceStyle;fallback?:JSX.Element;onState?:(state:MediaSurfaceState)=>void;onError?:(error:unknown)=>void}){
 let node:NodeMirror|undefined;
 const [binding,setBinding]=createSignal<ReturnType<typeof bindMediaSurface>>(),[texture,setTexture]=createSignal<{width:number;height:number}>();
 createEffect(()=>{
  const kind=props.kind,path=props.path,posterPath=props.posterPath,widthPx=props.widthPx,heightPx=props.heightPx,textureSampling=props.textureSampling;void props.revision;
  if(!node)return;setTexture(undefined);
  const current=bindMediaSurface({kind,path,posterPath,widthPx,heightPx,textureSampling,style:untrack(()=>props.style)},node,value=>{setTexture(value);props.onState?.(value);},error=>{setTexture(undefined);if(props.onError)props.onError(error);else console.warn("MediaSurface",String(error));});setBinding(current);
  onCleanup(()=>{current.close();setBinding(undefined);});
 });
 createEffect(()=>{binding()?.update({...props.style});});
 return <View accessibilityHidden class="relative overflow-hidden" style={{width:props.widthPx/props.pixelScale,height:props.heightPx/props.pixelScale}}>
  <View class="absolute inset-0" style={{opacity:texture()?0:1}}>{props.fallback}</View>
  <Image ref={value=>{node=value;}} accessibilityHidden class="absolute left-[0] top-[0]" style={{width:(texture()?.width??props.widthPx)/props.pixelScale,height:(texture()?.height??props.heightPx)/props.pixelScale,opacity:texture()?1:0}}/>
 </View>;
}
export const mediaSurfaceOwners=()=>[...owners];
