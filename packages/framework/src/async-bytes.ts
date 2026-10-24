import {utf8ToString} from '../../../vendor/pocketjs/framework/src/bytes.ts';
import {encodeUtf8,utf8EncodeChunks,encodeBase64,base64EncodeChunks,decodeBase64,base64DecodeChunks,type SurrogatePolicy} from './byte-codecs.ts';

export interface CodecWorkOptions {
  cancelled?:()=>boolean;
  /** A macrotask/frame boundary, not just a resolved promise. */
  yield?:()=>Promise<void>;
}
const UTF16_BUDGET=4096,BASE64_CHARS=4096,BASE64_BYTES=3072;
const host=()=>(globalThis as {pod?:{decodeUtf8?:(bytes:Uint8Array)=>string|null|undefined}}).pod;
function join(chunks:Uint8Array[]):Uint8Array {const out=new Uint8Array(chunks.reduce((n,b)=>n+b.length,0));let offset=0;for(const bytes of chunks){out.set(bytes,offset);offset+=bytes.length;}return out;}
function check(options:CodecWorkOptions){if(options.cancelled?.())throw Error('byte codec cancelled');}
async function yieldTurn(options:CodecWorkOptions):Promise<void>{
 check(options);
 if(options.yield)await options.yield();
 else {
  // Keep pure/synchronous codecs independent of service initialization. Older
  // portable hosts can supply timers; callers can also supply their scheduler.
  const scheduler=(globalThis as {__podYieldCodecTurn?:()=>Promise<void>}).__podYieldCodecTurn;
  if(scheduler)await scheduler();
  else await new Promise<void>(resolve=>setTimeout(resolve,0));
 }
 check(options);
}
async function collect<T>(pieces:Iterable<T>,options:CodecWorkOptions):Promise<T[]> {
 const out:T[]=[];check(options);const iterator=pieces[Symbol.iterator]();
 const now=()=>globalThis.performance?.now?.()??Date.now();let sliceStart=now(),piecesInSlice=0;
 try {while(true){check(options);const next=iterator.next();if(next.done)break;out.push(next.value);piecesInSlice++;
  // Bound CPU work as well as bytes. Fast native pieces should share a turn,
  // rather than paying one Android timer/service roundtrip for every 4 KiB.
  if(piecesInSlice>=16||now()-sliceStart>=8){await yieldTurn(options);sliceStart=now();piecesInSlice=0;}
 }if(piecesInSlice&&(options.cancelled||options.yield))await yieldTurn(options);check(options);return out;}
 finally{iterator.return?.();}
}
/** Bounded 4 KiB pieces share a slice of at most 16 pieces / 8 ms before yielding. */
export async function encodeUtf8Async(value:string,policy:SurrogatePolicy='strict',options:CodecWorkOptions={}):Promise<Uint8Array>{check(options);if(value.length<=UTF16_BUDGET)return encodeUtf8(value,policy);return join(await collect(utf8EncodeChunks(value,policy),options));}
export async function encodeBase64Async(bytes:Uint8Array,options:CodecWorkOptions={}):Promise<string>{check(options);if(bytes.length<=BASE64_BYTES)return encodeBase64(bytes);return (await collect(base64EncodeChunks(bytes),options)).join('');}
export async function decodeBase64Async(value:string,options:CodecWorkOptions={}):Promise<Uint8Array>{check(options);if(value.length<=BASE64_CHARS)return decodeBase64(value);return join(await collect(base64DecodeChunks(value),options));}
function* utf8DecodeChunks(bytes:Uint8Array):Generator<string> {
 for(let start=0;start<bytes.length;){let end=Math.min(start+4096,bytes.length);
  if(end<bytes.length)for(let back=0;back<3&&end>start&&(bytes[end]&0xc0)===0x80;back++)end--;
  const part=bytes.subarray(start,end),native=host()?.decodeUtf8?.(part);
  yield typeof native==='string'?native:utf8ToString(part);start=end;
 }
}
/** Strict decoding reuses the existing native UTF-8 bridge with frame yields. */
export async function decodeUtf8Async(bytes:Uint8Array,options:CodecWorkOptions={}):Promise<string>{check(options);if(bytes.length<=4096)return [...utf8DecodeChunks(bytes)].join('');return (await collect(utf8DecodeChunks(bytes),options)).join('');}
