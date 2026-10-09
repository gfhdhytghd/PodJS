import {fileChunks,hostOperation,base64ToBytes,bytesToBase64,stringToUtf8,images} from './services.ts';
export interface CacheFile<B extends string=string> {path:string;bucket:B;size:number;modified:number}
export interface CacheNamespace {
 /** Flat filename produced after digesting URL and sorted headers. */
 filename(key:string,variant:string):string;
 ownsFile(name:string):boolean;
 stagedTarget(name:string):string|undefined;
 traverseDirectory(path:string):boolean;
 cleanupStaged:boolean;
}
export interface FileCacheErrors {limit:string;directories:string;path:string;metadata:string;files:string;remove:string;cancel:string;url:string;key:string;file:string}
const defaultErrors:FileCacheErrors={limit:'invalid_cache_limit',directories:'cache_directory_limit',path:'invalid_cache_path',metadata:'invalid_cache_metadata',files:'cache_file_limit',remove:'cache_remove_failed',cancel:'cache_cancelled',url:'invalid_cache_url',key:'invalid_cache_key',file:'invalid_cache_file'};
export interface FileCacheOptions<B extends string> {
 root:string;buckets:readonly B[];namespace(bucket:B):CacheNamespace;
 evictionPolicy(files:readonly CacheFile<B>[],limit:number,pinned:ReadonlySet<string>):readonly CacheFile<B>[];
 ports?:CachePorts;limitBytes?:number;concurrency?:number;maxDirectories?:number;maxFiles?:number;
 errors?:Partial<FileCacheErrors>;
}
export interface CacheStats<B extends string=string> {
 bytes:number;limitBytes:number;files:CacheFile<B>[];bucketBytes:Record<B,number>;
 jobs:{path:string;refs:number;started:boolean;cancelled:boolean}[];
 pinnedPaths:string[];activeWorkers:number;queuedRequests:number;downloadHandles:number;
}
/** Default transport retains the host's atomic http.download publication and cancellation. */
export function defaultCachePorts(options:{maxDownloadBytes?:number|((path:string)=>number);validateImageDimension?:number}={}):CachePorts {
 const maxBytes=options.maxDownloadBytes??16*1024*1024;
 if((typeof maxBytes!=='function'&&(!Number.isSafeInteger(maxBytes)||maxBytes<1))||(options.validateImageDimension!==undefined&&(!Number.isInteger(options.validateImageDimension)||options.validateImageDimension<1)))throw new RangeError('invalid cache transport bounds');
 return {
 list:fileChunks.list,stat:fileChunks.stat,remove:fileChunks.remove,touch:fileChunks.touch,
 async digest(value){const result=await hostOperation<{digestBase64:string}>('crypto.hash',{algorithm:'SHA-256',dataBase64:bytesToBase64(stringToUtf8(value))}).result;return Array.from(base64ToBytes(result.digestBase64),b=>b.toString(16).padStart(2,'0')).join('');},
 download:(url,path,headers)=>{const bound=typeof maxBytes==='function'?maxBytes(path):maxBytes;if(!Number.isSafeInteger(bound)||bound<1)throw new RangeError('invalid cache transport bounds');return hostOperation('http.download',{url,path,headers,maxBytes:bound});},
 ...(options.validateImageDimension===undefined?{}:{async validate(path:string){const decoded=await images.decodeOperation(path,{maxDimension:options.validateImageDimension}).result;await fileChunks.remove(decoded.path);}}),
};}
const safePath=(path:string)=>!!path&&!path.startsWith('/')&&!/[\\\x00]/.test(path)&&path.split('/').every(part=>!!part&&part!=='.'&&part!=='..');
export interface CacheEntry {path:string;file:boolean;directory:boolean;size:number;modified:number}
export interface CachePorts {
 list(path:string):Promise<{entries:CacheEntry[]}>;
 stat(path:string):Promise<{exists:boolean;file:boolean;size:number}>;
 remove(path:string):Promise<void>;touch(path:string):Promise<unknown>;validate?(path:string):Promise<void>;
 digest(value:string):Promise<string>;
 download(url:string,path:string,headers:Record<string,string>):{result:Promise<unknown>;cancel():void};
}
export interface CacheProducer { pins():ReadonlySet<string>; clear():Promise<void>; cleared?():void }
export interface CachedFile {path:string;release():void}
/** Compatibility alias for file-backed image consumers. */
export type CachedImage=CachedFile;
type Work={path:string;url:string;headers:Record<string,string>;refs:number;started:boolean;cancelled:boolean;current?:{cancel():void};promise:Promise<void>;resolve():void;reject(error:unknown):void};
export class FileCache<B extends string=string> {
 private producers=new Map<B,CacheProducer>();
 get limitBytes(){return this.limit;}
 registerProducer(bucket:B,producer:CacheProducer):void {if(!this.options.buckets.includes(bucket))throw Error(this.errors.path);if(this.producers.has(bucket))throw Error('cache_producer_exists');this.producers.set(bucket,producer);}
 private limit=64*1024*1024;
 private serial:Promise<unknown>=Promise.resolve();
 private jobs=new Map<string,Work>();private queue:Work[]=[];private active=0;
 private observers=new Set<(bytes:number)=>void>();private lastUsage:number|undefined;
 private ports:CachePorts;private errors:FileCacheErrors;
 constructor(private options:FileCacheOptions<B>){
  this.errors={...defaultErrors,...options.errors};
  if(!safePath(options.root)||!options.buckets.length||new Set(options.buckets).size!==options.buckets.length||options.buckets.some(b=>!safePath(b)||b.includes('/')))throw Error(this.errors.path);
  this.ports=options.ports??defaultCachePorts();this.limit=options.limitBytes??this.limit;
  if(!Number.isSafeInteger(this.limit)||this.limit<0)throw Error(this.errors.limit);
  for(const n of [options.concurrency??2,options.maxDirectories??1000,options.maxFiles??50000])if(!Number.isSafeInteger(n)||n<1)throw Error('invalid_cache_bound');
 }
 subscribe(listener:(bytes:number)=>void):()=>void {this.observers.add(listener);if(this.lastUsage!==undefined)listener(this.lastUsage);return ()=>this.observers.delete(listener);}
 private locked<T>(action:()=>Promise<T>):Promise<T>{const value=this.serial.then(action);this.serial=value.catch(()=>{});return value;}
 async configure(limit:number):Promise<number>{if(!Number.isSafeInteger(limit)||limit<0)throw Error(this.errors.limit);this.limit=limit;return this.maintain();}
 private async scan(cleanup=false):Promise<CacheFile<B>[]> {
  const files:CacheFile<B>[]=[];
  for(const bucket of this.options.buckets){
   const namespace=this.options.namespace(bucket);
   const root=`${this.options.root}/${bucket}`,directories=[root];let visited=0;
   while(directories.length){const directory=directories.shift()!;if(++visited>(this.options.maxDirectories??1000))throw Error(this.errors.directories);
    for(const entry of (await this.ports.list(directory)).entries){
     if(!entry.path.startsWith(directory+'/')||entry.path.slice(directory.length+1).includes('/')||!safePath(entry.path))throw Error(this.errors.path);
     if(entry.directory){if(namespace.traverseDirectory(entry.path))directories.push(entry.path);continue;}
     if(!entry.file)continue;
     const staged=namespace.stagedTarget(entry.path.slice(directory.length+1));
     if(cleanup&&namespace.cleanupStaged&&staged&&!this.jobs.has(directory+'/'+staged)&&!this.producers.get(bucket)?.pins().has(directory+'/'+staged)){await this.ports.remove(entry.path);continue;}
     if(!Number.isSafeInteger(entry.size)||entry.size<0||!Number.isFinite(entry.modified))throw Error(this.errors.metadata);
     // Include producer partial files in usage/eviction. In-flight files are pinned below.
     if(!namespace.ownsFile(entry.path.slice(directory.length+1)))continue;
     files.push({...entry,bucket});if(files.length>(this.options.maxFiles??50000))throw Error(this.errors.files);
    }
   }
  }
  return files;
 }
 private publish(files:CacheFile<B>[]):number {this.lastUsage=files.reduce((n,f)=>n+f.size,0);for(const listener of this.observers)if(this.lastUsage!==undefined)listener(this.lastUsage);return this.lastUsage;}
 inspect():Promise<number>{return this.locked(async()=>this.publish(await this.scan()));}
 /** Read-only disk accounting. No cleanup, touch, source URL, headers or credentials. */
 stats():Promise<CacheStats<B>>{return this.locked(async()=>{
  const files=await this.scan(),bucketBytes=Object.fromEntries(this.options.buckets.map(b=>[b,0])) as Record<B,number>;
  for(const f of files)bucketBytes[f.bucket]+=f.size;
  const jobs=[...this.jobs.values()].map(({path,refs,started,cancelled})=>({path,refs,started,cancelled}));
  const pinned=new Set(jobs.filter(j=>j.refs>0||j.started).map(j=>j.path));for(const p of this.producers.values())for(const path of p.pins())pinned.add(path);
  return {bytes:files.reduce((n,f)=>n+f.size,0),limitBytes:this.limit,files,bucketBytes,jobs,pinnedPaths:[...pinned],activeWorkers:this.active,queuedRequests:this.queue.length,downloadHandles:[...this.jobs.values()].filter(job=>job.current).length};
 });}
 maintain():Promise<number>{return this.locked(async()=>{
  const files=await this.scan(true),pinned=new Set([...this.jobs.values()].filter(j=>j.refs>0||j.started).map(j=>j.path));
  for(const producer of this.producers.values())for(const path of producer.pins())pinned.add(path);
  const candidates=this.options.evictionPolicy(files,this.limit,pinned),owned=new Set(files.map(f=>f.path));
  for(const entry of candidates){if(!owned.has(entry.path)||pinned.has(entry.path))throw Error(this.errors.path);await this.ports.remove(entry.path);if((await this.ports.stat(entry.path)).exists)throw Error(this.errors.remove);}
  return this.publish(await this.scan());
 });}
 /** Explicit flat-namespace clearing cancels jobs and runs the producer lifecycle. */
 clearBucket(bucket:B):Promise<number>{return this.locked(async()=>{
  if(!this.options.buckets.includes(bucket))throw Error(this.errors.path);
  const producer=this.producers.get(bucket);
  try {
  await producer?.clear();
  const root=`${this.options.root}/${bucket}`,prefix=root+'/';
  const jobs=[...this.jobs.values()].filter(job=>job.path.startsWith(prefix));
  for(const job of jobs){job.cancelled=true;job.current?.cancel();if(!job.started){this.queue=this.queue.filter(item=>item!==job);this.jobs.delete(job.path);job.reject(Error(this.errors.cancel));}}
  await Promise.allSettled(jobs.map(job=>job.promise));
  const entries=(await this.ports.list(root)).entries;
  for(const entry of entries){
   if(!entry.path.startsWith(prefix)||entry.path.slice(prefix.length).includes('/')||entry.directory||!entry.file||!safePath(entry.path))throw Error(this.errors.path);
   await this.ports.remove(entry.path);
   if((await this.ports.stat(entry.path)).exists)throw Error(this.errors.remove);
  }
  return this.publish(await this.scan());
  } finally {producer?.cleared?.();}
 });}
 /** Reserve a shared download synchronously after hashing; release never deletes a completed cache file. */
 acquire(url:string,headers:Record<string,string>={},request:{variant?:string;bucket?:B}={}):{result:Promise<CachedFile>;cancel():void}{
  let stopped=false,work:Work|undefined,released=false;
  const release=()=>{if(released)return;released=true;stopped=true;if(!work)return;if(--work.refs>0)return;if(!work.started){this.queue=this.queue.filter(j=>j!==work);this.jobs.delete(work.path);work.reject(Error(this.errors.cancel));}else{work.cancelled=true;work.current?.cancel();}void this.maintain().catch(()=>{});};
  const result=(async()=>{
   if(!/^https?:\/\//i.test(url))throw Error(this.errors.url);
   const headerKey=Object.keys(headers).sort().map(k=>[k,headers[k]]);const key=await this.ports.digest(JSON.stringify([url,headerKey]));
   if(!/^[a-f0-9]{64}$/.test(key))throw Error(this.errors.key);if(stopped)throw Error(this.errors.cancel);
   const bucket=request.bucket??this.options.buckets[0]!;if(!this.options.buckets.includes(bucket))throw Error(this.errors.path);
   const name=this.options.namespace(bucket).filename(key,request.variant??'');if(!safePath(name)||name.includes('/')||!this.options.namespace(bucket).ownsFile(name))throw Error(this.errors.path);
   const path=`${this.options.root}/${bucket}/${name}`;
   // The mutation runs in the same queue as eviction, so a leased path cannot be deleted between stat and decode.
   await this.locked(async()=>{
    if(stopped)throw Error(this.errors.cancel);let candidate=this.jobs.get(path);
    if(candidate?.cancelled){await candidate.promise.catch(()=>{});candidate=undefined;}
    // Cancellation while waiting must not release the old job or reserve a new lease.
    if(stopped)throw Error(this.errors.cancel);
    work=candidate;
    if(!work){let resolve!:()=>void,reject!:(error:unknown)=>void;const promise=new Promise<void>((yes,no)=>{resolve=yes;reject=no;});void promise.catch(()=>{});work={path,url,headers,refs:0,started:false,cancelled:false,promise,resolve,reject};this.jobs.set(path,work);this.queue.push(work);}
    work.refs++;this.pump();
   });
   await work!.promise;if(stopped)throw Error(this.errors.cancel);return {path,release};
  })();void result.catch(()=>release());return {result,cancel:release};
 }
 private pump(){while(this.active<(this.options.concurrency??2)&&this.queue.length){const work=this.queue.shift()!;work.started=true;this.active++;void this.load(work).then(work.resolve,work.reject).finally(()=>{work.current=undefined;work.started=false;this.active--;if(!work.refs&&this.jobs.get(work.path)===work)this.jobs.delete(work.path);this.pump();void this.maintain().catch(()=>{});});}}
 private async load(work:Work){
  if(work.cancelled)throw Error(this.errors.cancel);const current=await this.ports.stat(work.path);
  let valid=current.file&&current.size>0;
  if(valid&&this.ports.validate){try{await this.ports.validate(work.path);}catch{valid=false;}}
  if(!valid){if(work.cancelled)throw Error(this.errors.cancel);const request=this.ports.download(work.url,work.path,work.headers);work.current=request;await request.result;if(this.ports.validate)await this.ports.validate(work.path);}
  if(work.cancelled)throw Error(this.errors.cancel);const value=await this.ports.stat(work.path);if(!value.file||value.size<=0)throw Error(this.errors.file);await this.ports.touch(work.path);
 }
}

export function createFileCache<B extends string>(options:FileCacheOptions<B>):FileCache<B>{return new FileCache(options);}
