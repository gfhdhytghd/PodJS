import {test,expect} from 'bun:test';
import {ObjectFrameConnection} from '../src/object-frame';
import {StreamFrameConnection,type FrameByteStream} from '../src/frame-stream';
class Stream implements FrameByteStream {
 bytes:number[]=[];closed=false;limit=7;
 async read(max:number){if(!this.bytes.length)return null;return Uint8Array.from(this.bytes.splice(0,Math.min(max,this.limit)));}
 async write(b:Uint8Array){this.bytes.push(...b);}
 async close(){this.closed=true;}
}
const enc=new TextEncoder(),dec=new TextDecoder();
const codecs={encodeControl:async(v:any)=>enc.encode(JSON.stringify(v)),decodeControl:async(b:Uint8Array)=>JSON.parse(dec.decode(b)),encodeHeader:(v:any)=>enc.encode(JSON.stringify(v)),decodeHeader:(b:Uint8Array)=>JSON.parse(dec.decode(b)),partBytes:(h:any)=>h.partBytes,validatePart:async(h:any,b:Uint8Array)=>{if(h.first!==b[0])throw Error('hash_mismatch');}};
test('WRP2 coalesced controls/parts and arbitrary fragmentation keep exact boundaries',async()=>{
 const stream=new Stream(),c=new ObjectFrameConnection(stream,codecs,()=>true);await c.writeControl({request:'one'});const part=new Uint8Array(65536).fill(9);await c.writePart({partBytes:part.length,first:9},part);await c.writeControl({request:'two'});expect(await c.read(1000)).toEqual({kind:'control',value:{request:'one'}});const frame=await c.read(1000);expect(frame.kind).toBe('part');if(frame.kind==='part')expect(frame.payload).toEqual(part);expect(await c.read(1000)).toEqual({kind:'control',value:{request:'two'}});
});
test('WRP2 refuses bytes before the committed boundary',async()=>{const stream=new Stream(),c=new ObjectFrameConnection(stream,codecs,()=>false);await expect(c.writeControl({})).rejects.toThrow('framing_not_committed');expect(stream.bytes.length).toBe(0);await expect(c.read(1000)).rejects.toThrow('framing_not_committed');});
test('WRP2 overlong header and part never reach transport',async()=>{for(const header of [{partBytes:65537,first:1},{partBytes:1,first:1,padding:'x'.repeat(4096)}]){const stream=new Stream(),c=new ObjectFrameConnection(stream,codecs,()=>true);await expect(c.writePart(header,new Uint8Array(header.partBytes).fill(1))).rejects.toThrow();expect(stream.bytes.length).toBe(0);expect(stream.closed).toBe(true);}});
test('WRP2 untrusted length and truncated payload poison the stream',async()=>{
 for(const raw of [[0x57,0x52,0x50,0x32,0xff,0xff,0xff,0xff],[0,4,0,1],[0x57,0x52,0x50,0x32,0,0,0,2,123]]){const stream=new Stream();stream.bytes=raw;const c=new ObjectFrameConnection(stream,codecs,()=>true);await expect(c.read(1000)).rejects.toThrow();expect(stream.closed).toBe(true);await expect(c.read(1000)).rejects.toThrow('framing_not_committed');}
});
test('legacy stream ownership can transfer only at an idle frame boundary',async()=>{
 const stream=new Stream(),codec={maxFrameBytes:100,encode:async()=>new Uint8Array([0,0,0,1,123]),decode:async()=>({})},old=new StreamFrameConnection(stream,codec);const handed=old.detachStream();await old.close();expect(stream.closed).toBe(false);await expect(old.readFrame(1000)).rejects.toThrow('closed');expect(()=>old.detachStream()).toThrow('handoff');await handed.close();expect(stream.closed).toBe(true);
});
test('WRP2 same-stream concurrent readers and writers are rejected',async()=>{
 let unblock:()=>void=()=>{};const stream=new Stream(),blocked={...codecs,encodeControl:async(v:any)=>{await new Promise<void>(r=>unblock=r);return codecs.encodeControl(v);}},a=new ObjectFrameConnection(stream,blocked,()=>true),b=new ObjectFrameConnection(stream,codecs,()=>true);const first=a.writeControl({one:1});await expect(b.writeControl({two:2})).rejects.toThrow('concurrent_frame_write');unblock();await first;
});

import {decodeObjectBatch} from '../src/object-frame';
test('object batch checks exact coverage, ordering, duplicates, extras and digest callback',()=>{const decode=(s:string)=>new Uint8Array([Number(s)]),verify=(id:string,b:Uint8Array)=>{if(id!==String(b[0]))throw Error('hash_mismatch');};expect([...decodeObjectBatch(['1','2'],{objects:[{oid:'1',bytes:'1'}],nextOids:['2']},decode,verify).objects.keys()]).toEqual(['1']);for(const response of [{objects:[{oid:'1',bytes:'1'}],nextOids:[]},{objects:[{oid:'1',bytes:'1'}],nextOids:['1','2']},{objects:[{oid:'3',bytes:'3'}],nextOids:['1','2']},{objects:[{oid:'2',bytes:'2'},{oid:'1',bytes:'1'}],nextOids:[]},{objects:[{oid:'1',bytes:'9'}],nextOids:['2']}])expect(()=>decodeObjectBatch(['1','2'],response,decode,verify)).toThrow();});
