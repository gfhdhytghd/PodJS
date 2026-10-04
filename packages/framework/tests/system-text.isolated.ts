import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[],uploads:any[]=[],freed:number[]=[];
(globalThis as any).pod={takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line)),capabilities:()=>JSON.stringify(['text.raster','data.files.chunks'])};
mock.module('@pocketjs/framework/components',()=>({View:()=>null,Image:()=>null}));
mock.module('@pocketjs/framework/host',()=>({getOps:()=>({setImage(){},uploadTexture(...args:any[]){uploads.push(args);return 7;},freeTexture(id:number){freed.push(id);}})}));
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {bindSystemTextLine,systemTextResourceCounts}=await import('../src/text.tsx');
const tick=async()=>{for(let i=0;i<12;i++)await Promise.resolve();};
const reply=async(request:any,value:any)=>{events.push({t:'service.result',id:request.id,ok:true,value});__pumpPodEvents();await tick();};
const options=(text:string)=>({text,widthPx:1,heightPx:1,baselinePx:1,fontSizePx:1});
const bind=(text:string)=>bindSystemTextLine(options(text),{id:1} as any,()=>{});
const raster=(path:string)=>({path,width:1,height:1,carets:[0,1]});
test('released raster drains a late file result without reading or uploading it',async()=>{
 const release=bind('a'),request=emitted.at(-1);expect(request.method).toBe('text.raster');release();
 expect(emitted.some(e=>e.t==='service.cancel')).toBe(false);
 await reply(request,raster('text/late.rgba'));expect(emitted.at(-1)).toMatchObject({method:'file.delete',args:{path:'text/late.rgba'}});expect(uploads).toHaveLength(0);
 await reply(emitted.at(-1),{deleted:true});expect(systemTextResourceCounts()).toEqual({active:0,queued:0,cached:0});
});
test('release during a pixel read waits for the read and removes its file',async()=>{
 const release=bind('b');await reply(emitted.at(-1),raster('text/read.rgba'));const read=emitted.at(-1);expect(read.method).toBe('file.read');release();expect(emitted.at(-1)).toBe(read);
 await reply(read,{dataBase64:'AAAAAA==',size:4});expect(emitted.at(-1).method).toBe('file.delete');expect(uploads).toHaveLength(0);await reply(emitted.at(-1),{deleted:true});expect(systemTextResourceCounts().active).toBe(0);
});
test('shared textures live until their final consumer and cancelled queued work never starts',async()=>{
 const a=bind('c'),request=emitted.at(-1),b=bind('c');await reply(request,raster('text/shared.rgba'));await reply(emitted.at(-1),{dataBase64:'AAAAAA==',size:4});await reply(emitted.at(-1),{deleted:true});expect(uploads).toHaveLength(1);a();expect(freed).toHaveLength(0);b();expect(freed).toEqual([7]);
 const one=bind('d'),r1=emitted.at(-1),two=bind('e'),r2=emitted.at(-1),three=bind('f');three();one();two();
 await reply(r1,raster('text/one.rgba'));await reply(emitted.at(-1),{deleted:true});await reply(r2,raster('text/two.rgba'));await reply(emitted.at(-1),{deleted:true});
 expect(emitted.filter(e=>e.method==='text.raster'&&e.args.text==='f')).toHaveLength(0);expect(systemTextResourceCounts()).toEqual({active:0,queued:0,cached:0});
});
