/** Encrypted normalized values with authenticated snapshot membership.
 * Reusing a value never rebinds it to an unauthenticated snapshot/key pair.
 * The caller commits returned writes under its own authoritative state CAS. */
import {hmacSha256Bytes,hex} from './crypto';
import {encodeUtf8} from './bytes';
import type {ProjectionSql,Statement} from './sql-projection';
import type {StateCipher} from './sql-state-journal';
export const SQL_AUTHENTICATED_CACHE_SCHEMA=[
 'CREATE TABLE IF NOT EXISTS podjs_sql_cache_values(binding TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(binding,id))',
 'CREATE TABLE IF NOT EXISTS podjs_sql_cache_refs(binding TEXT NOT NULL,snapshot TEXT NOT NULL,key TEXT NOT NULL,id TEXT NOT NULL,tag TEXT NOT NULL,PRIMARY KEY(binding,snapshot,key))',
];
export class SqlAuthenticatedCache<T> {
 constructor(private db:Pick<ProjectionSql,'query'>,private cipher:StateCipher,private binding:string,private key:Uint8Array,private serialize:(v:any)=>string=JSON.stringify){}
 private mac(parts:unknown[]){return hex(hmacSha256Bytes(this.key,encodeUtf8(this.serialize(['podjs/sql-authenticated-cache',this.binding,...parts]),'strict')));}
 private aad(id:string){return this.serialize(['podjs/sql-authenticated-cache/value',this.binding,id]);}
 async prepare(snapshot:string,entries:{key:string;value:T}[]):Promise<Statement[]>{if(entries.length>32)throw Error('cache_batch_limit');const out:Statement[]=[];for(const e of entries){const id=this.mac(['value',e.value]),tag=this.mac(['ref',snapshot,e.key,id]);const prior=(await this.db.query('SELECT id,tag FROM podjs_sql_cache_refs WHERE binding=? AND snapshot=? AND key=?',[this.binding,snapshot,e.key]))[0];if(prior?.id===id&&prior.tag===tag)continue;if(!(await this.db.query('SELECT 1 FROM podjs_sql_cache_values WHERE binding=? AND id=?',[this.binding,id])).length)out.push({sql:'INSERT OR IGNORE INTO podjs_sql_cache_values VALUES(?,?,?)',params:[this.binding,id,await this.cipher.seal(this.aad(id),e.value)]});out.push({sql:'INSERT INTO podjs_sql_cache_refs VALUES(?,?,?,?,?) ON CONFLICT(binding,snapshot,key) DO UPDATE SET id=excluded.id,tag=excluded.tag',params:[this.binding,snapshot,e.key,id,tag]});}return out;}
 private async decode(snapshot:string,row:any):Promise<T>{if(this.mac(['ref',snapshot,String(row.key),String(row.id)])!==row.tag)throw Error('cache_authentication_failed');const value=await this.cipher.open(this.aad(String(row.id)),String(row.body));if(this.mac(['value',value])!==row.id)throw Error('cache_authentication_failed');return value;}
 async get(snapshot:string,key:string){const row=(await this.db.query('SELECT r.key,r.id,r.tag,v.body FROM podjs_sql_cache_refs r JOIN podjs_sql_cache_values v ON r.binding=v.binding AND r.id=v.id WHERE r.binding=? AND r.snapshot=? AND r.key=?',[this.binding,snapshot,key]))[0];return row?this.decode(snapshot,row):null;}
 async page(snapshot:string,after='',limit=32){if(limit<1||limit>32)throw Error('cache_batch_limit');const rows=await this.db.query('SELECT r.key,r.id,r.tag,v.body FROM podjs_sql_cache_refs r JOIN podjs_sql_cache_values v ON r.binding=v.binding AND r.id=v.id WHERE r.binding=? AND r.snapshot=? AND r.key>? ORDER BY r.key LIMIT ?',[this.binding,snapshot,after,limit]);const out:T[]=[];for(const r of rows)out.push(await this.decode(snapshot,r));return out;}
 async count(snapshot:string){return Number((await this.db.query('SELECT COUNT(*) AS n FROM podjs_sql_cache_refs WHERE binding=? AND snapshot=?',[this.binding,snapshot]))[0]?.n??0);}
 dropExcept(snapshot:string):Statement[]{return [{sql:'DELETE FROM podjs_sql_cache_refs WHERE binding=? AND snapshot!=?',params:[this.binding,snapshot]},{sql:'DELETE FROM podjs_sql_cache_values WHERE binding=? AND NOT EXISTS(SELECT 1 FROM podjs_sql_cache_refs r WHERE r.binding=podjs_sql_cache_values.binding AND r.id=podjs_sql_cache_values.id)',params:[this.binding]}];}
}
