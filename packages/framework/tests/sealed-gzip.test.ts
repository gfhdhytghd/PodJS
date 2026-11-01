import {test,expect} from 'bun:test';
import {sealedGzip} from '../src/sealed-gzip';
import {sha256Stream} from '../src/sha256-stream';
import {createHash} from 'node:crypto';
const input={keyBase64:'fixture',parts:[{path:'sealed',aadBase64:'public',bytes:17}],length:17,outputPrefix:'out/p',aadPrefix:'public:',maxOutputBytes:262145};
test('native sealed stream records only durable replies and always closes on a receipt interruption',async()=>{
 const calls:string[]=[],receipts:number[]=[];
 await expect(sealedGzip(async<T>(method:string,args:Record<string,unknown>)=>{calls.push(method);if(method==='crypto.closeSealedGzip')return {} as T;return {offset:args.offset,bytes:262144,done:false} as T;},input,0,async step=>{receipts.push(step.bytes);throw Error('interrupt');})).rejects.toThrow('interrupt');
 expect(receipts).toEqual([262144]);expect(calls).toEqual(['crypto.gunzipSealedParts','crypto.closeSealedGzip']);
 const resumed:any[]=[];expect(await sealedGzip(async<T>(method:string,args:Record<string,unknown>)=>{if(method==='crypto.closeSealedGzip')return {} as T;resumed.push(args);return {offset:262144,bytes:1,done:true,sha256:'a'.repeat(64)} as T;},input,262144,async()=>{})).toBe('a'.repeat(64));expect(resumed[0].replayBytes).toBe(262144);
});
test('native stream rejects premature EOF, oversized pieces, wrong offset and missing final digest',async()=>{
 for(const step of [{offset:0,bytes:1,done:true,sha256:'a'.repeat(64)},{offset:0,bytes:262145,done:false},{offset:1,bytes:262144,done:false},{offset:0,bytes:262145,done:true}]){
  let receipts=0;await expect(sealedGzip(async<T>(method:string)=>method==='crypto.closeSealedGzip'?{} as T:step as T,input,0,async()=>{receipts++;})).rejects.toThrow('invalid_sealed_gzip_step');expect(receipts).toBe(0);
 }
});
test('large digest default path safely declines absent native capability',async()=>{expect(await sha256Stream(new Uint8Array(262145))).toBeNull();});
test('bounded digest supports headless property fallback, byte views and cancellation',async()=>{
 const g=globalThis as any,before=g.__podByteCodecs,pod=g.pod;let sizes:number[]=[],finish=0;
 g.pod={};g.__podByteCodecs={sha256Create:()=>{const h=createHash('sha256');return {update:(b:Uint8Array)=>{sizes.push(b.length);h.update(b);return true;},finish:()=>{finish++;return new Uint8Array(h.digest());}};}};
 try{const bytes=new Uint8Array(1024*1024+17).fill(37).subarray(1);let yields=0;expect(Buffer.from((await sha256Stream(bytes,async()=>{yields++;}))!).toString('hex')).toBe(createHash('sha256').update(bytes).digest('hex'));expect(Math.max(...sizes)).toBe(262144);expect(yields).toBe(4);sizes=[];await expect(sha256Stream(bytes,async()=>{throw Error('cancel');})).rejects.toThrow('cancel');expect(sizes).toEqual([262144]);expect(finish).toBe(2);}finally{g.pod=pod;g.__podByteCodecs=before;}
});
