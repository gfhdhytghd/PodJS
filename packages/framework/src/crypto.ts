import {encodeUtf8,base64ToBytes,bytesToBase64} from './bytes';
/** Length-prefixed binary framing. Integers use exact decimal UTF-8. */
export function lengthPrefix(...fields:(string|number|Uint8Array)[]):Uint8Array {
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
