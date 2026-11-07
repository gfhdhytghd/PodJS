/** Encrypted, bounded preparation for one native atomic SQL transaction.
 * Appending never changes active business data. The final marker is small;
 * native/host executors stream authenticated pages in the existing transaction. */
import {sha256Hex} from './crypto';
import {encodeUtf8,encodeBase64,decodeBase64,utf8ToString} from './bytes';
import type {ProjectionSql,Statement} from './sql-projection';
export const SQL_PROGRAM_SCHEMA=[
 'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))',
 'CREATE TABLE IF NOT EXISTS podjs_sql_programs(binding TEXT NOT NULL,id TEXT NOT NULL,status TEXT NOT NULL,pages INTEGER NOT NULL,fragments INTEGER NOT NULL,operations INTEGER NOT NULL,bytes INTEGER NOT NULL,root TEXT NOT NULL,PRIMARY KEY(binding,id))',
 'CREATE TABLE IF NOT EXISTS podjs_sql_program_pages(binding TEXT NOT NULL,id TEXT NOT NULL,page INTEGER NOT NULL,part INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(binding,id,page,part))',
 ...sqlProgramRelationGuards('podjs_sql_program_pages','binding','id','podjs_sql_program_pages'),
 "CREATE TRIGGER IF NOT EXISTS podjs_sql_program_ready BEFORE UPDATE ON podjs_sql_programs WHEN OLD.status='ready' BEGIN SELECT RAISE(ABORT,'sql_program_immutable');END",
];
/** Exact-set assertions belong in the encrypted program, before any writes.
 * Count plus disjoint authenticated key groups proves equality, including
 * equal-count substitution. Each assertion executes in the final transaction. */
