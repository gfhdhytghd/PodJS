import {afterEach,expect,test} from 'bun:test';
import {renderCheckpoint} from '../src/render-checkpoint.ts';
const old=(globalThis as any).pod;
afterEach(()=>{(globalThis as any).pod=old;});
test('default host-free checkpoint is inert; an opted-in host receives only numeric metadata',()=>{
 (globalThis as any).pod=undefined;expect(renderCheckpoint()).toBeUndefined();
 const lines:string[]=[];(globalThis as any).pod={emit:(s:string)=>lines.push(s)};
 const first=renderCheckpoint(),second=renderCheckpoint({sampleAlpha:true});expect(second).toBe(first!+1);
 const a=JSON.parse(lines[0]!),b=JSON.parse(lines[1]!);expect(a.t).toBe('diagnostic.renderCheckpoint');expect(a.sampleAlpha).toBe(false);expect(b.sampleAlpha).toBe(true);
 expect(Object.keys(a).sort()).toEqual(['guestEpochMs','sampleAlpha','sequence','t']);expect(a.guestEpochMs).toBeGreaterThan(0);
});
test('a failed diagnostic sink never breaks product code',()=>{
 (globalThis as any).pod={emit:()=>{throw Error('sink closed');}};expect(()=>renderCheckpoint()).not.toThrow();expect(renderCheckpoint()).toBeUndefined();
});
