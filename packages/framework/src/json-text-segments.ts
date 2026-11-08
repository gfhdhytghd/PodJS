/** Greedy Unicode-scalar segmentation with two byte limits.
 *
 * Each segment is the longest nonempty scalar prefix of the remaining text that
 * satisfies both limits: raw UTF-8 bytes, and the caller's wrapper overhead plus
 * the bytes of the text as a canonical JSON string body (JSON.stringify escapes,
 * no quotes). Text arrives as arbitrary well-formed tokens; boundaries do not
 * depend on how the caller tokenizes it. No full-text copy is retained.
 */
import {encodeUtf8} from './byte-codecs.ts';
import {ContentSha256} from './content-sha256.ts';

export interface ScalarSegmentLimits {
 /** Maximum raw UTF-8 bytes of one segment. */
 readonly maxRawBytes:number;
 /** Maximum overhead(index) + escaped body bytes of one segment. */
 readonly maxEncodedBytes:number;
 /** Encoded bytes of the wrapper with an empty text at this index. */
 readonly overhead:(index:number)=>number;
 /** More segments throw `object_too_large`. */
 readonly maxSegments:number;
}

const fail=(code:string):never=>{throw Error(code);};

/** Raw and canonical-JSON-escaped UTF-8 bytes of one UTF-16 code unit;
 * surrogate pairs are charged on the high unit. Lone surrogates throw. */
function unitCost(text:string,at:number):number {
 const c=text.charCodeAt(at);
 if(c<0x80){if(c===34||c===92)return 0x10002;if(c<0x20)return c===8||c===9||c===10||c===12||c===13?0x10002:0x10006;return 0x10001;}
 if(c<0x800)return 0x20002;
 if(c>=0xd800&&c<=0xdbff){const d=text.charCodeAt(at+1);if(!(d>=0xdc00&&d<=0xdfff))fail('invalid_encoding');return 0x40004;}
 if(c>=0xdc00&&c<=0xdfff)fail('invalid_encoding');
 return 0x30003;
}

/** Raw and escaped (JSON string body) UTF-8 byte counts of a well-formed string. */
export function jsonStringBodyBytes(text:string):{raw:number;escaped:number} {
 let raw=0,escaped=0;
 for(let i=0;i<text.length;i++){const cost=unitCost(text,i);raw+=cost>>>16;escaped+=cost&0xffff;if(cost>>>16===4)i++;}
 return {raw,escaped};
}

/** Streaming segmenter. `emit` receives each completed segment in order. */
export class GreedyScalarSegmenter {
 private parts:string[]=[];private raw=0;private escaped=0;private index=0;private limit:number;
 constructor(private readonly limits:ScalarSegmentLimits,private readonly emit:(index:number,text:string,rawBytes:number)=>void){
  for(const n of [limits.maxRawBytes,limits.maxEncodedBytes,limits.maxSegments])if(!Number.isSafeInteger(n)||n<1)fail('invalid_schema');
  this.limit=this.budget();
 }
 get count(){return this.index;}
 private budget(){const overhead=this.limits.overhead(this.index);if(!Number.isSafeInteger(overhead)||overhead<0)fail('invalid_schema');return this.limits.maxEncodedBytes-overhead;}
 private flush(){
  if(!this.parts.length)fail('object_too_large');
  if(this.index>=this.limits.maxSegments)fail('object_too_large');
  const text=this.parts.length===1?this.parts[0]!:this.parts.join('');
  this.emit(this.index,text,this.raw);this.index++;this.parts=[];this.raw=0;this.escaped=0;this.limit=this.budget();
 }
 /** Appends a token. A token never ends inside a surrogate pair. */
 push(token:string){
  let start=0,i=0;
  while(i<token.length){
   const cost=unitCost(token,i),r=cost>>>16,e=cost&0xffff;
   if(this.raw+r>this.limits.maxRawBytes||this.escaped+e>this.limit){
    if(i>start)this.parts.push(token.slice(start,i));
    if(!this.parts.length)fail('object_too_large');
    this.flush();start=i;continue;
   }
   this.raw+=r;this.escaped+=e;i+=r===4?2:1;
  }
  if(i>start)this.parts.push(start===0?token:token.slice(start));
 }
 /** Emits the final segment; no empty tail. Returns the segment count. */
 finish(){if(this.parts.length)this.flush();return this.index;}
}

/** Segments `tokens` and returns the segment count. */
export function segmentScalarsGreedy(tokens:Iterable<string>,limits:ScalarSegmentLimits,emit:(index:number,text:string,rawBytes:number)=>void){
 const segmenter=new GreedyScalarSegmenter(limits,emit);for(const token of tokens)segmenter.push(token);return segmenter.finish();
}

/** Checks that `texts` are exactly the greedy boundaries of their concatenation. */
export function isGreedyScalarSegmentation(texts:readonly string[],limits:ScalarSegmentLimits){
 let at=0,ok=true;
 try{segmentScalarsGreedy(texts,limits,(_index,text)=>{if(!ok)return;const expected=texts[at++];if(expected===undefined||expected.length!==text.length||expected!==text)ok=false;});}
 catch(error){if((error as Error).message==='object_too_large')return false;throw error;}
 return ok&&at===texts.length;
}

/** SHA-256 over the UTF-8 concatenation of well-formed string parts. Parts
 * are hashed in bounded slices; no concatenated copy is created. */
export function utf8PartsSha256(parts:Iterable<string>):{sha256:string;bytes:number} {
 const hash=new ContentSha256();let bytes=0;
 for(const part of parts)for(let start=0;start<part.length;){
  let end=Math.min(part.length,start+65536);const last=part.charCodeAt(end-1);if(end<part.length&&last>=0xd800&&last<=0xdbff)end--;
  const chunk=encodeUtf8(part.slice(start,end),'strict');hash.update(chunk);bytes+=chunk.length;start=end;
 }
 return {sha256:hash.finish(),bytes};
}
