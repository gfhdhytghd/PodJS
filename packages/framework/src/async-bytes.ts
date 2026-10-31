import {cpuWorkAsync} from './cpu-work';
import {utf8ToString} from '../../../vendor/pocketjs/framework/src/bytes.ts';
import {encodeUtf8,utf8EncodeChunks,encodeBase64,base64EncodeChunks,decodeBase64,base64DecodeChunks,type SurrogatePolicy} from './byte-codecs.ts';

export interface CodecWorkOptions {
  cancelled?:()=>boolean;
  /** A macrotask/frame boundary, not just a resolved promise. */
  yield?:()=>Promise<void>;
}
const UTF16_BUDGET=4096,BASE64_CHARS=4096,BASE64_BYTES=3072;
const host=()=>{const codecs=globalThis as {pod?:{decodeUtf8?:(bytes:Uint8Array)=>string|null|undefined};__podByteCodecs?:{decodeUtf8?:(bytes:Uint8Array)=>string|null|undefined}};return {decodeUtf8:codecs.pod?.decodeUtf8??codecs.__podByteCodecs?.decodeUtf8};};
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
export function encodeUtf8Async(value:string,policy:SurrogatePolicy='strict',options:CodecWorkOptions={}):Promise<Uint8Array>{return cpuWorkAsync('utf8-encode','chars',value.length,()=>_encodeUtf8Async(value,policy,options));}
async function _encodeUtf8Async(value:string,policy:SurrogatePolicy='strict',options:CodecWorkOptions={}):Promise<Uint8Array>{check(options);if(value.length<=UTF16_BUDGET)return encodeUtf8(value,policy);return join(await collect(utf8EncodeChunks(value,policy),options));}
export function encodeBase64Async(bytes:Uint8Array,options:CodecWorkOptions={}):Promise<string>{return cpuWorkAsync('base64-encode','bytes',bytes.length,()=>_encodeBase64Async(bytes,options));}
async function _encodeBase64Async(bytes:Uint8Array,options:CodecWorkOptions={}):Promise<string>{check(options);if(bytes.length<=BASE64_BYTES)return encodeBase64(bytes);return (await collect(base64EncodeChunks(bytes),options)).join('');}
export function decodeBase64Async(value:string,options:CodecWorkOptions={}):Promise<Uint8Array>{return cpuWorkAsync('base64-decode','chars',value.length,()=>_decodeBase64Async(value,options));}
async function _decodeBase64Async(value:string,options:CodecWorkOptions={}):Promise<Uint8Array>{check(options);if(value.length<=BASE64_CHARS)return decodeBase64(value);return join(await collect(base64DecodeChunks(value),options));}
function* utf8DecodeChunks(bytes:Uint8Array):Generator<string> {
 for(let start=0;start<bytes.length;){let end=Math.min(start+4096,bytes.length);
  if(end<bytes.length)for(let back=0;back<3&&end>start&&(bytes[end]&0xc0)===0x80;back++)end--;
  const part=bytes.subarray(start,end),native=host()?.decodeUtf8?.(part);
  yield typeof native==='string'?native:utf8ToString(part);start=end;
 }
}
/** Strict decoding reuses the existing native UTF-8 bridge with frame yields. */
export function decodeUtf8Async(bytes:Uint8Array,options:CodecWorkOptions={}):Promise<string>{return cpuWorkAsync('utf8-decode','bytes',bytes.length,()=>_decodeUtf8Async(bytes,options));}
async function _decodeUtf8Async(bytes:Uint8Array,options:CodecWorkOptions={}):Promise<string>{check(options);if(bytes.length<=4096)return [...utf8DecodeChunks(bytes)].join('');return (await collect(utf8DecodeChunks(bytes),options)).join('');}

/** Exact comparison of stable byte views. Callers retain immutable input until
 * completion, as with asynchronous hashing. At most 16 x 4 KiB / 8 ms per turn;
 * no per-byte callbacks, copies, digest shortcuts or probabilistic equality. */
export function equalBytesAsync(a:Uint8Array,b:Uint8Array,options:CodecWorkOptions={}):Promise<boolean>{return cpuWorkAsync('byte-compare','bytes',a.length,()=>_equalBytesAsync(a,b,options));}
async function _equalBytesAsync(a:Uint8Array,b:Uint8Array,options:CodecWorkOptions={}):Promise<boolean>{
 check(options);if(a.length!==b.length)return false;if(a===b)return true;
 const now=()=>globalThis.performance?.now?.()??Date.now();let start=now(),pieces=0;
 for(let at=0;at<a.length;){
  check(options);const end=Math.min(a.length,at+4096);
  for(;at<end;at++)if(a[at]!==b[at])return false;
  pieces++;
  if(at<a.length&&(pieces>=16||now()-start>=8)){await yieldTurn(options);start=now();pieces=0;}
 }
 check(options);return true;
}
