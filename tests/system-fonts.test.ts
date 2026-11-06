import {test,expect} from 'bun:test';
import {pack} from '../vendor/pocketjs/framework/compiler/pak.ts';
import {systemFontManifest} from '../packages/cli/src/system-fonts.ts';

function descriptor(slot=2,px=16){const bytes=new Uint8Array(24),v=new DataView(bytes.buffer);v.setUint32(0,0x41464344,true);v.setUint16(4,7,true);bytes[12]=slot;bytes[14]=2;v.setFloat64(16,px,true);return bytes;}
test('system font package carries descriptors only and deduplicates prewarm characters',()=>{
 const pak=pack([{key:'ui:font.2',dtype:0,data:descriptor()}]);
 const manifest=systemFontManifest(pak,'中文A中文');
 expect(manifest.slots).toEqual([{slot:2,px:16,bold:false,density:2}]);
 expect([...manifest.warmChars].length).toBe(97);expect(manifest.diskCacheBytes).toBe(16*1024*1024);
});
test('system manifest rejects a legacy pixel atlas',()=>{
 const data=descriptor();new DataView(data.buffer).setUint16(4,3,true);
 expect(()=>systemFontManifest(pack([{key:'ui:font.2',dtype:0,data}]),'')).toThrow();
});
test('system manifest fails closed for malformed descriptors and full font payloads',()=>{
 for(const change of [(b:Uint8Array)=>b[12]=64,(b:Uint8Array)=>b[15]=1,(b:Uint8Array)=>b[6]=1,(b:Uint8Array)=>new DataView(b.buffer).setFloat64(16,NaN,true)]){
  const data=descriptor();change(data);expect(()=>systemFontManifest(pack([{key:'ui:font.2',dtype:0,data}]),'')).toThrow();
 }
 expect(()=>systemFontManifest(pack([{key:'ui:font.2',dtype:0,data:descriptor()},{key:'ui:font-source.x',dtype:1,data:new Uint8Array([1])}]),'')).toThrow('full font source');
});
