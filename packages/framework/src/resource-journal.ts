/** Durable detach-before-delete journal for derived immutable files only.
 * A retired resource can never be attached again. New downloads use new IDs.
 * The owner commits prepared statements with its application state CAS. */
import {nextD64,readSqlKeysetPage,type ProjectionSql,type Statement} from './sql-projection';
export type Resource={id:string;path:string;bytes:number};
export type CacheRow={id:string;revision:string;sort:string;value:string};
/** Completed native deletions survive a later failed commit. Keep the original
 * cause/message, so owners can report the completed prefix on takeover. */
export class ResourceCollectionError extends Error {
 readonly completed:Readonly<{freed:number;files:number}>;
 constructor(cause:unknown,completed:{freed:number;files:number}){super(cause instanceof Error?cause.message:String(cause),{cause});this.name='ResourceCollectionError';this.completed=Object.freeze({...completed});}
}
export class ResourceJournal {
 constructor(readonly db:ProjectionSql,readonly binding:string){}
 async init(){await this.db.transaction([
  {sql:'CREATE TABLE IF NOT EXISTS podjs_resource_rows(binding TEXT NOT NULL,id TEXT NOT NULL,revision TEXT NOT NULL,sort_key TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(binding,id))'},
  {sql:'CREATE UNIQUE INDEX IF NOT EXISTS podjs_resource_order ON podjs_resource_rows(binding,sort_key)'},
  {sql:"CREATE TABLE IF NOT EXISTS podjs_resources(binding TEXT NOT NULL,id TEXT NOT NULL,path TEXT NOT NULL,bytes INTEGER NOT NULL CHECK(bytes>=0),retired INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,authority TEXT NOT NULL DEFAULT '',PRIMARY KEY(binding,id),UNIQUE(binding,path))"},
  {sql:'CREATE TABLE IF NOT EXISTS podjs_resource_gc_cursor(binding TEXT PRIMARY KEY,last_id TEXT NOT NULL)'},
  {sql:'CREATE TABLE IF NOT EXISTS podjs_resource_refs(binding TEXT NOT NULL,owner TEXT NOT NULL,resource TEXT NOT NULL,PRIMARY KEY(binding,owner,resource))'},
  {sql:"CREATE TRIGGER IF NOT EXISTS podjs_resource_live BEFORE INSERT ON podjs_resource_refs WHEN NOT EXISTS(SELECT 1 FROM podjs_resources WHERE binding=NEW.binding AND id=NEW.resource AND retired=0) BEGIN SELECT RAISE(ABORT,'resource_retired'); END"},
 ]);}
 async row(id:string):Promise<CacheRow|null>{const r=(await this.db.query('SELECT id,revision,sort_key,value FROM podjs_resource_rows WHERE binding=? AND id=?',[this.binding,id]))[0];return r?{id:String(r.id),revision:String(r.revision),sort:String(r.sort_key),value:String(r.value)}:null;}
 async page(after='',limit=32){if(limit<1||limit>32)throw Error('resource_batch_limit');const {rows}=await readSqlKeysetPage(this.db,'SELECT id,revision,sort_key,value FROM podjs_resource_rows WHERE binding=? AND sort_key>? ORDER BY sort_key',[this.binding,after],limit);return rows.map(r=>({id:String(r.id),revision:String(r.revision),sort:String(r.sort_key),value:String(r.value)}));}
 register(resources:Resource[]):Statement[]{if(resources.length>32)throw Error('resource_batch_limit');const out:Statement[]=[{sql:'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))'}];for(const r of resources){if(!r.id||!r.path||!Number.isSafeInteger(r.bytes)||r.bytes<0)throw Error('invalid_resource');out.push({sql:'INSERT OR IGNORE INTO podjs_resources(binding,id,path,bytes) VALUES(?,?,?,?)',params:[this.binding,r.id,r.path,r.bytes]},{sql:'INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM podjs_resources WHERE binding=? AND id=? AND path=? AND bytes=? AND retired=0) THEN 1 ELSE 0 END',params:[this.binding,r.id,r.path,r.bytes]},{sql:'DELETE FROM podjs_sql_cas_guard'});}return out;}

