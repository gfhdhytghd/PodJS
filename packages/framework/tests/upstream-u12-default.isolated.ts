import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['host.lifecycle','data.files.chunks','crypto.basic','net.download','data.images']),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line))};
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');const {images}=await import('../src/services.ts');const {defaultCachePorts}=await import('../src/cache.ts');const {createHostLogFile}=await import('../src/log-file.ts');
const tick=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};
const reply=async(value:any)=>{events.push({t:'service.result',id:emitted.at(-1).id,ok:true,value});__pumpPodEvents();await tick();};
test('actual image service facade preserves arguments, source sizes, region metadata and cancellation',async()=>{
 const decoded={path:'private/pixels',width:2,height:4,sourceWidth:9,sourceHeight:17,contentWidth:2,contentHeight:3};
 const old=images.decode('source',17);expect(emitted.at(-1)).toMatchObject({method:'image.decode',args:{path:'source',maxDimension:17}});await reply(decoded);expect(await old).toEqual(decoded);
 const op=images.decodeOperation('source',{applyExif:false,rgb565:true});expect(emitted.at(-1)).toMatchObject({method:'image.decode',args:{path:'source',maxDimension:512,applyExif:false,rgb565:true}});await reply(decoded);expect(await op.result).toEqual(decoded);
 const region=images.decodeRegionOperation('source',{x:1,y:2,width:3,height:4,sampleSize:2,trace:true});expect(emitted.at(-1)).toMatchObject({method:'image.decodeRegion',args:{path:'source',x:1,y:2,width:3,height:4,sampleSize:2,trace:true}});await reply(decoded);expect(await region.result).toEqual(decoded);
 const cancelled=images.decodeOperation('other');const result=cancelled.result.catch(e=>e);cancelled.cancel();expect(emitted.at(-1).t).toBe('service.cancel');expect((await result).code).toBe('cancelled');
});
test('actual default cache transport preserves default limit and caller image-validation/download profile',async()=>{
 const defaults=defaultCachePorts();const download=defaults.download('https://example.test','cache/file',{});expect(emitted.at(-1).args.maxBytes).toBe(16*1024*1024);await reply({size:2});await download.result;
 const ports=defaultCachePorts({maxDownloadBytes:7,validateImageDimension:1});
 const validation=ports.validate!('cache/file');expect(emitted.at(-1)).toMatchObject({method:'image.decode',args:{path:'cache/file',maxDimension:1}});await reply({path:'private/decoded',width:1,height:1});expect(emitted.at(-1)).toMatchObject({method:'file.delete',args:{path:'private/decoded'}});await reply(null);await validation;
 const hash=ports.digest('é');expect(emitted.at(-1)).toMatchObject({method:'crypto.hash',args:{algorithm:'SHA-256',dataBase64:'w6k='}});await reply({digestBase64:btoa('a'.repeat(32))});expect(await hash).toBe('61'.repeat(32));
 const task=ports.download('https://example.test','cache/file',{Accept:'x'});expect(emitted.at(-1).args).toEqual({url:'https://example.test',path:'cache/file',headers:{Accept:'x'},maxBytes:7});await reply({size:2});await task.result;
});
test('host log file defaults to real services, reads bytes and commits empty replacement before cleaning temp',async()=>{
 const log=createHostLogFile({path:'generic.log',temporaryDirectory:'private/log'});
 const reading=log.read();expect(emitted.at(-1)).toMatchObject({method:'file.stat',args:{path:'generic.log'}});await reply({exists:true,size:3});expect(emitted.at(-1)).toMatchObject({method:'file.read',args:{path:'generic.log',offset:0,maxBytes:3}});await reply({offset:0,size:3,eof:true,dataBase64:'5Lit'});expect(await reading).toBe('中');
 const replacing=log.replace('');expect(emitted.at(-1).method).toBe('file.createTemp');await reply({path:'private/log/tmp'});expect(emitted.at(-1)).toMatchObject({method:'file.hash',args:{path:'private/log/tmp'}});await reply({size:0,sha256:'a'.repeat(64)});expect(emitted.at(-1)).toMatchObject({method:'file.commit',args:{path:'private/log/tmp',target:'generic.log',expectedSize:0,expectedSha256:'a'.repeat(64)}});await reply(null);expect(emitted.at(-1).method).toBe('file.delete');await reply(null);await replacing;
});
