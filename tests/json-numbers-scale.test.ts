import {expect,test} from 'bun:test';
import {parseJsonObjectNumbers,jsonNumberToken} from '../packages/framework/src/json-numbers.ts';
import * as reference from './fixtures/json-numbers-reference.ts';
function tokens(value:unknown,get:(parent:object,key:string)=>string|undefined,path=''):unknown[]{
 if(!value||typeof value!=='object')return [];
 return Object.entries(value).flatMap(([key,child])=>[[path+'/'+key,get(value,key)],...tokens(child,get,path+'/'+key)]);
}
test('engine string skipping preserves reference lexical-number metadata across escapes, duplicates, nesting and large bodies',()=>{
 const samples=['{"a":1,"b":1.0,"c":1e0,"d":-0,"e":9007199254740993}', '{"a":1,"a":"1","b":2,"b":null,"c":2,"c":3.0}', '{"a\\\"b":2.0,"x":[1,1.0,null,true,{"\\\\":-2e+2}]}'];
 for(let i=0;i<40;i++)samples.push('{"articles":[{"content":'+JSON.stringify(('abc"\\🙂中\n'.repeat(i*311)))+',"readingProgress":0.7500,"chunkIndexes":[0,1,2.0]}],"version":14}');
 for(const sample of samples){const actual=parseJsonObjectNumbers(sample),expected=reference.parseJsonObjectNumbers(sample);expect(actual).toEqual(expected);expect(tokens(actual,jsonNumberToken)).toEqual(tokens(expected,reference.jsonNumberToken));}
 const deep='{"a":'.repeat(10)+'1'+'}'.repeat(10);expect(()=>parseJsonObjectNumbers(deep,{maxDepth:5})).toThrow('nesting');
 for(const invalid of ['[]','null','{"a":"unterminated}','{"a":01}','{"a":NaN}'])expect(()=>parseJsonObjectNumbers(invalid)).toThrow();
});
