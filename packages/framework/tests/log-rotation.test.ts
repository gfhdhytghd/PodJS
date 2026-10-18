import {test,expect} from 'bun:test';
import {FileLogger,type LogFile} from '../src/logging.ts';
import {createHostLogFile} from '../src/log-file.ts';
test('5 MiB rotation never sends the whole file to a RegExp and preserves FIFO byte offsets',async()=>{
 const row='[fixture] 中文 boundary\r\n',initial=row.repeat(Math.ceil((5*1024*1024+1181)/Buffer.byteLength(row)));let value=initial;
 const trim=(lines:string[])=>lines.slice(Math.floor(lines.length*.2)).join('\n')+'\n';
 const oldLines=initial.split(/\r\n|\n|\r/);if(oldLines.at(-1)==='')oldLines.pop();const expected=trim(oldLines);const writes:string[]=[];
 const file:LogFile={read:async()=>value,size:async()=>Buffer.byteLength(value),replace:async s=>{value=s;writes.push('replace');},append:async(s,offset)=>{expect(offset).toBe(Buffer.byteLength(value));value+=s;writes.push('append');}};
 const exec=RegExp.prototype.exec;let unbounded=0;RegExp.prototype.exec=function(input:string){if(input.length>65536){unbounded++;throw Error('unbounded RegExp work on a log file');}return exec.call(this,input);};
 try{const log=new FileLogger(file,{redact:s=>s,maxBytes:5*1024*1024,trim,writeFailureMessage:'failed'},()=>new Date(2026,0,1));log.i('T','new');await log.flush();}
 finally{RegExp.prototype.exec=exec;}
 expect(unbounded).toBe(0);expect(writes).toEqual(['replace','append']);expect(value.startsWith(expected)).toBe(true);expect(value.endsWith('[I] [T] new\n')).toBe(true);
},10000);
test('default host log adapter roundtrips a large mixed UTF-8 file and atomic replacement',async()=>{
 const original=('ASCII 中文 🙂 \ud800 \udc00\r\n').repeat(18000);const values=new Map<string,Buffer>();let reads=0,appends=0;
 const files={stat:async(path:string)=>({exists:values.has(path),size:values.get(path)?.length??0}),read:async(path:string,offset:number,max:number)=>{reads++;const b=values.get(path)!;return {offset,size:b.length,dataBase64:b.subarray(offset,offset+max).toString('base64'),eof:offset+max>=b.length};},write:async(path:string,data:string)=>{values.set(path,Buffer.from(data,'base64'));},append:async(path:string,data:string,offset:number)=>{appends++;const old=values.get(path)??Buffer.alloc(0);expect(offset).toBe(old.length);const b=Buffer.concat([old,Buffer.from(data,'base64')]);values.set(path,b);return {size:b.length};},createTemp:async()=>({path:'logs/temp'}),hash:async(path:string)=>({size:values.get(path)!.length,sha256:new Bun.CryptoHasher('sha256').update(values.get(path)!).digest('hex')}),commit:async(from:string,to:string,size:number,sha:string)=>{const b=values.get(from)!;expect(size).toBe(b.length);expect(sha).toBe(new Bun.CryptoHasher('sha256').update(b).digest('hex'));values.set(to,b);},remove:async(path:string)=>{values.delete(path);}};
 const log=createHostLogFile({path:'app_log.txt',temporaryDirectory:'logs'},files as any);await log.replace(original);const expected=Buffer.from(original).toString('utf8');expect(await log.read()).toBe(expected);expect(await log.size()).toBe(Buffer.byteLength(original));expect(reads).toBeGreaterThan(5);expect(appends).toBeGreaterThan(5);expect(values.has('logs/temp')).toBe(false);
},10000);
