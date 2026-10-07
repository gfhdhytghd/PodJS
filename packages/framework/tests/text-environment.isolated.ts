import {expect,test} from 'bun:test';
import {createTextEnvironment} from '../src/text-environment.ts';
test('production default environment uses native host, retries missing fonts, and explicitly invalidates',()=>{
 const prior=(globalThis as any).pod;let density=2,supported=true,reads=0,font:any;const a={cellWidth:16,cellHeight:18,baseline:14,lineHeight:18,rasterDensity:2},b={...a,baseline:15};
 (globalThis as any).pod={takeEvents:()=>undefined,displayMetrics:()=>JSON.stringify({logicalWidth:233,physicalWidth:233*density}),capabilities:()=>JSON.stringify(supported?['text.raster']:[]),fontMetrics:()=>{reads++;return font?JSON.stringify(font):undefined;}};
 try{const env=createTextEnvironment();expect(env.pixelScale).toBe(2);expect(env.rasterSupported).toBe(true);expect(env.fontMetrics(35)).toBeUndefined();font=a;const cached=env.fontMetrics(35);expect(cached).toEqual(a);font=b;expect(env.fontMetrics(35)).toBe(cached);expect(reads).toBe(2);
 density=1;supported=false;env.invalidate();expect(env.pixelScale).toBe(1);expect(env.rasterSupported).toBe(false);expect(env.fontMetrics(35)).toEqual(b);expect(reads).toBe(3);
 delete (globalThis as any).pod;const portable=createTextEnvironment();expect(portable.pixelScale).toBe(1);expect(portable.rasterSupported).toBe(false);expect(portable.fontMetrics(35)).toBeUndefined();}finally{(globalThis as any).pod=prior;}
});
