import {test,expect} from 'bun:test';
import {Database} from 'bun:sqlite';
import {createHash} from 'node:crypto';
import {onlyChangedSql} from '../src/sql-changes';
import {sha256Hex} from '../src/crypto';
test('conditional writes reuse bindings through CASE, nulls, quotes and existing predicates',()=>{
 const d=new Database(':memory:');try{d.exec('CREATE TABLE rows(id INTEGER PRIMARY KEY,value TEXT,n INTEGER)');
  const upsert=onlyChangedSql('INSERT INTO rows(id,value,n) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET value=CASE WHEN ?=1 THEN excluded.value ELSE rows.value END,n=excluded.n WHERE ?=1');
  expect(d.query(upsert).run(1,null,3,1,1).changes).toBe(1);expect(d.query(upsert).run(1,null,3,1,1).changes).toBe(0);expect(d.query(upsert).run(1,'changed',3,0,1).changes).toBe(0);expect(d.query(upsert).run(1,'changed',3,1,1).changes).toBe(1);
  const update=onlyChangedSql("UPDATE rows SET value=COALESCE(?, 'a,b? WHERE literal'),n=? WHERE id=?");expect(d.query(update).run('changed',3,1).changes).toBe(0);expect(d.query(update).run(null,4,1).changes).toBe(1);expect(d.query(update).run(null,4,1).changes).toBe(0);
  expect(onlyChangedSql('DELETE FROM rows WHERE id=?')).toBe('DELETE FROM rows WHERE id=?');expect(onlyChangedSql('INSERT OR IGNORE INTO rows VALUES(?,?,?)')).toBe('INSERT OR IGNORE INTO rows VALUES(?,?,?)');
 }finally{d.close();}
});
test('portable/native byte SHA256 agrees on empty, binary, multiblock and Unicode bytes',()=>{
 const prior=(globalThis as any).pod;try{(globalThis as any).pod=undefined;
  const cases=[Buffer.alloc(0),Buffer.from('abc'),Buffer.from('中文🙂'),Buffer.from(Array.from({length:1025},(_,i)=>i%256))];
  for(const b of cases)expect(sha256Hex(b)).toBe(createHash('sha256').update(b).digest('hex'));
  let calls=0;(globalThis as any).pod={sha256Bytes:(b:Uint8Array)=>{calls++;return new Uint8Array(createHash('sha256').update(b).digest());}};for(const b of cases)expect(sha256Hex(b)).toBe(createHash('sha256').update(b).digest('hex'));expect(calls).toBe(cases.length);
 }finally{(globalThis as any).pod=prior;}
});
