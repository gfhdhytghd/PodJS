import {expect,test} from 'bun:test';
import {createKeyedMemo} from '../src/keyed-memo.ts';
import {wrapAtlasText,layoutText} from '../src/text-wrap.ts';
test('caller memo retains identity, invalidates changed dependencies and releases source',()=>{
 const memo=createKeyedMemo<string>(),a={},compute=()=>({});
 const first=memo.get('image',[a,300],compute);
 expect(memo.get('image',[a,300],compute)).toBe(first);
 expect(memo.peek<object>('image',[a,300])).toBe(first);
 expect(memo.peek('image',[a,301])).toBeUndefined();
 expect(memo.get('image',[a,301],compute)).not.toBe(first);
 memo.clear();expect(memo.size).toBe(0);expect(memo.peek('image',[a,301])).toBeUndefined();
});
test('atlas wrap default host absence and rejected capability retain portable cluster rules',()=>{
 const global=globalThis as any,previous=global.pod;delete global.pod;
 const text='中　文 a\u0301\u200db 🙂\r\n tail  ';
 const options={fontSlot:1,width:4,measure:(s:string)=>Array.from(s).length};
 try{
  const expected=layoutText(text,options);expect(wrapAtlasText(text,options)).toEqual(expected);
  let calls=0;global.pod={wrapAtlasTextChunk(){calls++;return undefined;}};
  expect(wrapAtlasText(text,options)).toEqual(expected);expect(calls).toBe(1);
  wrapAtlasText('a'.repeat(4097),options);expect(calls).toBe(1);
 }finally{global.pod=previous;}
});