export function sqlExactSetGuards(table:string,where:Record<string,string>,column:string,values:readonly string[]):Statement[]{
 const identifier=(v:string)=>{if(!/^[a-z_][a-z0-9_]*$/i.test(v))throw Error('sql_program_invalid');return v;};
 identifier(table);identifier(column);const entries=Object.entries(where);for(const [key]of entries)identifier(key);
 if(new Set(values).size!==values.length)throw Error('sql_program_invalid');
 const clause=entries.map(([key])=>key+'=?').join(' AND ')||'1',params=entries.map(([,value])=>value),out:Statement[]=[];
 const assert=(condition:string,args:(string|number)[])=>out.push({sql:'INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN '+condition+' THEN 1 ELSE 0 END',params:args},{sql:'DELETE FROM podjs_sql_cas_guard'});
 assert('(SELECT COUNT(*) FROM '+table+' WHERE '+clause+')=?',[...params,values.length]);
 for(let at=0;at<values.length;at+=32){const group=values.slice(at,at+32);assert('(SELECT COUNT(*) FROM '+table+' WHERE '+clause+' AND '+column+' IN ('+group.map(()=>'?').join(',')+'))=?',[...params,...group,group.length]);}
 return out;
}
/** Freeze every direction of a staged relation once its owner is ready. */
export function sqlProgramRelationGuards(table:string,binding:string,id:string,prefix:string){
 for(const v of [table,binding,id,prefix])if(!/^[a-z_][a-z0-9_]*$/i.test(v))throw Error('sql_program_invalid');
 return ['INSERT','UPDATE','DELETE'].map(op=>{const aliases=op==='UPDATE'?['OLD','NEW']:[op==='DELETE'?'OLD':'NEW'];return `CREATE TRIGGER IF NOT EXISTS ${prefix}_${op.toLowerCase()} BEFORE ${op} ON ${table} WHEN ${aliases.map(a=>`EXISTS(SELECT 1 FROM podjs_sql_programs WHERE binding=${a}.${binding} AND id=${a}.${id} AND status='ready')`).join(' OR ')} BEGIN SELECT RAISE(ABORT,'sql_program_immutable');END`;});
}
export interface SqlProgramCipher {seal(aad:string,value:unknown):Promise<string>;open(aad:string,body:string):Promise<any>;/** Native envelopes use nonceBase64/ciphertextBase64 AES-256-GCM. */nativeKey?():Promise<{keyBase64:string;aadJson:boolean}>}
export type SqlProgramRef={binding:string;id:string;pages:number;fragments:number;operations:number;bytes:number;root:string};
export type SqlProgramMarker=SqlProgramRef&{aadPrefix:string;keyBase64?:string;aadJson?:boolean;validateOnly?:boolean};
export type ProgramStatement=Statement&{program:SqlProgramMarker;sqlProgramKey?:()=>Promise<{keyBase64:string;aadJson:boolean}>};
export async function materializeSqlPrograms(statements:readonly Statement[]){const out:Statement[]=[];for(const s of statements){const p=s as ProgramStatement;if(!p.program){out.push(s);continue;}if(!p.sqlProgramKey)throw Error('sql_program_key_missing');out.push({...s,program:{...p.program,...await p.sqlProgramKey()}} as ProgramStatement);}return out;}
const validStatement=(s:any)=>s&&typeof s.sql==='string'&&!s.program&&!/^(BEGIN|COMMIT|ROLLBACK|ATTACH)\b/i.test(s.sql.trim())&&(s.params===undefined||Array.isArray(s.params)&&s.params.every((v:unknown)=>v===null||typeof v==='string'||typeof v==='number'&&Number.isFinite(v)));
const hash=(s:string)=>sha256Hex(encodeUtf8(s,'strict'));
export const sqlProgramChain=(prior:string,body:string)=>hash(prior+':'+body.length+':'+body);
export class SqlProgram {
 private ref?:SqlProgramRef;
 constructor(private db:ProjectionSql,private cipher:SqlProgramCipher,private binding:string,private id:string,private aadPrefix:string,private fragmentBytes=8192){}
 async open(){const row=(await this.db.query('SELECT * FROM podjs_sql_programs WHERE binding=? AND id=?',[this.binding,this.id]))[0];if(row){this.ref=this.parse(row);return row.status;}await this.db.transaction([{sql:'INSERT INTO podjs_sql_programs VALUES(?,?,?,0,0,0,0,?)',params:[this.binding,this.id,'building','0'.repeat(64)]}]);this.ref={binding:this.binding,id:this.id,pages:0,fragments:0,operations:0,bytes:0,root:'0'.repeat(64)};return 'building';}
 private parse(row:any):SqlProgramRef {const ref={binding:this.binding,id:this.id,pages:Number(row.pages),fragments:Number(row.fragments),operations:Number(row.operations),bytes:Number(row.bytes),root:String(row.root)};if(!/^[a-f0-9]{64}$/.test(ref.root)||Object.entries(ref).some(([k,v])=>['pages','fragments','operations','bytes'].includes(k)&&(!Number.isSafeInteger(v)||Number(v)<0)))throw Error('sql_program_invalid');return ref;}
 async append(statements:Statement[]){if(!this.ref)await this.open();let group:Statement[]=[],bytes=2;for(const s of statements){if((s as any).program)throw Error('sql_program_nested');if(!validStatement(s))throw Error('sql_program_invalid');const n=encodeUtf8(JSON.stringify(s),'strict').length;if(n+2>2*1024*1024)throw Error('sql_statement_too_large');if(group.length&&(bytes+n>32768||group.length>=64)){await this.page(group);group=[];bytes=2;}group.push(s);bytes+=n+1;}if(group.length)await this.page(group);return this.ref!;}
 private async page(statements:Statement[]){const old=this.ref!,raw=encodeUtf8(JSON.stringify(statements),'strict'),parts=Math.max(1,Math.ceil(raw.length/this.fragmentBytes));let root=old.root;const writes:Statement[]=[];
  for(let i=0;i<parts;i++){const body=await this.cipher.seal(this.aadPrefix+':'+old.pages+':'+i,{page:old.pages,part:i,parts,data:encodeBase64(raw.subarray(i*this.fragmentBytes,(i+1)*this.fragmentBytes))});root=sqlProgramChain(root,body);writes.push({sql:'INSERT INTO podjs_sql_program_pages VALUES(?,?,?,?,?)',params:[this.binding,this.id,old.pages,i,body]});}
  const next={...old,pages:old.pages+1,fragments:old.fragments+parts,operations:old.operations+statements.length,bytes:old.bytes+raw.length,root};
  // Large individual statements still cross the bridge as small encrypted parts.
  const guard:Statement[]=[{sql:'INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM podjs_sql_programs WHERE binding=? AND id=? AND status=? AND pages=? AND root=?) THEN 1 ELSE 0 END',params:[this.binding,this.id,'building',old.pages,old.root]},{sql:'DELETE FROM podjs_sql_cas_guard'}];
  const update:Statement={sql:'UPDATE podjs_sql_programs SET pages=?,fragments=?,operations=?,bytes=?,root=? WHERE binding=? AND id=?',params:[next.pages,next.fragments,next.operations,next.bytes,next.root,this.binding,this.id]};
  for(let i=0;i<writes.length;i+=2){const first=i===0,last=i+2>=writes.length;await this.db.transaction([...guard,...(first?[{sql:'DELETE FROM podjs_sql_program_pages WHERE binding=? AND id=? AND page>=?',params:[this.binding,this.id,old.pages]}]:[]),...writes.slice(i,i+2),...(last?[update]:[])]);}this.ref=next;
 }
 snapshot(){if(!this.ref)throw Error('sql_program_not_open');return {...this.ref};}
 async rewind(expected:SqlProgramRef){if(!this.ref)await this.open();if(expected.binding!==this.binding||expected.id!==this.id||expected.pages>this.ref!.pages)throw Error('sql_program_invalid');if(['binding','id','pages','fragments','operations','bytes','root'].every(k=>(expected as any)[k]===(this.ref as any)[k]))return;
  await this.db.transaction([{sql:"INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM podjs_sql_programs WHERE binding=? AND id=? AND status='building') THEN 1 ELSE 0 END",params:[this.binding,this.id]},{sql:'DELETE FROM podjs_sql_cas_guard'},{sql:'DELETE FROM podjs_sql_program_pages WHERE binding=? AND id=? AND page>=?',params:[this.binding,this.id,expected.pages]},{sql:'UPDATE podjs_sql_programs SET pages=?,fragments=?,operations=?,bytes=?,root=? WHERE binding=? AND id=?',params:[expected.pages,expected.fragments,expected.operations,expected.bytes,expected.root,this.binding,this.id]}]);this.ref={...expected};
 }
 async finish(){if(!this.ref)await this.open();await this.db.transaction([{sql:"UPDATE podjs_sql_programs SET status='ready' WHERE binding=? AND id=? AND status='building'",params:[this.binding,this.id]}]);return {...this.ref!};}
 async statement(ref:SqlProgramRef,validateOnly=false):Promise<ProgramStatement>{if(!this.cipher.nativeKey)throw Error('sql_program_unavailable');const statement:ProgramStatement={sql:'SELECT podjs_sql_program_required()',program:{...ref,aadPrefix:this.aadPrefix,...(validateOnly?{validateOnly:true}:{})}};Object.defineProperty(statement,'sqlProgramKey',{value:()=>this.cipher.nativeKey!()});return statement;}
}
/** Portable executor for SQLite host ports. Native executors use this exact
 * framing/chain contract; all yielded SQL executes under the caller's CAS. */
