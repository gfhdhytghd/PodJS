import {cpuWork,cpuWorkAsync,cpuJsonParse,cpuJsonStringify} from './cpu-work';
import {sha256Hex} from './crypto.ts';
import {encodeUtf8} from './byte-codecs.ts';
export interface JsonJournalFiles {
 /** Bounded decoded chunks; chunk boundaries must preserve Unicode scalars. */
 chunks?(path:string):AsyncIterable<string>;
 /** Optional historical decoding of each raw LF-delimited frame, without LF.
  * Consulted only after the primary interpretation fails validation. */
 legacyLines?(path:string):AsyncIterable<string>;
 /** Atomically publish the verified decoded UTF-8 prefix (bytes), optionally adding a newline. */
 repair?(path:string,bytes:number,newline:boolean):Promise<void>;
 yieldTurn?():Promise<void>;
sha256?(bytes:Uint8Array):Promise<string>;read(path:string):Promise<string|undefined>;replace(path:string,text:string):Promise<void>;append(path:string,text:string,offset:number):Promise<void>;fingerprint?(path:string,raw:string):Promise<string>}
type ObjectValue=Record<string,any>;
type Edit={field:string;key?:string;remove?:true;value?:any};
const object=(v:any):v is ObjectValue=>!!v&&typeof v==='object'&&!Array.isArray(v);
const own=(v:ObjectValue,k:string)=>Object.prototype.hasOwnProperty.call(v,k);
const put=(v:ObjectValue,k:string,x:any)=>Object.defineProperty(v,k,{value:x,writable:true,enumerable:true,configurable:true});
/** Disposable local state. A complete checksummed frame is the commit unit;
 * torn tails never advance it. The base is immutable between compactions.
 * Dictionary entries are immutable JSON values: replace an entry to edit it.
 * This is not authentication, a remote ACK, or an application transaction. */
