import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['host.lifecycle','data.files.chunks','crypto.basic','net.download']),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line))};
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {DownloadCache}=await import('../src/download-cache.ts');
test('production default constructor dispatches bounded whole-file transport and headers',async()=>{
 const cache=new DownloadCache({root:'managed-cache/video',minBytes:65536,maxBytes:1048576,maxEntries:64});
 let finished=false,published=false,handled=0;
 const op=cache.download('fixture-id',async()=>({url:'https://example.test/video',headers:{Referer:'https://example.test/',Cookie:'fixture','User-Agent':'fixture'}}));
 void op.result.then(()=>finished=true);
 for(let i=0;i<500&&!finished;i++){
  await Promise.resolve();
  while(handled<emitted.length){const call=emitted[handled++];let value:unknown;
   if(call.method==='crypto.hash')value={digestBase64:btoa('a'.repeat(32))};
   else if(call.method==='file.stat')value={exists:published,file:published,size:published?65536:0};
   else if(call.method==='http.download'){expect(call.args).toMatchObject({maxBytes:1048576,headers:{Referer:'https://example.test/',Cookie:'fixture','User-Agent':'fixture'}});published=true;value={size:65536};}
   else if(call.method==='file.list')value={entries:[]};
   else if(call.method==='file.touch')value={};else throw Error(call.method);
   events.push({t:'service.result',id:call.id,ok:true,value});__pumpPodEvents();
  }
 }
 expect(finished).toBe(true);expect(await op.result).toBe('managed-cache/video/'+'61'.repeat(32)+'.mp4');expect(emitted.filter(c=>c.method==='http.download')).toHaveLength(1);
});
