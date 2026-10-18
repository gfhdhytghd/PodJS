import {defaultCachePorts, type CachePorts, type CacheProducer} from './cache.ts';

export interface FileDownload {url:string; headers:Record<string,string>}
export interface DownloadCacheOptions {
  root:string; minBytes:number; maxEntries:number; maxBytes:number;
  downloaded?:(bytes:number)=>void;
  failed?:(category:string)=>void;
  ports?:CachePorts; changed?:()=>Promise<unknown>;
}
/** Whole-file downloads, addressed by an opaque content identity. Resolution happens
 * immediately before transport, so queued signed URLs need not outlive their TTL.
 * Lookups never wait behind a network request. No URL or headers are persisted. */
export class DownloadCache implements CacheProducer {
  private ports:CachePorts;
  private tail:Promise<unknown>=Promise.resolve();
  private jobs=new Map<string,{path?:string;stopped:boolean;cancel():void;done:Promise<string|undefined>}>();
  private held=new Map<string,number>();
  private clearing=false;
  constructor(private options:DownloadCacheOptions) {
    if(!/^[\w-]+(?:\/[\w-]+)*$/.test(options.root) || !Number.isSafeInteger(options.minBytes) || options.minBytes<1 || !Number.isSafeInteger(options.maxEntries) || options.maxEntries<1 || !Number.isSafeInteger(options.maxBytes) || options.maxBytes<options.minBytes) throw new RangeError('invalid_download_cache_options');
    this.ports=options.ports??defaultCachePorts({maxDownloadBytes:options.maxBytes});
  }
  private async path(key:string) {
    if(!key.trim())throw Error('invalid_download_cache_key');
    const hash=await this.ports.digest(key);
    if(!/^[a-f0-9]{64}$/.test(hash))throw Error('invalid_download_cache_digest');
    return `${this.options.root}/${hash}.mp4`;
  }
  private async valid(path:string) {
    const stat=await this.ports.stat(path);
    return stat.file&&stat.size>=this.options.minBytes&&stat.size<=this.options.maxBytes;
  }
  async lookup(key:string):Promise<string|undefined> {
    if(this.clearing)return;
    const path=await this.path(key);
    if(this.clearing||!await this.valid(path))return;
    await this.ports.touch(path);
    return this.clearing?undefined:path;
  }
  /** Caller leases protect active playback from global and entry-count eviction. */
  pin(path:string):()=>void {
    if(!this.owns(path))throw Error('invalid_download_cache_path');
    this.held.set(path,(this.held.get(path)??0)+1);let released=false;
    return ()=>{if(released)return;released=true;const n=(this.held.get(path)??1)-1;if(n)this.held.set(path,n);else this.held.delete(path);};
  }
  pins():ReadonlySet<string> {return new Set([...this.held.keys(),...[...this.jobs.values()].flatMap(j=>j.path?[j.path]:[])]);}
  download(key:string,resolve:()=>Promise<FileDownload|undefined>):{result:Promise<string|undefined>;cancel():void} {
    if(this.clearing)return {result:Promise.resolve(undefined),cancel(){}};
    const existing=this.jobs.get(key);if(existing&&!existing.stopped)return {result:existing.done,cancel:existing.cancel};
    let stopped=false,request:{cancel():void}|undefined;
    const job={path:undefined as string|undefined,stopped:false,cancel(){stopped=true;job.stopped=true;request?.cancel();},done:Promise.resolve(undefined) as Promise<string|undefined>};
    const current=()=>!stopped&&!this.clearing;
    const work=this.tail.catch(()=>{}).then(async()=>{
      if(!current())return;
      job.path=await this.path(key);if(!current())return;
      const cached=await this.lookup(key);if(!current())return;if(cached)return cached;
      const source=await resolve();if(!source||!current())return;
      if(!/^https?:\/\//i.test(source.url))throw Error('invalid_download_cache_url');
      const op=this.ports.download(source.url,job.path,source.headers);request=op;
      await op.result;
      if(!current()||!await this.valid(job.path)){if(current())this.options.failed?.('invalid_size');await this.ports.remove(job.path);return;}
      await this.ports.touch(job.path);
      this.options.downloaded?.((await this.ports.stat(job.path)).size);
      return job.path;
    });
    job.done=work.catch(async(error:unknown)=>{this.options.failed?.(stopped?'cancelled':downloadFailureCategory(error));if(job.path)await this.ports.remove(job.path).catch(()=>{});return undefined;}).finally(async()=>{
      if(this.jobs.get(key)===job)this.jobs.delete(key);
      await this.trim().catch(()=>{});void this.options.changed?.().catch(()=>{});
    });
    this.jobs.set(key,job);this.tail=job.done;return {result:job.done,cancel:job.cancel};
  }
  async invalidate(key:string):Promise<void> {
    const job=this.jobs.get(key);job?.cancel();await job?.done;
    await this.ports.remove(await this.path(key));await this.options.changed?.();
  }
  private owns(path:string) {return path.startsWith(this.options.root+'/')&&/^[a-f0-9]{64}\.mp4$/.test(path.slice(this.options.root.length+1));}
  async trim():Promise<void> {
    const entries=(await this.ports.list(this.options.root)).entries.filter(e=>e.file&&this.owns(e.path)).sort((a,b)=>a.modified-b.modified||a.path.localeCompare(b.path));
    let excess=entries.length-this.options.maxEntries;const pins=this.pins();
    for(const file of entries){if(excess<=0)break;if(pins.has(file.path))continue;await this.ports.remove(file.path);excess--;}
  }
  /** Parent managed-cache clears files only after all writers have drained. */
  async clear():Promise<void> {this.clearing=true;for(const job of this.jobs.values())job.cancel();await this.tail;this.held.clear();}
  cleared():void {this.clearing=false;}
}

/** Fixed diagnostic vocabulary: never forward arbitrary transport messages/URLs. */
export function downloadFailureCategory(error:unknown):string {
  const message=String((error as {message?:unknown})?.message??'');
  const status=/http_status:(\d{3})\b/.exec(message);if(status)return 'http_'+status[1];
  for(const code of ['download_cancelled','download_too_large','invalid_max_bytes','redirect_limit','parent_unavailable'])if(message.includes(code))return code;
  return /timeout|timed out/i.test(message)?'timeout':'transport_error';
}