export class JsonDeltaJournal {
 private value:ObjectValue={};private snapshot:ObjectValue={};private base='';private basePrefixed=false;private sequence=0;private offset=0;private loaded=false;private loading:Promise<ObjectValue>|undefined;private writes:Promise<unknown>=Promise.resolve();
 constructor(private files:JsonJournalFiles,private path:string,private maps:readonly string[],private normalize:(v:ObjectValue)=>ObjectValue=v=>v,private denormalize:(v:ObjectValue)=>ObjectValue=v=>v,private maximum=32*1024*1024){}
 private digest(s:string){const bytes=encodeUtf8(s);return this.files.sha256?cpuWorkAsync('sha256','bytes',bytes.length,()=>this.files.sha256!(bytes)):Promise.resolve(sha256Hex(bytes));}
 private remember(value:ObjectValue){this.snapshot={...value};for(const field of this.maps)if(object(value[field]))this.snapshot[field]={...value[field]};}
 private pause(){return this.files.yieldTurn?.()??new Promise<void>(resolve=>setTimeout(resolve,0));}
 private async *lines():AsyncGenerator<{text:string;terminated:boolean}>{
  const path=this.path+'.delta';
  const chunks=this.files.chunks?this.files.chunks(path):(async function*(files:JsonJournalFiles){yield await files.read(path)??'';})(this.files);
  let pending='';
  for await(const chunk of chunks){let start=0,end:number;
   while((end=chunk.indexOf('\n',start))>=0){yield {text:pending+chunk.slice(start,end),terminated:true};pending='';start=end+1;}
   pending+=chunk.slice(start);
  }
  if(pending)yield {text:pending,terminated:false};
 }
 load():Promise<ObjectValue>{
  if(this.loading)return this.loading;
  if(this.loaded)return Promise.resolve(this.denormalize(this.value));
  const task=this.replay();this.loading=task;
  void task.finally(()=>{if(this.loading===task)this.loading=undefined;}).catch(()=>{});return task;
 }
 private async replay():Promise<ObjectValue>{
  // A failed IO/repair may be retried on the same instance.
  this.sequence=0;this.offset=0;
  let raw=await this.files.read(this.path)??'{}';this.basePrefixed=raw.startsWith('\n');this.base=this.files.fingerprint?await this.files.fingerprint(this.path,raw):await this.digest(raw);
  await this.pause();let parsed:ObjectValue={};try{const value=cpuJsonParse(raw);if(object(value))parsed=value;}catch{/* Derived cache loss never authorizes business cleanup. */}
  this.value=this.normalize(parsed);raw='';await this.pause();
  let repair=false,newline=false,legacyAccepted=false,sourceBytes=0,spentBytes=0,frames=0,started=Date.now();
  let legacy:AsyncIterator<string>|undefined,legacyIndex=0,lineIndex=0;
  const validated=async(line:string)=>{
   let f:any,payload:string|undefined;
   try{f=cpuJsonParse(line);if(f.base===this.base&&f.sequence===this.sequence+1&&Array.isArray(f.edits)&&f.edits.length<=100000)payload=cpuJsonStringify([f.base,f.sequence,f.edits]);}
   catch{/* Invalid JSON is a corrupt frame; host digest failures are not. */}
   return payload!==undefined&&await this.digest(payload)===f.digest?f:undefined;
  };
  try{await cpuWorkAsync('journal-rebuild','chars',0,async()=>{for await(const {text:line,terminated} of this.lines()){
   lineIndex++;
   if(!line){repair=true;break;}
   const bytes=encodeUtf8(line).length;let accepted=false;
   let f=await validated(line);
   if(!f&&this.files.legacyLines){
    legacy??=this.files.legacyLines(this.path+'.delta')[Symbol.asyncIterator]();
    let alternate:IteratorResult<string>|undefined;
    while(legacyIndex<lineIndex){alternate=await legacy.next();legacyIndex++;if(alternate.done)throw Error('json_journal_legacy_incomplete');if(legacyIndex%32===0)await this.pause();}
    f=await validated(alternate!.value!);
    if(f)legacyAccepted=true;
   }
   if(f){
    try{this.apply(f.edits);this.sequence++;sourceBytes+=bytes+(terminated?1:0);this.offset+=bytes+1;accepted=true;}
    catch{/* Stop at an invalid edit, exactly as the legacy reader. */}
   }
   if(!accepted){repair=true;break;}
   // Replacement characters can represent malformed source bytes. Canonicalize
   // accepted text before appending, as the legacy byte-length check did.
   if(line.includes('\ufffd'))repair=true;
   if(!terminated){repair=true;newline=true;}
   spentBytes+=bytes+1;frames++;
   if(spentBytes>=128*1024||frames>=32||Date.now()-started>=8){await this.pause();spentBytes=0;frames=0;started=Date.now();}
  }});}finally{await legacy?.return?.();}
  // Historical decoding can change byte lengths. Publish the authenticated
  // state at this safe checkpoint, never a prefix re-encoded by the other
  // decoder. This also canonicalizes the legacy frame before future appends.
  if(repair&&!legacyAccepted){
   if(this.files.repair)await this.files.repair(this.path+'.delta',sourceBytes,newline);
   else{let valid='',n=0;for await(const {text} of this.lines()){if(n++>=this.sequence)break;valid+=text+'\n';}await this.files.replace(this.path+'.delta',valid);}
  }
  // No caller has received a mutable value yet: this is a safe checkpoint.
  if(legacyAccepted||this.offset>=this.maximum||this.sequence>=2048){await this.pause();await this.compact(this.value);}
  this.remember(this.value);this.loaded=true;return this.denormalize(this.value);
 }
 private async compact(value:ObjectValue){
  // Alternate a JSON whitespace prefix so even a revert to the previous base
  // has a new content identity. A crash before delta cleanup must reject the
  // previous generation's frames. JSON readers (including legacy) accept it.
  const raw=(this.basePrefixed?'':'\n')+cpuJsonStringify(this.denormalize(value));
  try{
   // A host fingerprint may include more than the content hash. Preserve its
   // identity contract against the published base, as the legacy writer did.
   const digest=this.files.fingerprint?undefined:await this.digest(raw);
   await this.pause();await this.files.replace(this.path,raw);
   this.base=this.files.fingerprint?await this.files.fingerprint(this.path,raw):digest!;
   this.basePrefixed=raw.startsWith('\n');this.offset=0;this.sequence=0;await this.files.replace(this.path+'.delta','');
  }catch(error){
   // Publication may already have succeeded. Reopen before any later append.
   this.loaded=false;throw error;
  }
 }
 private apply(edits:Edit[]){for(const e of edits)if(!e||typeof e.field!=='string'||e.field.length>1024||e.key!==undefined&&(typeof e.key!=='string'||e.key.length>2048))throw Error('json_journal_invalid');for(const e of edits){const target=e.key===undefined?this.value:(own(this.value,e.field)&&this.value[e.field]&&typeof this.value[e.field]==='object'?this.value[e.field]:put(this.value,e.field,{} as ObjectValue)[e.field]);const key=e.key??e.field;if(e.remove)delete target[key];else put(target,key,e.value);}}
 commit(input:ObjectValue){const task=this.writes.then(()=>this.commitValue(input));this.writes=task.catch(()=>{});return task;}
 private async commitValue(input:ObjectValue){if(!this.loaded)await this.load();const next=this.normalize(input),edits:Edit[]=[];
  const count=this.maps.reduce((n,field)=>n+(object(next[field])?Object.keys(next[field]).length:0),0);cpuWork('journal-diff','items',count,()=>{for(const field of new Set([...Object.keys(this.snapshot),...Object.keys(next)])){
   if(!own(next,field)||next[field]===undefined){if(own(this.snapshot,field))edits.push({field,remove:true});continue;}
   if(this.maps.includes(field)&&object(next[field])){const value=next[field],before=this.snapshot[field],old=object(before)?before:{};if(!object(before))edits.push({field,value:{}});
    for(const key of new Set([...Object.keys(old),...Object.keys(value)]))if(!own(value,key)||value[key]===undefined){if(own(old,key))edits.push({field,key,remove:true});}else if(!own(old,key)||old[key]!==value[key]&&cpuJsonStringify(old[key])!==cpuJsonStringify(value[key]))edits.push({field,key,value:value[key]});
   }else if(this.snapshot[field]!==next[field]&&cpuJsonStringify(this.snapshot[field])!==cpuJsonStringify(next[field]))edits.push({field,value:next[field]});
  }
  });if(!edits.length)return {bytes:0,edits:0};
  const payload=cpuJsonStringify([this.base,this.sequence+1,edits]),frame=cpuJsonStringify({base:this.base,sequence:this.sequence+1,edits,digest:await this.digest(payload)})+'\n',bytes=encodeUtf8(frame).length;
  if(this.offset+bytes>this.maximum||this.sequence>=2048){
   // Normal committed data remains recoverable if replacement or truncation is
   // interrupted. Old frames cannot apply to the new base's digest.
   await this.compact(next);
  }else{try{await this.files.append(this.path+'.delta',frame,this.offset);}catch(error){this.loaded=false;throw error;}this.offset+=bytes;this.sequence++;}
  this.value=next;this.remember(next);return {bytes,edits:edits.length};
 }
}
