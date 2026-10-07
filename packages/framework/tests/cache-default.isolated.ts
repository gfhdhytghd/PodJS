import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['host.lifecycle','data.files.chunks','crypto.basic','net.download']),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line))};
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {createFileCache}=await import('../src/cache.ts');
const tick=async()=>{for(let i=0;i<24;i++)await Promise.resolve();};
const reply=async(value:any)=>{events.push({t:'service.result',id:emitted.at(-1).id,ok:true,value});__pumpPodEvents();await tick();};
test('production constructor uses default host hashing, atomic download and file lease transport',async()=>{
 const cache=createFileCache({root:'files',buckets:['blob'],namespace:()=>({filename:key=>key+'.bin',ownsFile:name=>name.endsWith('.bin'),stagedTarget:()=>undefined,traverseDirectory:()=>false,cleanupStaged:false}),evictionPolicy:()=>[]});
 const request=cache.acquire('https://example.test/asset');await tick();expect(emitted.at(-1).method).toBe('crypto.hash');await reply({digestBase64:btoa('a'.repeat(32))});expect(emitted.at(-1).method).toBe('file.stat');await reply({exists:false,file:false,size:0});expect(emitted.at(-1).method).toBe('http.download');expect(emitted.at(-1).args).toMatchObject({url:'https://example.test/asset',maxBytes:16*1024*1024});await reply({size:8});expect(emitted.at(-1).method).toBe('file.stat');await reply({exists:true,file:true,size:8});expect(emitted.at(-1).method).toBe('file.touch');await reply({});const lease=await request.result;expect(lease.path).toBe('files/blob/'+'61'.repeat(32)+'.bin');lease.release();await tick();expect(emitted.at(-1).method).toBe('file.list');await reply({entries:[]});await reply({entries:[]});
});
