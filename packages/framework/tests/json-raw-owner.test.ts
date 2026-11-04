import {test,expect} from 'bun:test';
import {parseRawJsonAsync,rawJsonText} from '../src/json-raw';
test('async scanner retains fixed caller ownership across yields and keeps exact JSON bytes',async()=>{
 const g=globalThis as any,oldPod=g.pod,oldCodecs=g.__podByteCodecs;const stacks:string[]=[];g.pod=undefined;g.__podByteCodecs={strictUtf8ByteLength:(s:string)=>{stacks.push(new Error().stack??'');return Buffer.byteLength(s);}};
 try{for(const owner of ['cloud-manifest','cloud-private-overlay'] as const){stacks.length=0;let yields=0;const text=JSON.stringify({data:'x'.repeat(90000)});const result=await parseRawJsonAsync(text,{owner,yield:async()=>{yields++;},check:()=>{}});expect(rawJsonText(result)).toBe(text);expect(yields).toBeGreaterThan(0);expect(stacks.some(s=>s.includes(owner==='cloud-manifest'?'cloudManifestJsonStep':'cloudPrivateOverlayJsonStep'))).toBe(true);}}
 finally{g.pod=oldPod;g.__podByteCodecs=oldCodecs;}
});
