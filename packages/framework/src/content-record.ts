import {encodeBase64,decodeBase64,encodeUtf8} from './byte-codecs';
import {sha256Hex} from './crypto';
export interface ContentRecordCipher {seal(aad:string,value:any):Promise<string>;open(aad:string,value:string):Promise<any>;}
/** Bounded LOCAL projection records. This is not a sync object wire encoding.
 * Authenticate the exact chunk ciphertext list before decoding any fragment. */
export function contentRecordCipher(base:ContentRecordCipher,encode:(v:any)=>Uint8Array,decode:(b:Uint8Array)=>any):ContentRecordCipher {
 return {
  async seal(aad,value){const plain=encode(value);if(plain.length>393216)throw Error('object_too_large');
   const chunks:string[]=[];for(let at=0;at<plain.length;at+=98304)chunks.push(await base.seal(aad+':part:'+chunks.length,{data:encodeBase64(plain.subarray(at,at+98304))}));
   const manifest=await base.seal(aad+':manifest',{size:plain.length,hashes:chunks.map(c=>sha256Hex(encodeUtf8(c,'strict')))});return JSON.stringify({version:1,manifest,chunks});
  },
  async open(aad,value){const v=JSON.parse(value);if(v?.version!==1||Object.keys(v).sort().join(',')!=='chunks,manifest,version'||!Array.isArray(v.chunks)||v.chunks.length>4||typeof v.manifest!=='string')throw Error('content_record_corrupt');
   const m=await base.open(aad+':manifest',v.manifest);if(!Number.isInteger(m.size)||m.size<0||m.size>393216||!Array.isArray(m.hashes)||m.hashes.length!==v.chunks.length||v.chunks.length!==Math.ceil(m.size/98304))throw Error('content_record_corrupt');
   const plain=new Uint8Array(m.size);let at=0;for(let i=0;i<v.chunks.length;i++){const c=v.chunks[i];if(typeof c!=='string'||c.length>300000||sha256Hex(encodeUtf8(c,'strict'))!==m.hashes[i])throw Error('content_record_corrupt');const p=await base.open(aad+':part:'+i,c),bytes=decodeBase64(p.data);if(bytes.length!==Math.min(98304,m.size-at))throw Error('content_record_corrupt');plain.set(bytes,at);at+=bytes.length;}return decode(plain);
  },
 };
}
