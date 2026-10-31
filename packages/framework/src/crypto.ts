import {cpuWork} from './cpu-work';
import {encodeUtf8,base64ToBytes,bytesToBase64,sha256Bytes} from './bytes';
/** Length-prefixed binary framing. Integers use exact decimal UTF-8. */
export function lengthPrefix(...fields:(string|number|Uint8Array)[]):Uint8Array {return lengthPrefixFields(fields);}
/** Array entry point avoids guest argument limits for large record inventories. */
export function lengthPrefixFields(fields:readonly (string|number|Uint8Array)[]):Uint8Array {
 const parts=fields.map(f=>f instanceof Uint8Array?f:encodeUtf8(String(f)));
 const out=new Uint8Array(parts.reduce((n,b)=>n+4+b.length,0));let at=0;
 for(const b of parts){if(b.length>0xffffffff)throw Error('field_too_large');new DataView(out.buffer).setUint32(at,b.length,false);out.set(b,at+4);at+=4+b.length;}return out;
}
export const hex=(b:Uint8Array)=>Array.from(b,v=>v.toString(16).padStart(2,'0')).join('');
export function unhex(s:string){if(!/^(?:[0-9a-f]{2})*$/.test(s))throw Error('invalid_hex');return Uint8Array.from(s.match(/../g)??[],v=>parseInt(v,16));}
/** u64 modulo using small exact integers; portable to guests without BigInt. */
export function decimalCode(digest:Uint8Array,digits=8){if(digest.length<8||digits!==8)throw Error('invalid_code_input');let n=0;for(const b of digest.subarray(0,8))n=(n*256+b)%100000000;return String(n).padStart(8,'0');}
export type CryptoRequest=<T>(method:string,args:Record<string,unknown>)=>Promise<T>;
/** No import-time I/O. P-256 signatures are IEEE-P1363 (64-byte r||s). */
export function createCrypto(request:CryptoRequest){return {
 async hmacSha256(key:Uint8Array,input:Uint8Array){return base64ToBytes((await request<{digestBase64:string}>('crypto.hmacSha256',{keyBase64:bytesToBase64(key),dataBase64:bytesToBase64(input)})).digestBase64);},
 async verifyP256(spki:Uint8Array,input:Uint8Array,signature:Uint8Array){if(signature.length!==64)return false;return (await request<{valid:boolean}>('crypto.verifyP256',{publicKeySpki:bytesToBase64(spki),dataBase64:bytesToBase64(input),signatureBase64:bytesToBase64(signature)})).valid===true;},
};}

/** Portable byte SHA-256 with an optional bounded native fast path. */
export function sha256Hex(input:Uint8Array):string {return cpuWork('sha256','bytes',input.length,()=>hashBytes(input));}
function hashBytes(input:Uint8Array):string {
  const native=sha256Bytes(input);if(native)return hex(native);
  const bytes=[...input], bitLength=bytes.length*8;
  bytes.push(0x80); while(bytes.length%64!==56) bytes.push(0);
  const high=Math.floor(bitLength/0x100000000), low=bitLength>>>0;
  for(let shift=24;shift>=0;shift-=8) bytes.push((high>>>shift)&255);
  for(let shift=24;shift>=0;shift-=8) bytes.push((low>>>shift)&255);
  const k=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
  const h=[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
  const rr=(x:number,n:number)=>(x>>>n)|(x<<(32-n));
  for(let offset=0;offset<bytes.length;offset+=64){const w=new Array<number>(64);for(let i=0;i<16;i++){const p=offset+i*4;w[i]=((bytes[p]<<24)|(bytes[p+1]<<16)|(bytes[p+2]<<8)|bytes[p+3])>>>0;}for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2],s0=rr(a,7)^rr(a,18)^(a>>>3),s1=rr(b,17)^rr(b,19)^(b>>>10);w[i]=(w[i-16]+s0+w[i-7]+s1)>>>0;}let[a,b,c,d,e,f,g,z]=h;for(let i=0;i<64;i++){const s1=rr(e,6)^rr(e,11)^rr(e,25),ch=(e&f)^(~e&g),t1=(z+s1+ch+k[i]+w[i])>>>0,s0=rr(a,2)^rr(a,13)^rr(a,22),maj=(a&b)^(a&c)^(b&c),t2=(s0+maj)>>>0;z=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0;}h[0]=(h[0]+a)>>>0;h[1]=(h[1]+b)>>>0;h[2]=(h[2]+c)>>>0;h[3]=(h[3]+d)>>>0;h[4]=(h[4]+e)>>>0;h[5]=(h[5]+f)>>>0;h[6]=(h[6]+g)>>>0;h[7]=(h[7]+z)>>>0;}
  return h.map(v=>v.toString(16).padStart(8,"0")).join("");
}

/** RFC 2104 HMAC. Small messages stay in the guest/native byte fast path,
 * without one asynchronous host request per digest. No key material is cached. */
export function hmacSha256Bytes(key:Uint8Array,input:Uint8Array):Uint8Array {
 const block=new Uint8Array(64),inner=new Uint8Array(64+input.length),outer=new Uint8Array(96);
 let normalized:Uint8Array|undefined,digest:Uint8Array|undefined;
 try{
  if(key.length>64){normalized=unhex(sha256Hex(key));block.set(normalized);}else block.set(key);
  for(let i=0;i<64;i++){inner[i]=block[i]^0x36;outer[i]=block[i]^0x5c;}
  inner.set(input,64);digest=unhex(sha256Hex(inner));outer.set(digest,64);
  return unhex(sha256Hex(outer));
 }finally{block.fill(0);inner.fill(0);outer.fill(0);normalized?.fill(0);digest?.fill(0);}
}

/** Java/Android releases write different gzip OS header bytes for identical
 * deflate streams. Recover an advertised encoding only after its entire digest
 * matches; never reinterpret a body or accept a hash mismatch. Input is immutable. */
export async function matchGzipHash(bytes:Uint8Array,expected:string,sha256:(bytes:Uint8Array)=>Promise<string>):Promise<Uint8Array|null>{
 if(await sha256(bytes)===expected)return bytes;
 if(bytes.length<18||bytes[0]!==31||bytes[1]!==139||bytes[2]!==8)return null;
 const candidate=new Uint8Array(bytes);
 for(const os of [0,3,255]){if(os===bytes[9])continue;candidate[9]=os;if(await sha256(candidate)===expected)return candidate;}
 return null;
}

/** Deterministic base header for newly encoded gzip members. Deflate and the
 * trailer are unchanged; callers still verify any previously advertised hash.
 * Optional headers (in particular FHCRC) cannot be patched by this helper. */
export function stableGzipHeader(bytes:Uint8Array):Uint8Array {
 if(bytes.length<18||bytes[0]!==31||bytes[1]!==139||bytes[2]!==8||bytes[3]!==0)throw Error('invalid_stable_gzip_header');
 if(bytes[4]===0&&bytes[5]===0&&bytes[6]===0&&bytes[7]===0&&bytes[9]===255)return bytes;
 const stable=new Uint8Array(bytes);stable.fill(0,4,8);stable[9]=255;return stable;
}
