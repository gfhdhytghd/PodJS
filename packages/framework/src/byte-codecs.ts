
export type SurrogatePolicy='strict'|'question'|'replacement';
type CodecHost={
  decodeUtf8?:(bytes:Uint8Array)=>string|null|undefined;
  encodeUtf8Chunk?:(value:string,policy:SurrogatePolicy)=>Uint8Array|null|undefined;
  encodeBase64Chunk?:(bytes:Uint8Array)=>string|null|undefined;
  decodeBase64Chunk?:(value:string)=>Uint8Array|null|undefined;
};
const host=()=>(globalThis as {pod?:CodecHost}).pod;
const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const UTF16_BUDGET=4096,BASE64_CHARS=4096,BASE64_BYTES=3072;

function join(chunks:Uint8Array[]):Uint8Array {
 const out=new Uint8Array(chunks.reduce((n,b)=>n+b.length,0));let offset=0;
 for(const bytes of chunks){out.set(bytes,offset);offset+=bytes.length;}return out;
}
function portableUtf8(value:string,policy:SurrogatePolicy):Uint8Array {
 const bytes:number[]=[];
 for(let i=0;i<value.length;i++){
  let code=value.charCodeAt(i);
  if(code>=0xd800&&code<=0xdbff){const low=value.charCodeAt(i+1);if(low>=0xdc00&&low<=0xdfff){code=0x10000+((code-0xd800)<<10)+low-0xdc00;i++;}else code=surrogate(policy);}
  else if(code>=0xdc00&&code<=0xdfff)code=surrogate(policy);
  if(code<0x80)bytes.push(code);
  else if(code<0x800)bytes.push(0xc0|(code>>6),0x80|(code&63));
  else if(code<0x10000)bytes.push(0xe0|(code>>12),0x80|((code>>6)&63),0x80|(code&63));
  else bytes.push(0xf0|(code>>18),0x80|((code>>12)&63),0x80|((code>>6)&63),0x80|(code&63));
 }return new Uint8Array(bytes);
}
function surrogate(policy:SurrogatePolicy):number {if(policy==='strict')throw Error('invalid UTF-16 surrogate');return policy==='question'?63:0xfffd;}
/** Bounded pieces preserve UTF-16 pair boundaries; no host retains the input. */
export function* utf8EncodeChunks(value:string,policy:SurrogatePolicy='strict'):Generator<Uint8Array> {
 if(!['strict','question','replacement'].includes(policy))throw RangeError('invalid surrogate policy');
 for(let start=0;start<value.length;){let end=Math.min(start+UTF16_BUDGET,value.length);
  const high=value.charCodeAt(end-1);if(end<value.length&&high>=0xd800&&high<=0xdbff)end--;
  const part=value.slice(start,end),native=host()?.encodeUtf8Chunk?.(part,policy);
  yield native instanceof Uint8Array?native:portableUtf8(part,policy);start=end;
 }
}
export function encodeUtf8(value:string,policy:SurrogatePolicy='strict'):Uint8Array {return join([...utf8EncodeChunks(value,policy)]);}

function portableBase64Encode(bytes:Uint8Array):string {
 const groups:string[]=[];
 for(let i=0;i<bytes.length;i+=3){const a=bytes[i],b=bytes[i+1]??0,c=bytes[i+2]??0;
  groups.push(alphabet[a>>2]+alphabet[((a&3)<<4)|(b>>4)]+(i+1<bytes.length?alphabet[((b&15)<<2)|(c>>6)]:'=')+(i+2<bytes.length?alphabet[c&63]:'='));
 }return groups.join('');
}
export function* base64EncodeChunks(bytes:Uint8Array):Generator<string> {
 for(let i=0;i<bytes.length;i+=BASE64_BYTES){const part=bytes.subarray(i,i+BASE64_BYTES),native=host()?.encodeBase64Chunk?.(part);yield typeof native==='string'?native:portableBase64Encode(part);}
}
export function encodeBase64(bytes:Uint8Array):string {return [...base64EncodeChunks(bytes)].join('');}
function portableBase64Decode(value:string):Uint8Array {
 if(!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}(?:==)?|[A-Za-z0-9+/]{3}=?)?$/.test(value))throw Error('invalid base64 chunk data');
 const raw=value.replace(/=+$/,''),out=new Uint8Array(Math.floor(raw.length*6/8));let accumulator=0,bits=0,index=0;
 for(const c of raw){accumulator=(accumulator<<6)|alphabet.indexOf(c);bits+=6;if(bits>=8){bits-=8;out[index++]=(accumulator>>bits)&255;}}return out;
}
/** Basic alphabet; optional complete padding; unused trailing bits are ignored. */
export function* base64DecodeChunks(value:string):Generator<Uint8Array> {
 for(let i=0;i<value.length;i+=BASE64_CHARS){const part=value.slice(i,i+BASE64_CHARS);
  if(i+BASE64_CHARS<value.length&&part.includes('='))throw Error('invalid base64 chunk data');
  const native=host()?.decodeBase64Chunk?.(part);yield native instanceof Uint8Array?native:portableBase64Decode(part);
 }
}
export function decodeBase64(value:string):Uint8Array {return join([...base64DecodeChunks(value)]);}
