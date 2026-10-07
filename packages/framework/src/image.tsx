import { imageFitRect } from "./image-fit.ts";
import { createEffect, createSignal, onCleanup } from "solid-js";
import { Image, View, type NodeMirror } from "@pocketjs/framework/components";
import { getOps } from "@pocketjs/framework/host";
import { getDisplayMetrics } from "./watch.ts";
import { base64ToBytes, hostOperation, hasTextureFileUpload, uploadTextureFile } from "./services.ts";

export interface ImageSource {
  url:string;
  filePath?:string;
  headers?:Record<string,string>;
  /** Provider-defined cache identity; include any policy affecting acquisition. */
  namespace?:string;
}
export interface ImageLease { path:string; release():void }
export interface ImageLoaderOptions<S extends ImageSource=ImageSource> {
  sourceProvider:{acquire(source:S):{result:Promise<ImageLease>;cancel():void}};
  logger?:{info(message:string):void;warn(message:string):void};
  maxConcurrent?:number;
  retainedBytes?:number;
  maxDimension?:number;
  messages?:Partial<Record<"dimensions"|"size"|"data"|"texture",string>>;
}
export interface RemoteImageProps<S extends ImageSource=ImageSource> {
  loader:ImageLoader<S>;
  source:S;
  width:number;
  height:number;
  class?:string;
  fit?:"stretch"|"cover"|"contain";
  onSize?:(width:number,height:number)=>void;
  onError?:(error:unknown)=>void;
}
export interface ImageLoader<S extends ImageSource=ImageSource> {
  bind(props:RemoteImageProps<S>,node:NodeMirror,onSize?:RemoteImageProps<S>["onSize"]):()=>void;
  releaseRetained():void;
  stats():Readonly<{active:number;queued:number;cached:number}>;
  dispose():void;
}
export {imageFitRect};