export async function* readSqlProgram(db:Pick<ProjectionSql,'query'>,cipher:Pick<SqlProgramCipher,'open'>,ref:SqlProgramMarker):AsyncGenerator<Statement>{
 const row=(await db.query('SELECT * FROM podjs_sql_programs WHERE binding=? AND id=?',[ref.binding,ref.id]))[0];if(!row||row.status!=='ready'||Number(row.pages)!==ref.pages||String(row.root)!==ref.root||Number(row.fragments)!==ref.fragments||Number(row.operations)!==ref.operations||Number(row.bytes)!==ref.bytes)throw Error('sql_program_incomplete');let root='0'.repeat(64),fragments=0,operations=0,totalBytes=0;
 for(let page=0;page<ref.pages;page++){const chunks:Uint8Array[]=[];let count=1;for(let part=0;part<count;part++){const rows=await db.query('SELECT body FROM podjs_sql_program_pages WHERE binding=? AND id=? AND page=? AND part=?',[ref.binding,ref.id,page,part]);if(rows.length!==1)throw Error('sql_program_incomplete');const body=String(rows[0]!.body);root=sqlProgramChain(root,body);const value=await cipher.open(ref.aadPrefix+':'+page+':'+part,body);if(value.page!==page||value.part!==part||!Number.isInteger(value.parts)||value.parts<1||value.parts>256||part&&count!==value.parts)throw Error('sql_program_invalid');count=value.parts;chunks.push(decodeBase64(value.data));fragments++;}
  const data=new Uint8Array(chunks.reduce((n,c)=>n+c.length,0));let at=0;for(const c of chunks){data.set(c,at);at+=c.length;}totalBytes+=data.length;if(data.length>2*1024*1024)throw Error('sql_program_invalid');const statements=JSON.parse(utf8ToString(data));if(!Array.isArray(statements)||statements.length>64)throw Error('sql_program_invalid');for(const s of statements){if(!validStatement(s))throw Error('sql_program_invalid');operations++;yield s;}
 }if(root!==ref.root||fragments!==ref.fragments||operations!==ref.operations||totalBytes!==ref.bytes)throw Error('sql_program_invalid');
}
