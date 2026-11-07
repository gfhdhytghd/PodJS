/** Bounded, restartable SQL building blocks. No import-time I/O. */
export type Statement={sql:string;params?:readonly unknown[]};
export interface ProjectionSql {query(sql:string,params?:readonly unknown[]):Promise<Record<string,unknown>[]>;transaction(statements:Statement[]):Promise<void>}
const identifier=(s:string)=>{if(!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(s))throw Error('invalid_sql_identifier');return s;};
export const MAX_D64='18446744073709551615';
export function checkD64(value:unknown):string {if(typeof value!=='string'||!/^(0|[1-9][0-9]{0,19})$/.test(value)||value.length===20&&value>MAX_D64)throw Error('invalid_schema');return value;}
export function compareD64(a:string,b:string){checkD64(a);checkD64(b);return a.length-b.length||(a<b?-1:a>b?1:0);}
export function nextD64(value:string){checkD64(value);if(value===MAX_D64)throw Error('counter_exhausted');const digits=value.split('');let i=digits.length-1;while(i>=0&&digits[i]==='9')digits[i--]='0';if(i<0)digits.unshift('1');else digits[i]=String(Number(digits[i])+1);return digits.join('');}
/** CHECK aborts the entire host transaction on stale state, even without a
 * reportChanges capability. All statements use the same native writer. */
export async function commitSqlCas(db:ProjectionSql,binding:{table:string;keyColumn:string;versionColumn:string;key:string},expected:string,statements:Statement[]){
 const next=nextD64(expected),t=identifier(binding.table),k=identifier(binding.keyColumn),v=identifier(binding.versionColumn);
 await db.transaction([{sql:'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))'},
 {sql:`INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM ${t} WHERE ${k}=? AND ${v}=?) THEN 1 ELSE 0 END`,params:[binding.key,expected]},
 {sql:'DELETE FROM podjs_sql_cas_guard'}, {sql:`UPDATE ${t} SET ${v}=CASE WHEN ${v}=? THEN ? ELSE NULL END WHERE ${k}=?`,params:[expected,next,binding.key]},...statements]);
 return next;
}
/** Read only one stable keyset page. The caller persists its last key with its
 * output; no OFFSET and no array containing the entire inventory. */
export async function readSqlKeysetPage(db:Pick<ProjectionSql,'query'>,query:string,params:readonly unknown[],limit=64){
 if(!Number.isInteger(limit)||limit<1||limit>256)throw Error('invalid_page_limit');
 for(;;){try{return {rows:await db.query(query+' LIMIT ?',[...params,limit]),limit};}catch(error){if(limit===1||!/query result too large/i.test(String(error)))throw error;limit=Math.max(1,Math.floor(limit/2));}}
}
const normalized=(sql:string)=>sql.replace(/^CREATE TRIGGER IF NOT EXISTS /i,'CREATE TRIGGER ').trim().replace(/;$/,'');
/** Install code-authored triggers only when their full stored definition
 * differs. Compare exact text conservatively; never collapse quoted content. */
export async function stableSqlTriggers(db:ProjectionSql,definitions:readonly string[],prefix:Statement[]=[]){
 const statements:Statement[]=[...prefix];let unchanged=0;
 for(const sql of definitions){const name=/^CREATE TRIGGER(?: IF NOT EXISTS)? ([a-zA-Z_][a-zA-Z0-9_]*)\b/i.exec(sql)?.[1];if(!name)throw Error('invalid_trigger_definition');
  const old=(await db.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?",[name]))[0];
  if(typeof old?.sql==='string'&&normalized(String(old.sql))===normalized(sql)){unchanged++;continue;}
  if(typeof old?.sql==='string')statements.push({sql:`DROP TRIGGER ${identifier(name)}`});statements.push({sql});
 }
 if(statements.length)await db.transaction(statements);return {ddl:statements.length-prefix.length,unchanged};
}

/** Copy a durable SQL revision after prepared writes, within their CAS transaction.
 * A version tag prevents an old basis from overriding a later encrypted state. */
export function captureSqlBasis(table:string,key:string,stateVersion:string,source:string):Statement{
 identifier(table);identifier(source);checkD64(stateVersion);
 return {sql:`INSERT INTO ${table}(scope,state_version,epoch,revision) SELECT ?,?,epoch,CAST(revision AS TEXT) FROM ${source} WHERE id=1 ON CONFLICT(scope) DO UPDATE SET state_version=excluded.state_version,epoch=excluded.epoch,revision=excluded.revision`,params:[key,stateVersion]};
}

/** Bound decrypted keyset scratch without assuming the SQL bridge can return
 * an entire page. A single large item may occupy the caller's item limit. */
export async function readBoundedKeysetBatch<T>(readOne:(after:string)=>Promise<T|undefined>,after:string,key:(item:T)=>string,size:(item:T)=>number,maxItems=16,maxBytes=65536){
 if(!Number.isInteger(maxItems)||maxItems<1||!Number.isSafeInteger(maxBytes)||maxBytes<1)throw Error('invalid_page_limit');
 const items:T[]=[];let bytes=0;
 while(items.length<maxItems){const item=await readOne(after);if(!item)break;const next=key(item),n=size(item);if(next<=after||!Number.isSafeInteger(n)||n<0)throw Error('invalid_keyset_page');if(items.length&&bytes+n>maxBytes)break;items.push(item);bytes+=n;after=next;if(bytes>=maxBytes)break;}
 return items;
}