export function createImageLoader<S extends ImageSource>(options:ImageLoaderOptions<S>):ImageLoader<S> {
  const concurrency=options.maxConcurrent??3, retention=options.retainedBytes??8*1024*1024;
  const dimension=options.maxDimension??512;
  if(!Number.isInteger(concurrency)||concurrency<1||!Number.isFinite(retention)||retention<0
      ||!Number.isInteger(dimension)||dimension<1||dimension>512||(dimension&(dimension-1))!==0)throw new RangeError("invalid image loader limits");
  let disposed=false;
  const logConsole=options.logger??{info(_message:string){},warn(_message:string){}};
  interface DecodeResult { path: string; width: number; height: number; sourceWidth?:number; sourceHeight?:number }
  interface ChunkResult { dataBase64: string; offset: number; size: number; eof: boolean }
  interface Entry {
    key: string;
    refs: number;
    nodes: Map<NodeMirror, {onSize?: (width:number,height:number)=>void;onError?: (error:unknown)=>void}>;
    error?:unknown;
    sourceWidth?:number;
    sourceHeight?:number;
    cancelled: boolean;
    started: boolean;
    handle: number;
    inputPath?: string;
    outputPath?: string;
    current?: { cancel(): void };
    source:S;
    maxDimension: number;
  }

  const MAX_DECODED_BYTES = 512 * 512 * 4;
  const MAX_CONCURRENT = concurrency;
  let loggedScale = false;
  const entries = new Map<string, Entry>();
  const queue: Entry[] = [];
  let active = 0;

  function normalizedHeaders(headers?: Record<string, string>): string {
    if (!headers) return "";
    return Object.keys(headers).sort().map(key => `${key.toLowerCase()}:${headers[key]}`).join("\n");
  }

  function removeFile(path: string | undefined): void {
    if (!path) return;
    void hostOperation("file.delete", { path }).result.catch(() => {});
  }

  function cleanupFiles(entry:Entry):void {
    const input=entry.inputPath,output=entry.outputPath;
    entry.inputPath=undefined;entry.outputPath=undefined;
    removeFile(input);removeFile(output);
  }

  function decodedSize(decoded:DecodeResult):number {
    if (!Number.isInteger(decoded.width) || !Number.isInteger(decoded.height)
        || decoded.width < 1 || decoded.height < 1 || decoded.width > 512 || decoded.height > 512
        || (decoded.width & (decoded.width - 1)) !== 0 || (decoded.height & (decoded.height - 1)) !== 0) {
      throw new Error(options.messages?.dimensions??"Invalid image dimensions");
    }
    const size = decoded.width * decoded.height * 4;
    if (size > MAX_DECODED_BYTES) throw new Error(options.messages?.size??"Decoded image exceeds limit");
    return size;
  }

  async function readDecoded(entry: Entry, decoded: DecodeResult, size: number): Promise<Uint8Array> {
    const bytes = new Uint8Array(size);
    let offset = 0;
    while (offset < size) {
      const operation = hostOperation<ChunkResult>("file.read", { path: decoded.path, offset, maxBytes: Math.min(256 * 1024, size - offset) });
      entry.current = operation;
      const chunk = await operation.result;
      if (entry.cancelled) throw new Error("cancelled");
      const data = base64ToBytes(chunk.dataBase64);
      if (!data.length || data.length > size - offset || chunk.size !== size) throw new Error(options.messages?.data??"Incomplete image data");
      bytes.set(data, offset);
      offset += data.length;
    }
    return bytes;
  }

  async function load(entry: Entry): Promise<void> {
    let cached:ImageLease|undefined;
    try {
      let sourcePath=entry.source.filePath;
      if(!sourcePath){
        const request=options.sourceProvider.acquire(entry.source);
        entry.current=request;cached=await request.result;sourcePath=cached.path;if(entry.cancelled)return;
      }
      const decodeOperation = hostOperation<DecodeResult>("image.decode", { path: sourcePath, maxDimension: entry.maxDimension });
      entry.current = decodeOperation;
      const decoded = await decodeOperation.result;
      entry.outputPath = decoded.path;
      if (entry.cancelled) return;
      const size=decodedSize(decoded);
      let handle:number|null=null;
      if(hasTextureFileUpload()){
        // A supporting host consumes the generated path on every outcome.
        entry.outputPath=undefined;
        handle=uploadTextureFile(decoded.path,decoded.width,decoded.height,3);
        // null means no supporting host took ownership.
        if(handle===null)entry.outputPath=decoded.path;
      }
      if(handle===null){
        const rgba = await readDecoded(entry, decoded, size);
        if (entry.cancelled) return;
        handle = getOps().uploadTexture(rgba, decoded.width, decoded.height, 3);
      }
      if (handle < 0) throw new Error(options.messages?.texture??"Image texture upload failed");
      if (entry.cancelled) { getOps().freeTexture?.(handle); return; }
      entry.handle = handle;
      entry.sourceWidth=decoded.sourceWidth??decoded.width;entry.sourceHeight=decoded.sourceHeight??decoded.height;
      for (const [node,callbacks] of entry.nodes) {getOps().setImage(node.id, handle);callbacks.onSize?.(entry.sourceWidth,entry.sourceHeight);}
    } catch (error) {
      if (!entry.cancelled) {entry.error=error;logConsole.warn('RemoteImage request failed');for(const callbacks of entry.nodes.values())callbacks.onError?.(error);}
    } finally {
      entry.current = undefined;
      cached?.release();
      cleanupFiles(entry);
    }
  }

  function pump(): void {
    while (active < MAX_CONCURRENT && queue.length) {
      const entry = queue.shift()!;
      if (entry.cancelled || entry.started) continue;
      entry.started = true;
      active++;
      void load(entry).finally(() => { active--; pump(); });
    }
  }

  /** Released-but-finished textures, oldest first, bounded by decoded bytes. */
  const RETAINED_BYTES = retention;
  const retained: Entry[] = [];
  function textureBytes(entry: Entry) { return entry.maxDimension * entry.maxDimension * 4; }
  function dispose(entry: Entry): void {
    if(entry.cancelled)return;
    if(entries.get(entry.key)===entry)entries.delete(entry.key);
    entry.cancelled = true;
    entry.current?.cancel();
    if (entry.handle >= 0) getOps().freeTexture?.(entry.handle);
    cleanupFiles(entry);
  }
  function retain(entry: Entry): void {
    retained.push(entry);
    let total = retained.reduce((sum, item) => sum + textureBytes(item), 0);
    while (total > RETAINED_BYTES && retained.length > 0) { const oldest = retained.shift()!; total -= textureBytes(oldest); dispose(oldest); }
  }
  /** Free every retained texture (memory pressure, tests). */
  function releaseRetainedRemoteImages(): void { while (retained.length) dispose(retained.shift()!); }
  function revive(entry: Entry): void { const index = retained.indexOf(entry); if (index >= 0) retained.splice(index, 1); }

  function bindRemoteImage(props: RemoteImageProps<S>, node: NodeMirror, onSize?: (width:number,height:number)=>void): () => void {
    if(disposed)throw new Error("image loader disposed");
    // Decode at device pixels (logical × scale), like native Coil sizing to the view; capped by the 512px texture budget.
    const metrics = getDisplayMetrics(), pixelScale = metrics.logicalWidth > 0 ? metrics.physicalWidth / metrics.logicalWidth : 1;
    // The host texture is the largest power of two <= maxDimension, so round the request up.
    const devicePx = Math.ceil(Math.max(props.width, props.height) * pixelScale);
    const maximum = Math.max(1, Math.min(dimension, 2 ** Math.ceil(Math.log2(Math.max(1, devicePx)))));
    if (!loggedScale) { loggedScale = true; logConsole.info(`RemoteImage scale=${pixelScale} request=${props.width}x${props.height} maxDimension=${maximum}`); }
    const key = `${props.source.namespace??""}\u0000${props.source.filePath??""}\u0000${props.source.url}\u0000${maximum}\u0000${normalizedHeaders(props.source.headers)}`;
    let entry = entries.get(key);
    if (!entry) {
      entry = { key, refs: 0, nodes: new Map(), cancelled: false, started: false, handle: -1,
        source:props.source, maxDimension: maximum };
      entries.set(key, entry);
      queue.push(entry);
      pump();
    }
    if (entry.refs === 0) revive(entry);
    entry.refs++;
    entry.nodes.set(node,{onSize,onError:props.onError});
    if(entry.error!==undefined)props.onError?.(entry.error);
    if (entry.handle >= 0) {getOps().setImage(node.id, entry.handle);onSize?.(entry.sourceWidth!,entry.sourceHeight!);}
    let released=false;
    return () => {
      if(released)return;released=true;
      entry!.nodes.delete(node);
      getOps().setImage(node.id, -1);
      if (--entry!.refs > 0 || entry!.cancelled) return;
      // Virtualized rows unbind on scroll-out; keep finished textures briefly so scrolling back
      // does not re-run download/decode/read/upload (native Coil keeps a memory cache too).
      if (entry!.handle >= 0) { retain(entry!); return; }
      dispose(entry!);
    };
  }

  /** Test-only visibility into resource bounds, without exposing cache entries. */
  function remoteImageResourceCounts(): Readonly<{ active: number; queued: number; cached: number }> {
    return Object.freeze({ active, queued: queue.filter(item => !item.cancelled && !item.started).length, cached: entries.size });
  }
  return {bind:bindRemoteImage,releaseRetained:releaseRetainedRemoteImages,stats:remoteImageResourceCounts,
    dispose(){if(disposed)return;disposed=true;for(const entry of entries.values()){for(const node of entry.nodes.keys())getOps().setImage(node.id,-1);entry.nodes.clear();dispose(entry);}retained.length=0;queue.length=0;}};
}

/** Native remote image with bounded download, decode, GPU upload and cleanup. */
export function RemoteImage<S extends ImageSource>(props: RemoteImageProps<S>) {
  let node: NodeMirror | undefined;
  const [size,setSize]=createSignal({width:props.width,height:props.height});
  createEffect(() => {
    if (!node || !props.source.url) return;
    const release = props.loader.bind(props, node,(width,height)=>{setSize({width,height});props.onSize?.(width,height);});
    onCleanup(release);
  });
  const rect=()=>imageFitRect(props.width,props.height,size().width,size().height,props.fit);
  return props.fit && props.fit!=="stretch"
    ? <View class={props.class} style={{width:props.width,height:props.height}}><View style={{position:"relative",overflow:"hidden",width:props.width,height:props.height}}><Image ref={value => { node = value; }} style={{position:"absolute",insetL:rect().left,insetT:rect().top,width:rect().width,height:rect().height}} /></View></View>
    : <Image ref={value=>{node=value;}} class={props.class} style={{width:props.width,height:props.height}}/>;
}

