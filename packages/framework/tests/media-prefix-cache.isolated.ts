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
test('prefetch result events carry opaque identities, HTTP classification and generation; unsubscribe works',()=>{
 const seen:any[]=[];const off=mediaPrefixCache.onResult(value=>seen.push(value));
 const value={itemToken:'opaque_1',generation:7,result:'failure',httpStatus:403,category:'http_403'};
 events.push({t:'video.cache.result',value});__pumpPodEvents();expect(seen).toEqual([value]);
 for(const invalid of [{...value,itemToken:'https://secret'},{...value,generation:-1},{...value,httpStatus:900},{...value,category:'arbitrary'}]){events.push({t:'video.cache.result',value:invalid});__pumpPodEvents();}expect(seen).toHaveLength(1);
 off();events.push({t:'video.cache.result',value});__pumpPodEvents();expect(seen).toHaveLength(1);
});
test('persistent registration and preparation retain the media capability contract',async()=>{
 const operations=[
  [()=>mediaPrefixCache.register('stable','https://example.test/new',{},true),'video.cache.register',{uri:'podjs-media-cache://play/stable'}],
  [()=>mediaPrefixCache.prepare('podjs-media-cache://play/stable',1048576,'frame'),'video.cache.prepare',{ready:true,prefixBytes:524288,tailBytes:524288}],
  [()=>mediaPrefixCache.inspect('podjs-media-cache://play/stable'),'video.cache.inspect',{prefixBytes:524288,tailBytes:524288,length:10000000}],
  [()=>mediaPrefixCache.remove('podjs-media-cache://play/stable'),'video.cache.remove',{}],
 ] as const;
 for(const [start,method,value] of operations){const op=start();const call=emitted.at(-1);expect(call.method).toBe(method);if(method==='video.cache.register')expect(call.args.persistent).toBe(true);events.push({t:'service.result',id:call.id,ok:true,value});__pumpPodEvents();expect(await op.result).toEqual(value as any);}
});

 test('same-track alternates cross the host bridge without losing headers or identity',async()=>{
  const op=mediaPrefixCache.register('track','https://a.test/track',{Cookie:'fixture'},false,['https://b.test/track']);const call=emitted.at(-1);
  expect(call.args).toEqual({key:'track',url:'https://a.test/track',headers:{Cookie:'fixture'},persistent:false,alternates:['https://b.test/track']});
  events.push({t:'service.result',id:call.id,ok:true,value:{uri:'podjs-media-cache://play/track'}});__pumpPodEvents();expect((await op.result).uri).toBe('podjs-media-cache://play/track');
 });
