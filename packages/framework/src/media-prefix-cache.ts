import {hostOperation} from './services.ts';
import {__onHostEvent,hasCapability} from './watch.ts';
export interface MediaPrefetchResult {itemToken:string;generation:number;result:'success'|'failure';httpStatus:number;category:'success'|'http_403'|'http_4xx'|'http_5xx'|'transport'}
export interface MediaPrefixStats {files:number;bytes:number;maxBytes:number;maxEntries:number;activePrefetches:number;downloadedBytes:number;networkReadBytes:number;cacheReadBytes:number;failures:number;bandwidthBytesPerSecond:number}
export interface MediaPrefixTarget {itemToken?:string;generation?:number;uri:string;prefetchBytes:number;captureBytes:number}
/** App-private range-aware Media3 cache. Registration is a process-local capability;
 * do not persist the URI. Original headers stay in the host and never reach disk. */
let revision=0;
export const mediaPrefixCache={
 onResult(listener:(value:MediaPrefetchResult)=>void){return __onHostEvent(event=>{if(!hasCapability('media.video')||event.t!=='video.cache.result')return;const value=event.value as MediaPrefetchResult|undefined;if(value&&typeof value.itemToken==='string'&&/^[A-Za-z0-9_-]{1,96}$/.test(value.itemToken)&&Number.isSafeInteger(value.generation)&&value.generation>=0&&['success','failure'].includes(value.result)&&Number.isInteger(value.httpStatus)&&value.httpStatus>=0&&value.httpStatus<=599&&['success','http_403','http_4xx','http_5xx','transport'].includes(value.category))listener({itemToken:value.itemToken,generation:value.generation,result:value.result,httpStatus:value.httpStatus,category:value.category});});},
 register(key:string,url:string,headers:Record<string,string>){return hostOperation<{uri:string}>('video.cache.register',{key,url,headers});},
 window(items:readonly MediaPrefixTarget[],foregroundBitrate=0){return hostOperation<MediaPrefixStats>('video.cache.window',{items,foregroundBitrate,revision:++revision});},
 stats(){return hostOperation<MediaPrefixStats>('video.cache.stats',{});},
 clear(){return hostOperation<{removedBytes:number}>('video.cache.clear',{revision:++revision});},
};
