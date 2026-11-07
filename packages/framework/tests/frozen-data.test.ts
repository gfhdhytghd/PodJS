import {test,expect} from 'bun:test';
import {frozenDataRecord} from '../src/frozen-data';
import {captureSqlBasis,commitSqlCas} from '../src/sql-projection';
import {Database} from 'bun:sqlite';
for(const hidden of ['string','symbol','getter','non-enumerable','prototype'] as const)test('frozen data refuses '+hidden+' before invoking a getter',()=>{
 let reads=0;let value:any={a:'one',b:'two'};
 if(hidden==='string')Object.defineProperty(value,'hidden',{value:1});
 if(hidden==='symbol')value[Symbol('hidden')]=1;
 if(hidden==='getter')Object.defineProperty(value,'a',{get(){reads++;return 'one';},enumerable:true});
 if(hidden==='non-enumerable')Object.defineProperty(value,'a',{value:'one',enumerable:false});
 if(hidden==='prototype')value=Object.assign(Object.create({}),value);
 Object.freeze(value);expect(()=>frozenDataRecord(value,['a','b'])).toThrow('invalid_schema');expect(reads).toBe(0);
});
test('frozen data accepts only an immutable exact data record',()=>{const value=Object.freeze({a:'one',b:'two'});expect(frozenDataRecord(value,['a','b'])).toBe(value);expect(()=>frozenDataRecord({...value},['a','b'])).toThrow('invalid_schema');});
test('SQL basis follows business writes in the same CAS and rolls back with them',async()=>{
 const raw=new Database(':memory:');raw.exec("CREATE TABLE state(scope TEXT PRIMARY KEY,version TEXT);INSERT INTO state VALUES('s','0');CREATE TABLE marker(id INTEGER PRIMARY KEY,epoch TEXT,revision INTEGER);INSERT INTO marker VALUES(1,'e',0);CREATE TABLE basis(scope TEXT PRIMARY KEY,state_version TEXT,epoch TEXT,revision TEXT)");
 const db={query:async()=>[],transaction:async(statements:any[])=>{raw.exec('BEGIN IMMEDIATE');try{for(const s of statements)raw.query(s.sql).run(...s.params??[]);raw.exec('COMMIT');}catch(e){raw.exec('ROLLBACK');throw e;}}};
 try{await commitSqlCas(db,{table:'state',keyColumn:'scope',versionColumn:'version',key:'s'},'0',[{sql:'UPDATE marker SET revision=revision+1'},captureSqlBasis('basis','s','1','marker')]);expect(raw.query('SELECT * FROM basis').get()).toEqual({scope:'s',state_version:'1',epoch:'e',revision:'1'});
 await expect(commitSqlCas(db,{table:'state',keyColumn:'scope',versionColumn:'version',key:'s'},'0',[{sql:'UPDATE marker SET revision=revision+1'},captureSqlBasis('basis','s','2','marker')])).rejects.toThrow();expect(raw.query('SELECT revision FROM marker').get()).toEqual({revision:1});
 expect(()=>captureSqlBasis('basis;DROP TABLE marker','s','2','marker')).toThrow('invalid_sql_identifier');
 }finally{raw.close();}
});
