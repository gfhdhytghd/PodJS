import {cpuWork,cpuWorkAsync} from './cpu-work';
import type {CodecWorkOptions} from './async-bytes';

const NATIVE_BYTES=256*1024,FALLBACK_BYTES=4096;
const table=new Uint32Array(256);
for(let n=0;n<256;n++){let c=n;for(let bit=0;bit<8;bit++)c=(c>>>1)^((c&1)?0xedb88320:0);table[n]=c>>>0;}
function validate(bytes:Uint8Array,initial:number){
 if(!(bytes instanceof Uint8Array)||!Number.isInteger(initial)||initial<0||initial>0xffffffff)throw RangeError('invalid CRC32 input');
}
function native(bytes:Uint8Array,initial:number):number|null {
 const codecs=globalThis as {pod?:{crc32Bytes?:(bytes:Uint8Array,initial:number)=>unknown};__podByteCodecs?:{crc32Bytes?:(bytes:Uint8Array,initial:number)=>unknown}};
 const run=codecs.pod?.crc32Bytes??codecs.__podByteCodecs?.crc32Bytes;
 if(typeof run!=='function')return null;
 const value=run(bytes,initial);
 if(value==null)return null;
 if(typeof value!=='number'||!Number.isInteger(value)||value<0||value>0xffffffff)throw Error('Invalid native CRC32 result');
 return value;
}
function fallback(bytes:Uint8Array,start:number,end:number,initial:number){
 let crc=(initial^0xffffffff)>>>0;
 for(let i=start;i<end;i++)crc=(crc>>>8)^table[(crc^bytes[i]!)&255]!;
 return (crc^0xffffffff)>>>0;
}
/** IEEE CRC32. The initial value is the finalized checksum of a preceding chunk. */
export function crc32(bytes:Uint8Array,initial=0):number {return cpuWork('crc32','bytes',bytes instanceof Uint8Array?bytes.length:0,()=>crc32Sync(bytes,initial));}
function crc32Sync(bytes:Uint8Array,initial=0):number {
 validate(bytes,initial);let value=initial;
 for(let start=0;start<bytes.length;start+=NATIVE_BYTES){const end=Math.min(start+NATIVE_BYTES,bytes.length);value=native(bytes.subarray(start,end),value)??fallback(bytes,start,end,value);}
 return value;
}
/** No native call exceeds 256 KiB. Portable fallback pieces are at most 4 KiB;
 * both paths yield after 8 ms or 16 pieces and observe cancellation at boundaries. */
export function crc32Async(bytes:Uint8Array,options:CodecWorkOptions={},initial=0):Promise<number>{return cpuWorkAsync('crc32','bytes',bytes instanceof Uint8Array?bytes.length:0,()=>checksumAsync(bytes,options,initial));}
async function checksumAsync(bytes:Uint8Array,options:CodecWorkOptions={},initial=0):Promise<number>{
 validate(bytes,initial);const check=()=>{if(options.cancelled?.())throw Error('checksum cancelled');};
 const now=()=>globalThis.performance?.now?.()??Date.now();let value=initial,start=0,pieces=0,began=now(),declined=false;check();
 while(start<bytes.length){
  check();let end=Math.min(start+NATIVE_BYTES,bytes.length),next=declined?null:native(bytes.subarray(start,end),value);
  if(next===null){declined=true;end=Math.min(start+FALLBACK_BYTES,bytes.length);next=fallback(bytes,start,end,value);}
  value=next;start=end;pieces++;
  if(pieces>=16||now()-began>=8){
   check();const scheduler=(globalThis as {__podYieldCodecTurn?:()=>Promise<void>}).__podYieldCodecTurn;
   if(options.yield)await options.yield();else if(scheduler)await scheduler();else await new Promise<void>(resolve=>setTimeout(resolve,0));
   check();pieces=0;began=now();
  }
 }
 check();return value;
}
