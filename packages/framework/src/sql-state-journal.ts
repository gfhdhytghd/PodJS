/** Authenticated incremental state in the same SQLite CAS as its business data.
 * The version is always read from SQLite. A cache never bypasses writer fences.
 * Inactive chunks are infrastructure; only the encrypted head activates them. */
import {sha256Hex} from './crypto';
import {encodeUtf8,encodeBase64,decodeBase64,utf8ToString} from './bytes';
import {nextD64,type ProjectionSql,type Statement} from './sql-projection';
export const SQL_STATE_SCHEMA='CREATE TABLE IF NOT EXISTS podjs_sql_state_chunks(binding TEXT NOT NULL,id TEXT NOT NULL,part INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(binding,id,part))';
export interface StateCipher {seal(aad:string,value:unknown):Promise<string>;open(aad:string,body:string):Promise<any>}
type Ref={id:string;hash:string;parts:number};
type Edit={path:string[];value?:unknown;remove?:true};
type Head={format:'podjs/sql-state-journal';version:1;ref:Ref;stateHash:string;depth:number};
const own=(x:any,k:string)=>Object.hasOwn(x,k),object=(x:any)=>x!==null&&typeof x==='object'&&!Array.isArray(x);
function diff(a:any,b:any,path:string[]=[],out:Edit[]=[]):Edit[]{
 if(a===b)return out;
 if(object(a)&&object(b)){for(const k of new Set([...Object.keys(a),...Object.keys(b)])){if(!own(b,k))out.push({path:[...path,k],remove:true});else if(!own(a,k))out.push({path:[...path,k],value:b[k]});else diff(a[k],b[k],[...path,k],out);}return out;}
 if(JSON.stringify(a)!==JSON.stringify(b))out.push({path,value:b});return out;
}
function patch(value:any,edits:Edit[]){for(const e of edits){if(!Array.isArray(e.path)||e.path.some(k=>typeof k!=='string'))throw Error('state_journal_invalid');if(!e.path.length){if(e.remove)throw Error('state_journal_invalid');value=e.value;continue;}let o=value;for(const k of e.path.slice(0,-1)){if(!object(o)||!own(o,k))throw Error('state_journal_invalid');o=o[k];}if(!object(o))throw Error('state_journal_invalid');const k=e.path.at(-1)!;if(e.remove)delete o[k];else Object.defineProperty(o,k,{value:e.value,writable:true,enumerable:true,configurable:true});}return value;}
export class SqlStateJournal<T extends object> {
 private cached?:{body:string;version:string;value:T;head?:Head};
 constructor(private db:ProjectionSql,private cipher:StateCipher,private binding:string,private aad:(purpose:string,id?:string)=>string,private serialize:(v:any)=>string=JSON.stringify,private chunkBytes=8192,private compactAfter=64){}
 private hash(v:any){return sha256Hex(encodeUtf8(this.serialize(v),'strict'));}
 private clone<V>(v:V):V{return JSON.parse(this.serialize(v));}
 invalidate(){this.cached=undefined;}
 async read(body:string,version:string):Promise<T>{
  if(this.cached?.body===body&&this.cached.version===version)return this.clone(this.cached.value);
  const parsed=await this.cipher.open(this.aad('state'),body);if(parsed?.format!=='podjs/sql-state-journal'){this.cached={body,version,value:this.clone(parsed)};return parsed;}
  const head=parsed as Head;if(head.version!==1||!Number.isInteger(head.depth)||head.depth<1||head.depth>this.compactAfter)throw Error('state_journal_invalid');
  const frames:any[]=[];let ref:Ref|undefined=head.ref;
  for(let n=0;ref;n++){if(n>=this.compactAfter)throw Error('state_journal_invalid');const frame=await this.frame(ref);frames.push(frame);ref=frame.previous;}
  if(frames.length!==head.depth)throw Error('state_journal_invalid');let value:any;let previousVersion:string|undefined;
  for(const frame of frames.reverse()){
   if(previousVersion!==undefined&&frame.version!==nextD64(previousVersion))throw Error('state_journal_invalid');
   if(frame.kind==='base'){if(previousVersion!==undefined)throw Error('state_journal_invalid');value=frame.value;}
   else if(frame.kind==='patch'&&previousVersion!==undefined)value=patch(value,frame.edits);else throw Error('state_journal_invalid');
   if(this.hash(value)!==frame.stateHash)throw Error('state_journal_invalid');previousVersion=frame.version;
  }
  if(previousVersion!==version||this.hash(value)!==head.stateHash)throw Error('state_journal_invalid');this.cached={body,version,value:this.clone(value),head};return value;
 }
 private async frame(ref:Ref){
  if(!/^[a-f0-9]{64}$/.test(ref.id)||!/^[a-f0-9]{64}$/.test(ref.hash)||!Number.isInteger(ref.parts)||ref.parts<1||ref.parts>8192)throw Error('state_journal_invalid');
  const chunks:Uint8Array[]=[];for(let after=0;after<ref.parts;after+=16){const rows=await this.db.query('SELECT part,body FROM podjs_sql_state_chunks WHERE binding=? AND id=? AND part>=? AND part<? ORDER BY part',[this.binding,ref.id,after,Math.min(after+16,ref.parts)]);if(rows.length!==Math.min(16,ref.parts-after))throw Error('state_journal_incomplete');for(const r of rows){const n=chunks.length;if(Number(r.part)!==n)throw Error('state_journal_invalid');const v=await this.cipher.open(this.aad('state-chunk',ref.hash+':'+n),String(r.body));if(v.part!==n||v.hash!==ref.hash)throw Error('state_journal_invalid');chunks.push(decodeBase64(v.data));}}
  const bytes=new Uint8Array(chunks.reduce((n,b)=>n+b.length,0));let at=0;for(const b of chunks){bytes.set(b,at);at+=b.length;}if(sha256Hex(bytes)!==ref.hash)throw Error('state_journal_invalid');return JSON.parse(utf8ToString(bytes));
 }
 async prepare(previous:T,next:T,version:string){
  const prior=this.cached,edits=diff(prior?.value??previous,next),stateHash=this.hash(next),base=!prior?.head||prior.head.depth>=this.compactAfter;
  const frame=base?{kind:'base',version,stateHash,value:next}:{kind:'patch',version,stateHash,previous:prior!.head!.ref,edits};
  const raw=encodeUtf8(this.serialize(frame),'strict'),hash=sha256Hex(raw),statements:Statement[]=[];let id='';
  const parts=Math.max(1,Math.ceil(raw.length/this.chunkBytes));if(parts>8192)throw Error('state_journal_too_large');
  for(let i=0;i<parts;i++){const body=await this.cipher.seal(this.aad('state-chunk',hash+':'+i),{hash,part:i,data:encodeBase64(raw.subarray(i*this.chunkBytes,(i+1)*this.chunkBytes))});if(!id)id=sha256Hex(encodeUtf8(body,'strict'));statements.push({sql:'INSERT INTO podjs_sql_state_chunks VALUES(?,?,?,?)',params:[this.binding,id,i,body]});}
  const head:Head={format:'podjs/sql-state-journal',version:1,ref:{id,hash,parts},stateHash,depth:base?1:prior!.head!.depth+1},body=await this.cipher.seal(this.aad('state'),head);
  // Large frames are paged durably before activation. Old state remains intact.
  let inline=statements;if(raw.length>32768){for(let i=0;i<statements.length;i+=2)await this.db.transaction(statements.slice(i,i+2));inline=[];}
  if(base)inline.push({sql:'DELETE FROM podjs_sql_state_chunks WHERE binding=? AND id!=?',params:[this.binding,id]});
  const accept=()=>{this.cached={body,version,value:this.clone(next),head};};return {body,statements:inline,accept,bytes:raw.length,edits:edits.length};
 }
}
