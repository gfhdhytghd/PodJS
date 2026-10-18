import {hostOperation} from './services.ts';
export interface MediaPrefixStats {files:number;bytes:number;maxBytes:number;maxEntries:number;activePrefetches:number;downloadedBytes:number;networkReadBytes:number;cacheReadBytes:number;failures:number;bandwidthBytesPerSecond:number}
export interface MediaPrefixTarget {uri:string;prefetchBytes:number;captureBytes:number}
/** App-private range-aware Media3 cache. Registration is a process-local capability;
 * do not persist the URI. Original headers stay in the host and never reach disk. */
let revision=0;
export const mediaPrefixCache={
 register(key:string,url:string,headers:Record<string,string>){return hostOperation<{uri:string}>('video.cache.register',{key,url,headers});},
 window(items:readonly MediaPrefixTarget[],foregroundBitrate=0){return hostOperation<MediaPrefixStats>('video.cache.window',{items,foregroundBitrate,revision:++revision});},
 stats(){return hostOperation<MediaPrefixStats>('video.cache.stats',{});},
 clear(){return hostOperation<{removedBytes:number}>('video.cache.clear',{revision:++revision});},
};
