import {test,expect} from 'bun:test';
import {Database} from 'bun:sqlite';
import {ResourceJournal,ResourceQuota} from '../src/resource-journal';
function fixture(){const raw=new Database(':memory:'),db={query:async(sql:string,params:readonly unknown[]=[])=>raw.query(sql).all(...params as any[]) as Record<string,unknown>[],transaction:async(statements:any[])=>{raw.exec('BEGIN IMMEDIATE');try{for(const s of statements)raw.query(s.sql).run(...s.params??[]);raw.exec('COMMIT');}catch(e){raw.exec('ROLLBACK');throw e;}}},journal=new ResourceJournal(db,'test');return {raw,db,journal};}
const r={id:'one',path:'derived/one',bytes:12};
test('resource journal detaches owner before bounded physical collection',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction([...f.journal.register([r]),...f.journal.attach('body',[r.id]),f.journal.insert('body','a','encrypted')]);expect((await f.journal.collect(async()=>{throw Error('protected');},async()=>[])).freed).toBe(0);const row=(await f.journal.row('body'))!;await f.db.transaction([...f.journal.replace(row,'placeholder'),f.journal.detach('body')]);let exists=true;expect(await f.journal.collect(async()=>{exists=false;},async()=>[12])).toEqual({freed:12,files:1});expect(exists).toBe(false);expect((await f.journal.row('body'))!.value).toBe('placeholder');}finally{f.raw.close();}});
test('resource journal shared current and recovery owners protect a resource',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction([...f.journal.register([r]),...f.journal.attach('body',[r.id]),...f.journal.attach('recovery',[r.id]),f.journal.detach('body')]);expect((await f.journal.collect(async()=>{},async()=>[])).files).toBe(0);expect((await f.journal.resourcePage('recovery')).map(r=>r.id)).toEqual(['one']);}finally{f.raw.close();}});
test('resource journal lost deletion reply retries without double counting freed bytes',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction(f.journal.register([r]));let size=12;await expect(f.journal.collect(async()=>{size=0;throw Error('lost_reply');},async()=>[size])).rejects.toThrow('lost_reply');expect(await new ResourceJournal(f.db,'test').collect(async()=>{},async()=>[size])).toEqual({freed:0,files:1});}finally{f.raw.close();}});
test('resource journal retired identities cannot be reattached or revived',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction(f.journal.register([r]));await f.journal.collect(async()=>{},async()=>[12]);await expect(f.db.transaction(f.journal.attach('new',[r.id]))).rejects.toThrow('resource_retired');await expect(f.db.transaction(f.journal.register([r]))).rejects.toThrow();}finally{f.raw.close();}});
test('resource journal immutable identity allows same metadata and rejects replacement',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction(f.journal.register([r]));await f.db.transaction(f.journal.register([r]));for(const patch of [{bytes:13},{path:'derived/other'},{id:'other'}])await expect(f.db.transaction(f.journal.register([{...r,...patch}]))).rejects.toThrow();expect((await f.db.query('SELECT * FROM podjs_resources')).length).toBe(1);}finally{f.raw.close();}});
test('resource journal CAS rolls back stale metadata and reference detach together',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction([...f.journal.register([r]),...f.journal.attach('body',[r.id]),f.journal.insert('body','a','original')]);const row=(await f.journal.row('body'))!;await f.db.transaction(f.journal.replace(row,'opened'));await expect(f.db.transaction([...f.journal.replace(row,'placeholder'),f.journal.detach('body')])).rejects.toThrow();expect((await f.journal.row('body'))!.value).toBe('opened');expect((await f.journal.resourcePage('body')).length).toBe(1);}finally{f.raw.close();}});
test('resource journal pages metadata and resources at 32 items',async()=>{const f=fixture();try{await f.journal.init();for(let i=0;i<64;i++)await f.db.transaction([f.journal.insert(String(i),String(i).padStart(3,'0'),'encrypted')]);expect((await f.journal.page()).length).toBe(32);expect((await f.journal.page('031')).length).toBe(32);await expect(f.journal.page('',33)).rejects.toThrow('resource_batch_limit');expect(()=>f.journal.register(Array(33).fill(r))).toThrow('resource_batch_limit');}finally{f.raw.close();}});
test('resource quota reserves persistently and rejects overcommit',async()=>{const f=fixture();try{const quota=new ResourceQuota(f.db,'test'),release=await quota.reserve('a',70,100);await expect(new ResourceQuota(f.db,'test').reserve('b',31,100)).rejects.toThrow();expect(await f.db.query('SELECT id FROM podjs_resource_quota')).toEqual([{id:'a'}]);await release();const second=await quota.reserve('b',100,100);await second();expect(await f.db.query('SELECT * FROM podjs_resource_quota')).toEqual([]);}finally{f.raw.close();}});
test('resource journal staging reference prevents collection before owner commit',async()=>{const f=fixture();try{await f.journal.init();await f.db.transaction([...f.journal.register([r]),...f.journal.attach('stage',[r.id])]);expect((await f.journal.collect(async()=>{},async()=>[])).files).toBe(0);await f.db.transaction([...f.journal.attach('body',[r.id]),f.journal.detach('stage')]);expect((await f.journal.collect(async()=>{},async()=>[])).files).toBe(0);}finally{f.raw.close();}});

