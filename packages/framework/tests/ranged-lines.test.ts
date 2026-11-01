import {test,expect} from 'bun:test';
import {rangedLines} from '../src/ranged-lines';
const bytes=(s:string)=>new TextEncoder().encode(s);
test('ranged lines retain exact UTF-8 byte cursors and resume without reading a prefix',async()=>{
 const input=bytes('header\n'+ '汉😀'.repeat(14000)+'\nend\n'),calls:number[]=[];
 const read=async(offset:number,max:number)=>{calls.push(offset);expect(max).toBeLessThanOrEqual(65536);return {size:input.length,bytes:input.slice(offset,offset+max)};};
 const all=[];for await(const r of rangedLines(read,input.length))all.push(r);
 expect(all.map(r=>r.text)).toEqual(['header','汉😀'.repeat(14000),'end']);expect(all.at(-1)!.end).toBe(input.length);
 calls.length=0;const tail=[];for await(const r of rangedLines(read,input.length,all[1].end))tail.push(r.text);expect(tail).toEqual(['end']);expect(calls).toEqual([all[1].end]);
});
test('ranged line bounds, truncation, CRLF, BOM and malformed UTF-8 fail closed',async()=>{
 for(const input of [bytes('no newline'),bytes('bad\r\n'),bytes('\ufeffbad\n'),new Uint8Array([0xc0,0xaf,10])]){
  await expect((async()=>{for await(const _ of rangedLines(async(at,max)=>({size:input.length,bytes:input.slice(at,at+max)}),input.length)){} })()).rejects.toThrow();
 }
 await expect((async()=>{for await(const _ of rangedLines(async()=>({size:2,bytes:bytes('a\n')}),3)){} })()).rejects.toThrow('line_source_changed');
});
