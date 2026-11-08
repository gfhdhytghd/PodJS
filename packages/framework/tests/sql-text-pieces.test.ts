import {test,expect} from 'bun:test';
import {Database} from 'bun:sqlite';
import {readSqlTextPieces} from '../src/sql-projection';
const fixture=()=>{const raw=new Database(':memory:');raw.exec('CREATE TABLE t(id TEXT PRIMARY KEY,body TEXT)');return {raw,db:{query:async(q:string,p:readonly unknown[]=[])=>raw.query(q).all(...p as any[]) as Record<string,unknown>[]}};};
test('long TEXT with U+0000, surrogate pairs and multibyte characters on piece boundaries reads back exactly',async()=>{
 const {raw,db}=fixture();try{
  // SQLite's TEXT length()/substr() stop at U+0000; the byte reader must not.
  const piece=16,cases=['head\u0000tail','\u0000'.repeat(40),'a'.repeat(15)+'中'+'b'.repeat(30),'a'.repeat(14)+'😀'+'\u0000'+'😀'.repeat(9),'é'.repeat(33),'中'.repeat(5)+'\u0000'+'😀'+'x'.repeat(17),''];
  for(let shift=0;shift<4;shift++)cases.push('z'.repeat(shift)+'😀中é\u0000'.repeat(12));
  for(const [i,text] of cases.entries()){raw.query('INSERT INTO t VALUES(?,?)').run(String(i),text);
   expect((raw.query('SELECT length(body) AS n FROM t WHERE id=?').get(String(i)) as any).n).toBe([...text.split('\u0000')[0]!].length);
   expect(await readSqlTextPieces(db,{table:'t',column:'body',key:{id:String(i)}},piece)).toBe(text);}
  raw.query('INSERT INTO t VALUES(?,NULL)').run('null');expect(await readSqlTextPieces(db,{table:'t',column:'body',key:{id:'null'}},piece)).toBeNull();
  expect(await readSqlTextPieces(db,{table:'t',column:'body',key:{id:'missing'}},piece)).toBeNull();
  const big='前\u0000'+'中'.repeat(100000)+'😀'.repeat(1000);raw.query('INSERT INTO t VALUES(?,?)').run('big',big);let calls=0;
  expect(await readSqlTextPieces({query:async(q,p)=>{calls++;return db.query(q,p);}},{table:'t',column:'body',key:{id:'big'}})).toBe(big);expect(calls).toBe(1+Math.ceil(new TextEncoder().encode(big).length/65536));
 }finally{raw.close();}
});
test('invalid identifiers and invalid stored UTF-8 fail closed',async()=>{
 const {raw,db}=fixture();try{
  await expect(readSqlTextPieces(db,{table:'t;DROP',column:'body',key:{id:'1'}})).rejects.toThrow('invalid_sql_identifier');
  raw.exec("INSERT INTO t VALUES('bad',CAST(X'61FF62' AS TEXT))");await expect(readSqlTextPieces(db,{table:'t',column:'body',key:{id:'bad'}},4)).rejects.toThrow();
  raw.exec("INSERT INTO t VALUES('cut',CAST(X'6162E4B8' AS TEXT))");await expect(readSqlTextPieces(db,{table:'t',column:'body',key:{id:'cut'}},4)).rejects.toThrow('invalid UTF-8');
 }finally{raw.close();}
});