 attach(owner:string,ids:string[]):Statement[]{if(ids.length>2048)throw Error('resource_batch_limit');return ids.map(id=>({sql:'INSERT OR IGNORE INTO podjs_resource_refs VALUES(?,?,?)',params:[this.binding,owner,id]}));}
 authority(owner:string,authority:string):Statement{return {sql:'UPDATE podjs_resources SET authority=? WHERE binding=? AND id IN (SELECT resource FROM podjs_resource_refs WHERE binding=? AND owner=?)',params:[authority,this.binding,this.binding,owner]};}
 detach(owner:string):Statement{return {sql:'DELETE FROM podjs_resource_refs WHERE binding=? AND owner=?',params:[this.binding,owner]};}
 insert(id:string,sort:string,value:string):Statement{return {sql:"INSERT INTO podjs_resource_rows VALUES(?,?,'0',?,?)",params:[this.binding,id,sort,value]};}
 /** Guard and replacement stay in the owner's short SQL transaction. */
 replace(row:CacheRow,value:string,sort=row.sort):Statement[]{return [
  {sql:'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))'},
  {sql:'INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN EXISTS(SELECT 1 FROM podjs_resource_rows WHERE binding=? AND id=? AND revision=?) THEN 1 ELSE 0 END',params:[this.binding,row.id,row.revision]},
  {sql:'DELETE FROM podjs_sql_cas_guard'},
  {sql:'UPDATE podjs_resource_rows SET revision=?,value=?,sort_key=? WHERE binding=? AND id=?',params:[nextD64(row.revision),value,sort,this.binding,row.id]},
 ];}
 async resourcePage(owner:string,after='',limit=32):Promise<Resource[]>{if(limit<1||limit>32)throw Error('resource_batch_limit');return (await this.db.query('SELECT r.id,r.path,r.bytes FROM podjs_resources r JOIN podjs_resource_refs f ON r.binding=f.binding AND r.id=f.resource WHERE r.binding=? AND f.owner=? AND r.id>? ORDER BY r.id LIMIT ?',[this.binding,owner,after,limit])).map(r=>({id:String(r.id),path:String(r.path),bytes:Number(r.bytes)}));}
 /** All persistent writes use authorize.commit, including scan progress and
  * deletion acknowledgements. The owner must support repeated commits and fence
  * each one. Empty commits refresh authorization immediately before native IO.
  * A completed discard is counted before its acknowledgement commit. */
 async collect(discard:(path:string)=>Promise<void|number>,measure:(paths:string[])=>Promise<number[]>,limit=32,authorize?:(rows:(Resource&{authority:string})[])=>Promise<{ids:string[];commit:(statements:Statement[])=>Promise<void>}>){
  if(limit<1||limit>32)throw Error('resource_batch_limit');let freed=0,files=0;
  try{
   const cursor=String((await this.db.query('SELECT last_id FROM podjs_resource_gc_cursor WHERE binding=?',[this.binding]))[0]?.last_id??'');const page=(after:string)=>this.db.query('SELECT r.id,r.path,r.bytes,r.authority FROM podjs_resources r WHERE r.binding=? AND r.id>? AND r.deleted=0 AND NOT EXISTS(SELECT 1 FROM podjs_resource_refs f WHERE f.binding=r.binding AND f.resource=r.id) ORDER BY r.id LIMIT ?',[this.binding,after,limit]);let candidates=await page(cursor);if(!candidates.length&&cursor)candidates=await page('');
   if(!candidates.length)return {freed,files};
   const auth=authorize?await authorize(candidates.map(r=>({id:String(r.id),path:String(r.path),bytes:Number(r.bytes),authority:String(r.authority)}))):{ids:candidates.map(r=>String(r.id)),commit:(s:Statement[])=>this.db.transaction(s)},allowed=candidates.filter(r=>auth.ids.includes(String(r.id)));
   // Even a denied page advances fairly, through the same owner-fenced commit.
   await auth.commit([
    {sql:'INSERT INTO podjs_resource_gc_cursor VALUES(?,?) ON CONFLICT(binding) DO UPDATE SET last_id=excluded.last_id',params:[this.binding,candidates.at(-1)!.id]},
    ...allowed.map(r=>({sql:'UPDATE podjs_resources SET retired=1 WHERE binding=? AND id=? AND authority=? AND NOT EXISTS(SELECT 1 FROM podjs_resource_refs f WHERE f.binding=podjs_resources.binding AND f.resource=podjs_resources.id)',params:[this.binding,r.id,r.authority]})),
   ]);
   if(!allowed.length)return {freed,files};
   const rows=await this.db.query('SELECT id,path,bytes FROM podjs_resources WHERE binding=? AND retired=1 AND deleted=0 AND id IN ('+allowed.map(()=>'?').join(',')+') ORDER BY id',[this.binding,...allowed.map(r=>r.id)]),sizes=await measure(rows.map(r=>String(r.path)));if(sizes.length!==rows.length||sizes.some(n=>!Number.isSafeInteger(n)||n<0))throw Error('invalid_resource_size');let index=0;
   for(const r of rows){
    if(authorize)await auth.commit([]);
    const actual=await discard(String(r.path)),bytes=typeof actual==='number'?actual:sizes[index]!;if(!Number.isSafeInteger(bytes)||bytes<0)throw Error('invalid_resource_size');freed+=bytes;files++;index++;
    await auth.commit([{sql:'UPDATE podjs_resources SET deleted=1 WHERE binding=? AND id=? AND retired=1',params:[this.binding,r.id]}]);
   }return {freed,files};
  }catch(error){if(files)throw new ResourceCollectionError(error,{freed,files});throw error;}
 }

}

/** Persistent conservative reservation. A crashed owner retains quota until its
 * staging records are reconciled; it never promises the same bytes twice. */
export class ResourceQuota {
 constructor(readonly db:ProjectionSql,readonly binding:string){}
 async reserve(id:string,bytes:number,available:number){
  if(!id||!Number.isSafeInteger(bytes)||bytes<0||!Number.isSafeInteger(available)||available<0)throw Error('storage_pressure');
  await this.db.transaction([{sql:'CREATE TABLE IF NOT EXISTS podjs_resource_quota(binding TEXT NOT NULL,id TEXT NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(binding,id))'},
   {sql:'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))'},
   {sql:'INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN COALESCE((SELECT SUM(bytes) FROM podjs_resource_quota WHERE binding=?),0)+?<=? THEN 1 ELSE 0 END',params:[this.binding,bytes,available]},
   {sql:'DELETE FROM podjs_sql_cas_guard'},{sql:'INSERT INTO podjs_resource_quota VALUES(?,?,?)',params:[this.binding,id,bytes]}]);
  return async()=>{await this.db.transaction([{sql:'DELETE FROM podjs_resource_quota WHERE binding=? AND id=?',params:[this.binding,id]}]);};
 }
}
