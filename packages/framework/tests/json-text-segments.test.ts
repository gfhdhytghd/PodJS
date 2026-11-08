import {describe,it,expect} from 'bun:test';
import {jsonStringBodyBytes,segmentScalarsGreedy,isGreedyScalarSegmentation,utf8PartsSha256,updateSha256Parts,GreedyScalarSegmenter} from '../src/json-text-segments';
import {createHash} from 'node:crypto';
import {ContentSha256} from '../src/content-sha256';

const body=(s:string)=>Buffer.byteLength(JSON.stringify(s))-2;
/** Independent oracle: byte-level longest prefix search with Node JSON. */
function oracle(text:string,maxRaw:number,maxEncoded:number,overhead:(i:number)=>number){
 const out:string[]=[];let rest=text;
 while(rest.length){let best=0;const points=[...rest];let acc='';for(const p of points){const next=acc+p;if(Buffer.byteLength(next)>maxRaw||overhead(out.length)+body(next)>maxEncoded)break;acc=next;best=acc.length;}if(!best)throw Error('object_too_large');out.push(rest.slice(0,best));rest=rest.slice(best);}
 return out;
}
const run=(tokens:string[],maxRaw:number,maxEncoded:number,overhead=(i:number)=>String(i).length,max=4096)=>{const out:string[]=[];segmentScalarsGreedy(tokens,{maxRawBytes:maxRaw,maxEncodedBytes:maxEncoded,overhead,maxSegments:max},(i,t)=>{expect(i).toBe(out.length);out.push(t);});return out;};

describe('json text segments',()=>{
 it('matches JSON.stringify byte costs for escapes, controls and supplementary scalars',()=>{
  for(const s of ['','a"b\\c','\n\r\t\b\f\u0000\u001f\u007f','é中😀','  /','\r\n\0'])expect(jsonStringBodyBytes(s)).toEqual({raw:Buffer.byteLength(s),escaped:body(s)});
  expect(()=>jsonStringBodyBytes('a\ud800')).toThrow('invalid_encoding');expect(()=>jsonStringBodyBytes('\udc00')).toThrow('invalid_encoding');
 });
 it('chooses the longest prefix under both limits independent of tokenization',()=>{
  const text='ab"c\\d😀é中\n\u0001'.repeat(37)+'x'.repeat(50);
  for(const [raw,enc]of [[17,40],[40,17],[9,9],[64,128],[4,9]]){
   const expected=oracle(text,raw,enc,i=>String(i).length);
   expect(run([text],raw,enc)).toEqual(expected);
   const tokens:string[]=[];for(let i=0;i<text.length;){let n=1+(i*7)%5;if(text.charCodeAt(i+n-1)>=0xd800&&text.charCodeAt(i+n-1)<=0xdbff)n++;tokens.push(text.slice(i,i+n));i+=n;}
   expect(run(tokens,raw,enc)).toEqual(expected);
   expect(isGreedyScalarSegmentation(expected,{maxRawBytes:raw,maxEncodedBytes:enc,overhead:i=>String(i).length,maxSegments:4096})).toBe(true);
  }
 });
 it('accepts equality, never emits an empty tail and never splits a surrogate pair',()=>{
  expect(run(['abcdef'],3,100,()=>0)).toEqual(['abc','def']);
  expect(run(['😀😀'],4,100,()=>0)).toEqual(['😀','😀']);
 });
 it('rejects scalars that cannot fit and the segment after the maximum',()=>{
  expect(()=>run(['😀'],3,100,()=>0)).toThrow('object_too_large');
  expect(()=>run(['"'],10,1,()=>0)).toThrow('object_too_large');
  expect(()=>run(['abcd'],1,100,()=>0,3)).toThrow('object_too_large');
  expect(run(['abc'],1,100,()=>0,3)).toEqual(['a','b','c']);
 });
 it('rejects moved, merged or empty boundaries',()=>{
  const limits={maxRawBytes:3,maxEncodedBytes:100,overhead:()=>0,maxSegments:10};
  expect(isGreedyScalarSegmentation(['abc','de'],limits)).toBe(true);
  for(const bad of [['ab','cde'],['abc','d','e'],['abc','de',''],['abcde']])expect(isGreedyScalarSegmentation(bad,limits)).toBe(false);
 });
 it('hashes parts without concatenation',()=>{
  const parts=['a'.repeat(70000)+'😀','é'.repeat(40000),'','😀x'];
  const joined=parts.join('');expect(utf8PartsSha256(parts)).toEqual({sha256:createHash('sha256').update(joined).digest('hex'),bytes:Buffer.byteLength(joined)});
  const s=new GreedyScalarSegmenter({maxRawBytes:2,maxEncodedBytes:9,overhead:()=>0,maxSegments:9},()=>{});s.push('abc');expect(s.finish()).toBe(2);
 });
 it('feeds byte arrays and strings above the per-update limit into one running hash',()=>{
  const big=new Uint8Array(262144*2+17).map((_,i)=>(i*31)&255),parts:(string|Uint8Array)[]=[big,'😀'.repeat(70000),new Uint8Array(0),'tail'];
  const hash=new ContentSha256(),bytes=updateSha256Parts(hash,parts),oracle=createHash('sha256');for(const p of parts)oracle.update(p);
  expect(hash.finish()).toBe(oracle.digest('hex'));expect(bytes).toBe(big.length+280000+4);
  expect(()=>new ContentSha256().update(big)).toThrow('hash_unit_too_large');
  // Incremental use across calls equals one call.
  const a=new ContentSha256();updateSha256Parts(a,[big]);updateSha256Parts(a,['x']);const b=new ContentSha256();updateSha256Parts(b,[big,'x']);expect(a.finish()).toBe(b.finish());
 });
});
