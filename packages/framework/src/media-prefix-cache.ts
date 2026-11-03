import {hostOperation} from './services.ts';
import {__onHostEvent,hasCapability} from './watch.ts';
export interface MediaPrefetchResult {itemToken:string;generation:number;result:'success'|'failure';httpStatus:number;category:'success'|'http_403'|'http_4xx'|'http_5xx'|'transport'}
export interface MediaPrefixStats {files:number;bytes:number;maxBytes:number;maxEntries:number;activePrefetches:number;downloadedBytes:number;networkReadBytes:number;cacheReadBytes:number;failures:number;bandwidthBytesPerSecond:number}
export interface MediaPrefixTarget {itemToken?:string;generation?:number;preStore?:boolean;uri:string;prefetchBytes:number;captureBytes:number}
/** App-private range-aware Media3 cache. Registration is a process-local capability; explicit persistent content keys reuse bytes after re-registration.
 * Do not persist the URI. Original headers stay in the host and never reach disk. */
let revision=0;
export const mediaPrefixCache={
 onResult(listener:(value:MediaPrefetchResult)=>void){return __onHostEvent(event=>{if(!hasCapability('media.video')||event.t!=='video.cache.result')return;const value=event.value as MediaPrefetchResult|undefined;if(value&&typeof value.itemToken==='string'&&/^[A-Za-z0-9_-]{1,96}$/.test(value.itemToken)&&Number.isSafeInteger(value.generation)&&value.generation>=0&&['success','failure'].includes(value.result)&&Number.isInteger(value.httpStatus)&&value.httpStatus>=0&&value.httpStatus<=599&&['success','http_403','http_4xx','http_5xx','transport'].includes(value.category))listener({itemToken:value.itemToken,generation:value.generation,result:value.result,httpStatus:value.httpStatus,category:value.category});});},
 register(key:string,url:string,headers:Record<string,string>,persistent=false,alternates:readonly string[]=[],expiresAt?:number,audioUrl?:string){return hostOperation<{uri:string}>('video.cache.register',{key,url,headers,persistent,alternates,expiresAt,audioUrl});},
 inspect(uri:string){return hostOperation<{prefixBytes:number;tailBytes:number;length:number}>('video.cache.inspect',{uri});},
 prepare(uri:string,bytes:number,frameKey:string){return hostOperation<{ready:boolean;prefixBytes:number;tailBytes:number;frame?:import('./services.ts').VideoFrame}>('video.cache.prepare',{uri,bytes,frameKey});},
 remove(uri:string){return hostOperation<void>('video.cache.remove',{uri});},
 window(items:readonly MediaPrefixTarget[],foregroundBitrate=0){return hostOperation<MediaPrefixStats>('video.cache.window',{items,foregroundBitrate,revision:++revision});},
 stats(){return hostOperation<MediaPrefixStats>('video.cache.stats',{});},
 clear(){return hostOperation<{removedBytes:number}>('video.cache.clear',{revision:++revision});},
};

export interface LocalMediaItem {audioTracks:number;videoTracks:number;key:string;metadata:Record<string,unknown>;uri:string;bytes:number;/** Combined video/audio input bytes; older manifests omit this field. */inputBytes?:number;sha256:string;storedAt:number;watched:boolean;frame:import('./services.ts').VideoFrame}
export interface LocalMediaConfig {enabled?:boolean;count?:number;entryBytes?:number;totalBytes?:number;headBytes?:number;frameBytes?:number;bytesPerSecond?:number;minBattery?:number;minFreeBytes?:number}
/** Verified whole files and frame-zero pixels. Lookup/list perform no network IO. */
export const localMediaStore={
 /** Resolves after an actual native window commit; the image stays over the player
  * until the matching decoded video texture commits. Cancellation never accepts
  * a late result. Call hide on route cancellation or playback failure. */
 present:(uri:string,bounds:{x:number;y:number;width:number;height:number},guestEntryMs:number)=>hostOperation<{presented:boolean;token:number}>('video.cache.local.present',{uri,bounds,guestEntryMs}),
 hide:(token:number)=>hostOperation<void>('video.cache.local.hide',{token}),
 configure:(config:LocalMediaConfig)=>hostOperation<void>('video.cache.local.configure',config as Record<string,unknown>),
 prepare:(key:string,uri:string,metadata:Record<string,unknown>,requireAudio=false)=>hostOperation<{ready:boolean;item?:LocalMediaItem}>('video.cache.local.prepare',{key,uri,metadata,requireAudio}),
 list:()=>hostOperation<{items:LocalMediaItem[]}>('video.cache.local.list',{}),
 lookup:(key:string)=>hostOperation<{item?:LocalMediaItem}>('video.cache.local.lookup',{key}),
 watched:(key:string)=>hostOperation<void>('video.cache.local.watched',{key}),
 cancel:()=>hostOperation<void>('video.cache.local.cancel',{}),
 clear:()=>hostOperation<void>('video.cache.local.clear',{}),
 stopTraffic:()=>hostOperation<void>('video.cache.stopTraffic',{}),
};
