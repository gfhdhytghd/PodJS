// Historical 259042d loader: full read, then decode in the application port,
// then split and replay. Keep load/apply semantics independent of streaming.
import {cpuWork,cpuWorkAsync,cpuJsonParse,cpuJsonStringify} from './cpu-work';
import {sha256Hex} from './crypto.ts';
import {encodeUtf8} from './byte-codecs.ts';
export interface JsonJournalFiles {sha256?(bytes:Uint8Array):Promise<string>;read(path:string):Promise<string|undefined>;replace(path:string,text:string):Promise<void>;append(path:string,text:string,offset:number):Promise<void>;fingerprint?(path:string,raw:string):Promise<string>}
type ObjectValue=Record<string,any>;
type Edit={field:string;key?:string;remove?:true;value?:any};
const object=(v:any):v is ObjectValue=>!!v&&typeof v==='object'&&!Array.isArray(v);
const own=(v:ObjectValue,k:string)=>Object.prototype.hasOwnProperty.call(v,k);
const put=(v:ObjectValue,k:string,x:any)=>Object.defineProperty(v,k,{value:x,writable:true,enumerable:true,configurable:true});
/** Disposable local state. A complete checksummed frame is the commit unit;
 * torn tails never advance it. The base is immutable between compactions.
 * Dictionary entries are immutable JSON values: replace an entry to edit it.
 * This is not authentication, a remote ACK, or an application transaction. */
export class LegacyJsonDeltaJournal {
 private value:ObjectValue={};private snapshot:ObjectValue={};private base='';private sequence=0;private offset=0;private loaded=false;
 constructor(private files:JsonJournalFiles,private path:string,private maps:readonly string[],private normalize:(v:ObjectValue)=>ObjectValue=v=>v,private denormalize:(v:ObjectValue)=>ObjectValue=v=>v,private maximum=32*1024*1024){}
 private digest(s:string){const bytes=encodeUtf8(s);return this.files.sha256?cpuWorkAsync('sha256','bytes',bytes.length,()=>this.files.sha256!(bytes)):Promise.resolve(sha256Hex(bytes));}
 private remember(value:ObjectValue){this.snapshot={...value};for(const field of this.maps)if(object(value[field]))this.snapshot[field]={...value[field]};}
 async load():Promise<ObjectValue>{if(this.loaded)return this.denormalize(this.value);
  const raw=await this.files.read(this.path)??'{}';this.base=this.files.fingerprint?await this.files.fingerprint(this.path,raw):await this.digest(raw);let parsed:ObjectValue={};try{const value=cpuJsonParse(raw);if(object(value))parsed=value;}catch{/* Derived cache loss restarts from its immutable source, never SQL cleanup. */}this.value=this.normalize(parsed);const tail=await this.files.read(this.path+'.delta')??'';let end=0,valid='';
  await cpuWorkAsync('journal-rebuild','chars',tail.length,async()=>{for(const line of tail.split('\n')){if(!line)break;try{const f=cpuJsonParse(line),payload=cpuJsonStringify([f.base,f.sequence,f.edits]);if(f.base!==this.base||f.sequence!==this.sequence+1||!Array.isArray(f.edits)||f.edits.length>100000||await this.digest(payload)!==f.digest)break;
   this.apply(f.edits);this.sequence++;end+=encodeUtf8(line+'\n').length;valid+=line+'\n';
  }catch{break;}}
  });this.offset=end;if(end!==encodeUtf8(tail).length)await this.files.replace(this.path+'.delta',valid);
  this.loaded=true;this.remember(this.value);return this.denormalize(this.value);
 }
 private apply(edits:Edit[]){for(const e of edits)if(!e||typeof e.field!=='string'||e.field.length>1024||e.key!==undefined&&(typeof e.key!=='string'||e.key.length>2048))throw Error('json_journal_invalid');for(const e of edits){const target=e.key===undefined?this.value:(own(this.value,e.field)&&this.value[e.field]&&typeof this.value[e.field]==='object'?this.value[e.field]:put(this.value,e.field,{} as ObjectValue)[e.field]);const key=e.key??e.field;if(e.remove)delete target[key];else put(target,key,e.value);}}
 /** Internal handoff after the original loader has decided the prefix. */
 replayState(){return {value:this.value,base:this.base,sequence:this.sequence,offset:this.offset};}
}
