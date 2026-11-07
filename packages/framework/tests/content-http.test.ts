import {test,expect} from 'bun:test';
import {createContentTransport} from '../src/content-http';
import {encodeBase64} from '../src/byte-codecs';
const path='sync2-content-http/slot-0/response-a.data';
for(const failure of ['none','read','size','path'])test('managed content HTTP owns response release after '+failure,async()=>{
 const calls:string[]=[],data=new Uint8Array(262147).fill(42),host:any=async(method:string,args:any)=>{
  calls.push(method);if(method==='content.http'){expect(args.managedTemp).toBe(true);expect(args.maxBytes).toBe(1048576);expect(args.path).toBeUndefined();return {path:failure==='path'?'foreign.data':path,size:failure==='size'?1048577:data.length,status:200,headers:{}};}
  if(method==='content.http.release')return {released:true};
  if(method==='file.read'){if(failure==='read')throw Error('lost_read');return {offset:args.offset,size:data.length,dataBase64:encodeBase64(data.subarray(args.offset,args.offset+args.maxBytes))};}
  throw Error('unexpected '+method);
 };
 const result=createContentTransport(host,()=>true).request({url:'http://127.0.0.1/reply',method:'GET',headers:{}});
 if(failure==='none')expect((await result).body).toEqual(data);else await expect(result).rejects.toThrow();
 expect(calls[0]).toBe('content.http');expect(calls.at(-1)).toBe('content.http.release');expect(calls).not.toContain('file.createTemp');expect(calls).not.toContain('file.delete');
});
test('managed content HTTP refuses oversized requests and releases no unrelated path on native failure',async()=>{
 let calls=0;const transport=createContentTransport(async()=>{calls++;throw Error('quota');},()=>true);
 await expect(transport.request({url:'http://127.0.0.1',method:'POST',headers:{},body:new Uint8Array(1048577)})).rejects.toThrow('object_too_large');expect(calls).toBe(0);
 await expect(transport.request({url:'http://127.0.0.1',method:'GET',headers:{}})).rejects.toThrow('quota');expect(calls).toBe(1);
});
