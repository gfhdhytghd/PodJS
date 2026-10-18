import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['media.video']),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line))};
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');const {mediaPrefixCache}=await import('../src/media-prefix-cache.ts');
test('production default host operations retain opaque URI, headers, range-window bounds and clear',async()=>{
 const op=mediaPrefixCache.register('fixture','https://example.test/video',{Cookie:'fixture',Referer:'https://example.test/'});const call=emitted.at(-1);expect(call.method).toBe('video.cache.register');expect(call.args.headers.Cookie).toBe('fixture');events.push({t:'service.result',id:call.id,ok:true,value:{uri:'podjs-media-cache://play/fixture.mp4'}});__pumpPodEvents();const {uri}=await op.result;
 const window=mediaPrefixCache.window([{uri,prefetchBytes:2097152,captureBytes:4194304}],12345);const next=emitted.at(-1);expect(next.args).toEqual({items:[{uri,prefetchBytes:2097152,captureBytes:4194304}],foregroundBitrate:12345,revision:1});events.push({t:'service.result',id:next.id,ok:true,value:{bytes:2097152}});__pumpPodEvents();expect((await window.result).bytes).toBe(2097152);
 const clear=mediaPrefixCache.clear();const last=emitted.at(-1);expect(last.method).toBe('video.cache.clear');events.push({t:'service.result',id:last.id,ok:true,value:{removedBytes:2097152}});__pumpPodEvents();expect((await clear.result).removedBytes).toBe(2097152);
});
