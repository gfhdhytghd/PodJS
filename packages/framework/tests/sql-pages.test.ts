import {test,expect} from 'bun:test';
import {Database} from 'bun:sqlite';
import {querySqlTableInfo,querySqlPages,querySqlKeysetPages} from '../src/sql';
test('batched metadata is byte-identical to PRAGMA columns including quoted names, defaults and views',async()=>{
 const sql=new Database(':memory:');try{
  const names=Array.from({length:42},(_,i)=>'table_'+i).concat('中文"表');
  for(const name of names)sql.exec('CREATE TABLE "'+name.replaceAll('"','""')+'"(id INTEGER PRIMARY KEY,value TEXT NOT NULL DEFAULT \'a"b\',extra REAL)');
  sql.exec('CREATE VIEW v AS SELECT * FROM table_0');names.push('v','missing');let calls=0;
  const db={query:async(q:string,p:readonly unknown[]=[])=>{calls++;return sql.query(q).all(...p as any[]) as Record<string,unknown>[];}};
  const actual=await querySqlTableInfo(db,names);expect(calls).toBe(1);
  for(const name of names)expect(JSON.stringify(actual.get(name))).toBe(JSON.stringify(sql.query('PRAGMA table_info("'+name.replaceAll('"','""')+'")').all()));
 }finally{sql.close();}
});
test('metadata fallback discards partial batches, quotes names and propagates real database failures',async()=>{
 const queries:string[]=[];const rows=[{cid:0,name:'x',type:'TEXT',notnull:0,dflt_value:null,pk:0}];
 const out=await querySqlTableInfo({query:async q=>{queries.push(q);if(q.startsWith('SELECT'))throw Error('no such table: pragma_table_info');return rows;}},['x"y','x"y']);
 expect(queries).toHaveLength(2);expect(queries[1]).toBe('PRAGMA table_info("x""y")');expect(out.get('x"y')).toEqual(rows);
 await expect(querySqlTableInfo({query:async()=>{throw Error('database is locked');}},['x'])).rejects.toThrow('locked');
});
test('page sizing still backs off on byte caps without losing or duplicating rows',async()=>{
 const all=Array.from({length:11},(_,n)=>({n}));let rejected=0;
 const rows=await querySqlPages({query:async(_q,p=[])=>{const [size,offset]=p as number[];if(size!>3){rejected++;throw Error('Query result too large; use paging');}return all.slice(offset,offset+size);}},'SELECT n FROM t ORDER BY n',[],8);
 expect(rows).toEqual(all);expect(rejected).toBe(2);
});


test('keyset pages preserve 5500 sparse filtered rows without repeated OFFSET scans',async()=>{
 const sql=new Database(':memory:');try{
  sql.exec('CREATE TABLE items(id INTEGER PRIMARY KEY,kind TEXT,value TEXT)');sql.transaction(()=>{const put=sql.prepare('INSERT INTO items VALUES(?,?,?)');for(let i=1;i<=6000;i++)put.run(i*3,i<=5500?'rss':'book','v'+i);})();
  let calls=0;const rows=await querySqlKeysetPages({query:async(q,p=[])=>{calls++;expect(q).not.toContain('OFFSET');return sql.query(q).all(...p as any[]) as Record<string,unknown>[];}},'SELECT id,value FROM items WHERE kind=? AND id>? ORDER BY id',['rss']);
  expect(rows).toEqual(sql.query("SELECT id,value FROM items WHERE kind='rss' ORDER BY id").all() as Record<string,unknown>[]);expect(calls).toBe(14);
 }finally{sql.close();}
});
test('keyset byte backoff retries the same cursor and retains large final rows',async()=>{
 const all=Array.from({length:101},(_,i)=>({id:i*2+1,value:'x'.repeat(8192)})),attempts:number[][]=[];
 const rows=await querySqlKeysetPages({query:async(_q,p=[])=>{const [after,size]=p as number[];attempts.push([after!,size!]);const page=all.filter(r=>r.id>after!).slice(0,size);if(JSON.stringify(page).length>512*1024)throw Error('Query result too large');return page;}},'SELECT id,value FROM items WHERE id>? ORDER BY id');
 expect(rows).toEqual(all);expect(attempts.slice(0,4)).toEqual([[0,400],[0,200],[0,100],[0,50]]);expect(attempts.at(-1)).toEqual([199,50]);
});
test('keyset rejects non-progressing keys, invalid options, oversized results and real SQL errors',async()=>{
 for(const bad of [[{id:1},{id:1}],[{id:2},{id:1}],[{}],[{id:Infinity}],[{id:Number.MAX_SAFE_INTEGER+1}]])await expect(querySqlKeysetPages({query:async()=>bad},'SELECT')).rejects.toThrow('cursor did not advance');
 let calls=0;for(const options of [{key:''},{key:'id',after:-1},{key:'id',pageSize:0},{key:'id',pageSize:1001},{key:'id',after:NaN}])await expect(querySqlKeysetPages({query:async()=>{calls++;return [];}},'SELECT',[],options)).rejects.toThrow('options');expect(calls).toBe(0);
 await expect(querySqlKeysetPages({query:async()=>[{id:1},{id:2}]},'SELECT',[],{key:'id',pageSize:1})).rejects.toThrow('exceeds');
 await expect(querySqlKeysetPages({query:async()=>{throw Error('database locked');}},'SELECT')).rejects.toThrow('database locked');
 expect(await querySqlKeysetPages({query:async()=>[]},'SELECT')).toEqual([]);
});
