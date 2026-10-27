import {describe,test,expect} from 'bun:test';
import {parseRawJson,patchRawJsonObject,rawJsonProperty,rawJsonText,rawJsonUnknownBytes,jsonUtf8Bytes} from '../src/json-raw';
describe('raw JSON token boundary',()=>{
 test('known patch retains big integer, escapes, whitespace and nested unknown tokens',()=>{
  const text='{"known":1,"future": { "wide":9007199254740993,"s":"\\u4e2d","a":[3, 1],"z":-0 }}';
  const raw=parseRawJson(text),unknown=rawJsonText(rawJsonProperty(raw,'future')!);
  const next=patchRawJsonObject(raw,{known:'2'});expect(next).toContain('"future": '+unknown);expect(text).toContain('"known":1');
  expect(rawJsonUnknownBytes(raw,{known:true})).toBe(jsonUtf8Bytes('"future": '+unknown));
 });
 test('escaped duplicate keys, invalid Unicode and invalid JSON reject',()=>{for(const input of ['{"a":1,"\\u0061":2}','{"x":"\\ud800"}','{"x":"\udfff"}','[1,]','{"x":01}','{"x":true garbage}'])expect(()=>parseRawJson(input)).toThrow();});
 test('container depth and UTF8 byte limits are exact',()=>{expect(()=>parseRawJson('['.repeat(32)+'0'+']'.repeat(32))).not.toThrow();expect(()=>parseRawJson('['.repeat(33)+'0'+']'.repeat(33))).toThrow('depth');expect(()=>parseRawJson('"中"',{maxBytes:5})).not.toThrow();expect(()=>parseRawJson('"中"',{maxBytes:4})).toThrow('size');for(const maxBytes of [NaN,Infinity,-1])expect(()=>parseRawJson('0',{maxBytes})).toThrow('size');});
 test('only explicit deletion removes a key, prototype names remain ordinary data',()=>{const raw=parseRawJson('{"__proto__": {"x":1},"a":1,"b":2}');const next=patchRawJsonObject(raw,new Map([['a',undefined],['c','3']]));expect(next).toBe('{"__proto__": {"x":1},"b":2,"c":3}');expect(rawJsonUnknownBytes(raw,{})).toBeGreaterThan(0);expect(()=>patchRawJsonObject(raw,{b:'NaN'})).toThrow();});
});
test('async raw JSON yields bounded work, agrees with sync and checks cancellation',async()=>{
 const {parseRawJsonAsync}=await import('../src/json-raw');let turns=0;
 const text='{"known":['+'1,'.repeat(2500)+'2],"future":"'+'x'.repeat(50000)+'"}';
 const node=await parseRawJsonAsync(text,{yield:async()=>{turns++;}});expect(rawJsonText(node)).toBe(rawJsonText(parseRawJson(text)));expect(turns).toBeGreaterThan(5);
 let cancelled=false;await expect(parseRawJsonAsync(text,{yield:async()=>{cancelled=true;},check:()=>{if(cancelled)throw Error('cancelled');}})).rejects.toThrow('cancelled');
});

test('integer JCS sorts UTF16 keys, canonicalizes strings and exact integral tokens',()=>{
 const {canonicalIntegerJson}=require('../src/json-raw');
 expect(canonicalIntegerJson('{"z":1e3,"a":-0,"中":"\\u4e2d","😀":12.00,"￿":true}')).toBe('{"a":0,"z":1000,"中":"中","😀":12,"￿":true}');
 expect(canonicalIntegerJson('[9007199254740991,90071992547409910e-1,0.001e3,null]')).toBe('[9007199254740991,9007199254740991,1,null]');
 for(const token of ['9007199254740992','9007199254740991.1','1e-324','-1','1.1','1e99999'])expect(()=>canonicalIntegerJson(token)).toThrow('json_integer_required');
 for(const source of ['{"a":1,"\\u0061":2}','"\\ud800"'])expect(()=>canonicalIntegerJson(source)).toThrow();
});
