import {expect,test} from 'bun:test';
import {Database} from 'bun:sqlite';
import {lazyRegExpTest} from '../src/regexp.ts';
import {watchUiLines,layoutUiText,measuredLines,balancedTitleLines,normalizeCjkTitle} from '../src/text-lines.ts';
import {updateCaretMap} from '../src/text-carets.ts';
import {querySqlPages,readSqlText} from '../src/sql.ts';
import {StreamFrameConnection,type FrameCodec,type FrameByteStream} from '../src/frame-stream.ts';
import {encodeSyncFrame} from '../src/sync-framing.ts';
test('distinct wrap profiles, real glyph widths, ellipsis and opt-in CJK title normalization',()=>{
 const measure=(s:string)=>Array.from(s).reduce((n,c)=>n+(/[a-z ]/i.test(c)?6:12),0);
 expect(watchUiLines('本次 token 数量',{width:54,measure})).toEqual(['本次','token 数','量']);
 expect(layoutUiText('  a',{width:54,measure})).toEqual(['a']);
 expect(watchUiLines('  a',{width:54,measure})).toEqual(['  a']);
 expect(measuredLines('ab中中文',30,measure,1)).toEqual(['ab…']);
 expect(balancedTitleLines(' 标题 中文 测试 ',233,measure,150,190)).toEqual([' 标题 中文 测试 ']);
 expect(balancedTitleLines(' 标题 中文 测试 ',233,measure,150,190,undefined,0,normalizeCjkTitle)).toEqual(['标题中文测试']);
});
test('lazy expressions compile at first call and retain native stateful flags',()=>{
 const delayed=lazyRegExpTest('[','u');expect(()=>delayed('x')).toThrow(SyntaxError);
 const global=lazyRegExpTest('a','g');expect([global('a'),global('a'),global('a')]).toEqual([true,false,true]);
});
test('caret delivery preserves unchanged signal values and other row identities',()=>{
 const row={},other={},old=new Map([[other,[0,5]]]);expect(updateCaretMap(old,row,undefined)).toBe(old);
 const next=updateCaretMap(old,row,[0,8]);expect(updateCaretMap(next,row,[0,8])).toBe(next);expect(next.get(other)).toBe(old.get(other));expect(updateCaretMap(next,row,undefined).has(row)).toBe(false);
});
test('SQL pages use production defaults and halve only oversized response retries',async()=>{
 const requested:any[]=[],values=Array.from({length:12},(_,id)=>({id})),db={query:async(sql:string,p:readonly unknown[]=[])=>{requested.push([sql,p]);const size=Number(p.at(-2)),offset=Number(p.at(-1));if(size>4)throw Error('Query result too large');return values.slice(offset,offset+size);}};
 expect(await querySqlPages(db,'SELECT id FROM rows ORDER BY id')).toEqual(values);expect(requested[0]).toEqual(['SELECT id FROM rows ORDER BY id LIMIT ? OFFSET ?',[400,0]]);
 await expect(querySqlPages({query:async()=>{throw Error('missing table');}},'SELECT x')).rejects.toThrow('missing table');
});
test('SQL text preserves Unicode/NUL bytes and empty/null with a caller size policy',async()=>{
 const raw=new Database(':memory:');raw.exec('CREATE TABLE data(value TEXT)');raw.query('INSERT INTO data VALUES(?)').run('x'.repeat(32767)+'😀中\0尾'.repeat(10000));
 const db={query:async(sql:string,p:readonly unknown[]=[])=>raw.query(sql).all(...p as any[]) as Record<string,unknown>[]};
 try{const expected=raw.query('SELECT value FROM data').get() as any;expect(await readSqlText(db,'SELECT value FROM data')).toBe(expected.value);
 await expect(readSqlText(db,'SELECT value FROM data',[],{maxBytes:9})).rejects.toThrow('Invalid or oversized SQL text');raw.exec("UPDATE data SET value='' ");expect(await readSqlText(db,'SELECT value FROM data')).toBe('');raw.exec('UPDATE data SET value=NULL');expect(await readSqlText(db,'SELECT value FROM data')).toBeNull();}finally{raw.close();}
});
const codec:FrameCodec<Uint8Array>={maxFrameBytes:100000,encode:async b=>encodeSyncFrame(b),decode:async b=>b.subarray(4)};
function transport(frame:Uint8Array){let offset=0,closed=0;const writes:Uint8Array[]=[],stream:FrameByteStream={read:async max=>{const b=frame.slice(offset,offset+Math.min(max,3));offset+=b.length;return b.length?b:null;},write:async bytes=>{writes.push(bytes.slice());},close:async()=>{closed++;}};return {stream,writes,closed:()=>closed};}
test('generic opaque stream defaults use Date clock, chunked transport and byte progress',async()=>{
 const bytes=new Uint8Array(40000).fill(7),f=transport(encodeSyncFrame(bytes)),conn=new StreamFrameConnection(f.stream,codec),progress:any[]=[];conn.setTransferProgress(p=>progress.push(p));expect(await conn.readFrame(1000)).toEqual(bytes);await conn.writeFrame(bytes);expect(f.writes.map(b=>b.length)).toEqual([4,16384,16384,7232]);expect(progress.filter(p=>p.direction==='read').reduce((n,p)=>n+p.bytes,0)).toBe(40004);await conn.close();await conn.close();expect(f.closed()).toBe(1);
});
test('frame budgets reject zero/negative configuration and concurrent operations preserve ownership',async()=>{
 const f=transport(encodeSyncFrame(new Uint8Array([1])));for(const chunkBytes of [0,-1,NaN])expect(()=>new StreamFrameConnection(f.stream,{...codec,chunkBytes})).toThrow('budget');expect(()=>new StreamFrameConnection(f.stream,{...codec,maxFrameBytes:0})).toThrow('limit');
 let finish!:(v:Uint8Array)=>void;const stream={...f.stream,read:()=>new Promise<Uint8Array>(r=>finish=r)},conn=new StreamFrameConnection(stream,codec);
 const first=conn.readFrame(1000).catch(e=>String(e));await expect(conn.readFrame(1000)).rejects.toThrow('Concurrent');await conn.close();finish(new Uint8Array([0]));expect(await first).toContain('closed');expect(f.closed()).toBe(1);
});
