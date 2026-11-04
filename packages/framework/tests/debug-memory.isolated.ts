import {expect,mock,test} from 'bun:test';
const events:unknown[]=[],emitted:any[]=[],freed:number[]=[];
(globalThis as any).pod={
 capabilities:()=>JSON.stringify(['host.lifecycle','text.raster','data.files.chunks']),
 takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,
 emit:(line:string)=>emitted.push(JSON.parse(line)),
 uploadTextureFile:()=>501,
};
// Replace only the native host/rendering boundary. Use the production service
// promise table, event fanout, text cache and zero-argument API call below.
mock.module("@pocketjs/framework/renderer",()=>Object.fromEntries(["createElement","insertNode","insert","setProp"].map(name=>[name,()=>{throw Error("unexpected component renderer call: "+name);}])));
mock.module('@pocketjs/framework/components',()=>({View(){},Image(){}}));
mock.module('@pocketjs/framework/host',()=>({getOps:()=>({setImage(){},freeTexture:(id:number)=>freed.push(id)})}));
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {debugMemoryStats,serviceResourceCounts}=await import('../src/services.ts');
const {bindSystemTextLine,systemTextMemoryStats}=await import('../src/text.tsx');
const tick=async()=>{for(let i=0;i<16;i++)await Promise.resolve();};
const reply=async(request:any,value:unknown)=>{events.push({t:'service.result',id:request.id,ok:true,value});__pumpPodEvents();await tick();};

test('default production memoryStats request settles and releases its callback',async()=>{
 expect(serviceResourceCounts().pending).toBe(0);
 const result=debugMemoryStats();
 expect(emitted.at(-1).method).toBe('debug.memoryStats');
 expect(emitted.at(-1).args).toEqual({});
 expect(serviceResourceCounts().pending).toBe(1);
 const snapshot={native:{quickjs:{usedBytes:1024}},renderer:null,pid:7};
 await reply(emitted.at(-1),snapshot);
 expect(await result).toEqual(snapshot as never);
 expect(serviceResourceCounts().pending).toBe(0);
 const denied=debugMemoryStats();const request=emitted.at(-1);
 events.push({t:'service.result',id:request.id,ok:false,code:'permission_denied',message:'Memory tools require a debug build'});
 __pumpPodEvents();
 await expect(denied).rejects.toMatchObject({code:'permission_denied'});
 expect(serviceResourceCounts().pending).toBe(0);
});

test('shared text stats track real refs, paragraph key units and final release',async()=>{
 const privateText='PRIVATE😀';
 const options={text:privateText,widthPx:4,heightPx:2,baselinePx:1,fontSizePx:1,
  paragraph:{text:'PRIVATE FULL PARAGRAPH',widthPx:4,fontSizePx:1,lineIndex:0}};
 const releaseA=bindSystemTextLine(options,{id:101} as any,()=>{});
 const releaseB=bindSystemTextLine(options,{id:102} as any,()=>{});
 const before=systemTextMemoryStats();
 expect(before.cached).toBe(1);expect(before.refs).toBe(2);expect(before.listeners).toBe(2);
 expect(before.utf16Units).toBe(privateText.length);expect(before.paragraphUtf16Units).toBe(options.paragraph.text.length);
 expect(before.keyUtf16Units).toBe(JSON.stringify(options).length);
 expect(JSON.stringify(before)).not.toContain('PRIVATE');
 await reply(emitted.find(r=>r.method==='text.raster'),{path:'text/line-fixture.rgba',width:4,height:2,carets:Array.from({length:privateText.length+1},(_,i)=>i)});
 const uploaded=systemTextMemoryStats();
 expect(uploaded.textures).toBe(1);expect(uploaded.textureBytes).toBe(32);
 releaseA();expect(systemTextMemoryStats().refs).toBe(1);expect(freed).toHaveLength(0);
 releaseB();releaseB();await tick();
 expect(freed).toEqual([501]);
 const after=systemTextMemoryStats();
 expect(after.cached).toBe(0);expect(after.refs).toBe(0);expect(after.listeners).toBe(0);
 expect(after.textures).toBe(0);expect(after.textureBytes).toBe(0);expect(after.queueSlots).toBe(0);
 expect(serviceResourceCounts().pending).toBe(0);
});