test('resource journal rejected owner commits neither GC cursor nor retirement',async()=>{
 const f=fixture();try{
  await f.journal.init();await f.db.transaction(f.journal.register([r]));const before=await f.db.query('SELECT * FROM podjs_resource_gc_cursor');let discarded=0;
  await expect(f.journal.collect(async()=>{discarded++;},async()=>[12],32,async()=>({ids:[r.id],commit:async statements=>f.db.transaction([{sql:'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))'},{sql:'INSERT INTO podjs_sql_cas_guard VALUES(0)'},...statements])}))).rejects.toThrow();
  expect(await f.db.query('SELECT * FROM podjs_resource_gc_cursor')).toEqual(before);expect(await f.db.query('SELECT retired,deleted FROM podjs_resources')).toEqual([{retired:0,deleted:0}]);expect(discarded).toBe(0);
 }finally{f.raw.close();}
});

test('resource journal late commit failure rolls back cursor and retirement together',async()=>{
 const f=fixture();try{
  await f.journal.init();await f.db.transaction([...f.journal.register([r]),{sql:'INSERT INTO podjs_resource_gc_cursor VALUES(?,?)',params:['test','old']}]);let discarded=0;
  await expect(f.journal.collect(async()=>{discarded++;},async()=>[12],32,async()=>({ids:[r.id],commit:async statements=>f.db.transaction([...statements,{sql:'INSERT INTO podjs_sql_cas_guard VALUES(0)'}])}))).rejects.toThrow();
  expect(await f.db.query('SELECT last_id FROM podjs_resource_gc_cursor')).toEqual([{last_id:'old'}]);expect(await f.db.query('SELECT retired,deleted FROM podjs_resources')).toEqual([{retired:0,deleted:0}]);expect(discarded).toBe(0);
 }finally{f.raw.close();}
});

test('resource journal denied pages advance only through the owner commit',async()=>{
 const f=fixture();try{
  await f.journal.init();await f.db.transaction(f.journal.register([r]));let commits=0;
  expect(await f.journal.collect(async()=>{throw Error('must_not_discard');},async()=>{throw Error('must_not_measure');},32,async()=>({ids:[],commit:async statements=>{commits++;await f.db.transaction(statements);}}))).toEqual({freed:0,files:0});
  expect(commits).toBe(1);expect(await f.db.query('SELECT last_id FROM podjs_resource_gc_cursor')).toEqual([{last_id:r.id}]);expect(await f.db.query('SELECT retired,deleted FROM podjs_resources')).toEqual([{retired:0,deleted:0}]);
 }finally{f.raw.close();}
});

test('resource journal refreshes owner permission after measurement and before native deletion',async()=>{
 const f=fixture();try{
  await f.journal.init();await f.db.transaction(f.journal.register([r]));let valid=true,discarded=0;
  await expect(f.journal.collect(async()=>{discarded++;},async()=>{valid=false;return [12];},32,async()=>({ids:[r.id],commit:async statements=>{if(!valid)throw Error('stale_owner');await f.db.transaction(statements);}}))).rejects.toThrow('stale_owner');
  expect(discarded).toBe(0);expect(await f.db.query('SELECT retired,deleted FROM podjs_resources')).toEqual([{retired:1,deleted:0}]);
 }finally{f.raw.close();}
});

test('resource journal retains completed bytes when deletion acknowledgement is rejected',async()=>{
 const {ResourceCollectionError}=await import('../src/resource-journal'),f=fixture();try{
  await f.journal.init();await f.db.transaction(f.journal.register([r]));let size=12,error:unknown;
  try{await f.journal.collect(async()=>{const n=size;size=0;return n;},async()=>[size],32,async()=>({ids:[r.id],commit:async statements=>{if(statements.some(s=>s.sql.startsWith('UPDATE podjs_resources SET deleted=1')))throw Error('stale_owner');await f.db.transaction(statements);}}));}catch(e){error=e;}
  expect(error).toBeInstanceOf(ResourceCollectionError);expect((error as InstanceType<typeof ResourceCollectionError>).completed).toEqual({freed:12,files:1});expect((error as Error).cause).toBeInstanceOf(Error);expect((error as Error).message).toBe('stale_owner');expect(await f.db.query('SELECT retired,deleted FROM podjs_resources')).toEqual([{retired:1,deleted:0}]);
  expect(await f.journal.collect(async()=>0,async()=>[size])).toEqual({freed:0,files:1});expect(await f.db.query('SELECT deleted FROM podjs_resources')).toEqual([{deleted:1}]);
 }finally{f.raw.close();}
});

test('resource journal preserves a completed file prefix before a later native failure',async()=>{
 const {ResourceCollectionError}=await import('../src/resource-journal'),f=fixture();try{
  const two={...r,id:'two',path:'derived/two'};await f.journal.init();await f.db.transaction(f.journal.register([r,two]));let error:unknown;
  try{await f.journal.collect(async path=>{if(path===two.path)throw Error('io_failure');return 12;},async()=>[12,12]);}catch(e){error=e;}
  expect(error).toBeInstanceOf(ResourceCollectionError);expect((error as InstanceType<typeof ResourceCollectionError>).completed).toEqual({freed:12,files:1});expect((error as Error).message).toBe('io_failure');expect(await f.db.query('SELECT id,retired,deleted FROM podjs_resources ORDER BY id')).toEqual([{id:'one',retired:1,deleted:1},{id:'two',retired:1,deleted:0}]);
 }finally{f.raw.close();}
});
