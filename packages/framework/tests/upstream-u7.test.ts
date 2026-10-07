import {expect,test} from 'bun:test';
import {SerialWriteQueue} from '../src/async';
import {TextHistory,DraftAutosave} from '../src/editing';
import {AutoScrollController} from '../src/navigation';
import {qrPixels} from '../src/qr-pixels';
import {visibleImageTiles,imageTileRegion} from '../src/image-tiles';
import {clampPreview,pinchPreviewStep} from '../src/image-preview';
test('serial writes recover after failure; flush captures the current tail',async()=>{
 const q=new SerialWriteQueue(),log:number[]=[];
 const failed=q.enqueue(async()=>{throw Error('disk');});const tail=q.flush();expect(tail).toBe(failed);
 const next=q.enqueue(async()=>{log.push(2);});expect(q.flush()).toBe(next);
 await expect(failed).rejects.toThrow('disk');await next;expect(log).toEqual([2]);
});
test('text history production clock, edit grouping and bounded redo',()=>{
 const history=new TextHistory('');history.record('a');history.record('ab');expect(history.undo()).toBe('');expect(history.redo()).toBe('ab');
 let time=0;const bounded=new TextHistory('',()=>time,2,10);for(const value of ['a','b','c']){time+=11;bounded.record(value);}expect(bounded.undo()).toBe('b');expect(bounded.undo()).toBe('a');expect(bounded.undo()).toBe('a');bounded.record('new');expect(bounded.canRedo).toBe(false);
});
test('generic autosave default clock, slow write and normalized response converge',async()=>{
 let finish!:(v:string)=>void;const writes:string[]=[];
 const draft=new DraftAutosave({initial:'',saved:null as string|null,equal:(a,b)=>a===b,isEmpty:v=>!v,save:async v=>{writes.push(v);return writes.length===1?new Promise<string>(r=>finish=r):v;},valueOf:v=>v});
 draft.setValue('first');const flushing=draft.flush();draft.setValue('second');finish('normalized');expect(await flushing).toBe('second');expect(writes).toEqual(['first','second']);expect(draft.state).toBe('saved');draft.dispose();
});
test('scroll defaults and background retain requested state until bounds reached',()=>{
 const scroll=new AutoScrollController();scroll.setBounds(10);scroll.start();scroll.setBackground(true);scroll.tick(1000);expect(scroll.getOffset()).toBe(0);expect(scroll.isRequested()).toBe(true);scroll.setBackground(false);scroll.tick(1000);expect(scroll.getOffset()).toBe(1);scroll.tick(Infinity);expect(scroll.getOffset()).toBe(1);scroll.tick(10000);expect(scroll.isRequested()).toBe(false);
});
test('QR raster defaults and explicit encoding/margin/inversion',()=>{
 const a=qrPixels('test',128),b=qrPixels('test',128,{invert:true});expect(a.length).toBe(128*128*4);
 for(let i=0;i<a.length;i+=4){expect(a[i]!+b[i]!).toBe(255);expect(b[i+3]).toBe(255);}
 expect(qrPixels('中文',128,{encoding:'utf8',bitmapSize:401,errorCorrection:'L',margin:0}).length).toBe(a.length);
 expect(()=>qrPixels('',128)).toThrow('Invalid QR content');
});
test('tile policy is caller-owned, edge halo and geometry remain bounded',()=>{
 const g={width:200,height:200,imageWidth:2048,imageHeight:2048,density:1},state={scale:1,x:0,y:0};
 expect(visibleImageTiles(g,state)[0]!.sampleSize).toBe(8);expect(visibleImageTiles(g,state,{fullResolution:()=>true})[0]!.sampleSize).toBe(1);
 expect(imageTileRegion({key:'',x:510,y:0,width:510,height:510,sampleSize:1},1024,1024)).toEqual({x:509,y:0,width:512,height:511,offsetX:1,offsetY:0});
 expect(clampPreview(g,{scale:1,x:10000,y:-10000})).toEqual({scale:1,x:0,y:-0});
 expect(pinchPreviewStep(g,state,1,100,100,0,0)).toEqual(state);
});

test('autosave treats falsy saved results as existing records',async()=>{
 const draft=new DraftAutosave({initial:0,saved:0,equal:(a:number,b:number)=>a===b,isEmpty:()=>true,valueOf:(v:number)=>v,save:async(v:number)=>v});
 expect(draft.state).toBe('saved');draft.setValue(1);expect(draft.state).toBe('pending');expect(await draft.flush()).toBe(1);
});
