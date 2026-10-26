import {test,expect} from 'bun:test';
import {Database} from 'bun:sqlite';
import {querySqlTableInfo,querySqlPages} from '../src/sql';
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
