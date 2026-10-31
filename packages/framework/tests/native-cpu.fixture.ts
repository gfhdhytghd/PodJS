import {crc32,crc32Async} from '../src/checksum';
import {decodeUtf8Async} from '../src/async-bytes';
import {gunzipBytes} from '../src/bytes';
const root=globalThis as any;
root.backgroundHandler=async(context:any)=>{
 if(typeof root.pod!=='undefined'||!root.__podByteCodecs)throw Error('headless namespace missing');
 const namespace=root.__podByteCodecs,native=namespace.crc32Bytes;let calls=0,yields=0,maximum=0;
 namespace.crc32Bytes=(b:Uint8Array,seed:number)=>{calls++;maximum=Math.max(maximum,b.length);if(b.length>262144)throw Error('unbounded native input');return native(b,seed);};
 const bytes=new Uint8Array(32*1024*1024+17);for(let i=0;i<bytes.length;i++)bytes[i]=(i*31+(i%251))&255;
 const began=Date.now(),checksum=await crc32Async(bytes,{yield:async()=>{yields++;}}),elapsedMillis=Date.now()-began;
 if(checksum!==root.__expectedCrc)throw Error('CRC differential mismatch');
 for(const seed of [0,1,0xffffffff]){const view=bytes.subarray(1,4097);if(crc32(view,seed)!==native(view,seed))throw Error('seed/view mismatch');}
 const originalCalls=calls;namespace.crc32Bytes=()=>null;
 if(await crc32Async(bytes.subarray(0,4096),{yield:async()=>{}},0xffffffff)!==native(bytes.subarray(0,4096),0xffffffff))throw Error('decline mismatch');
 namespace.crc32Bytes=()=>-1;let rejected=false;try{crc32(bytes.subarray(0,1));}catch{rejected=true;}if(!rejected&&originalCalls!==0)throw Error('invalid native result admitted');namespace.crc32Bytes=native;
 let utf8Calls=0;const decode=namespace.decodeUtf8;namespace.decodeUtf8=(b:Uint8Array)=>{utf8Calls++;return decode(b);};
 const decoded=await decodeUtf8Async(new Uint8Array(131072).fill(65),{yield:async()=>{}});if(decoded.length!==131072||!utf8Calls)throw Error('headless UTF8 wiring missing');
 let gzipCalls=0;const unzip=namespace.gunzipBytes;namespace.gunzipBytes=(b:Uint8Array)=>{gzipCalls++;return unzip(b);};
 const output=gunzipBytes(root.__gzipFixture);if(!output||output.length!==131072||!gzipCalls)throw Error('headless gzip wiring missing');
 if(await decodeUtf8Async(new Uint8Array([239,187,191,65,0,66]))!=='\ufeffA\0B')throw Error('UTF8 view semantics');
 await context.request('probe.metric',{checksum,calls:originalCalls,yields,maximum,bytes:bytes.length,elapsedMillis,utf8Calls,gzipCalls,servicesDuringComputation:0});return 'success';
};
