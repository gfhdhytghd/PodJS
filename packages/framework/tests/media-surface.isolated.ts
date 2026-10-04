import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[],images:any[]=[],detachFailures=new Set<number>();
(globalThis as any).pod={takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line)),capabilities:()=>JSON.stringify(['data.images','media.video'])};
mock.module('@pocketjs/framework/components',()=>({View:()=>null,Image:()=>null}));
mock.module('@pocketjs/framework/host',()=>({getOps:()=>({setImage(...args:any[]){images.push(args);if(args[1]===-1&&detachFailures.delete(args[0]))throw Error('node removed');}})}));
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');const {bindMediaSurface,mediaSurfaceOwners}=await import('../src/media-surface.tsx');
const reply=async(request:any,value:any)=>{events.push({t:'service.result',id:request.id,ok:true,value});__pumpPodEvents();for(let i=0;i<10;i++)await Promise.resolve();};
const result={texture:9,width:512,height:512,state:'ready'};
test('closing before the native open reply releases the known owner and ignores late texture binding',async()=>{
 const binding=bindMediaSurface({kind:'image',path:'image.png',widthPx:466,heightPx:466,style:{}},{id:1} as any,()=>{throw Error('late delivery');});const open=emitted.at(-1);binding.close();const close=emitted.at(-1);expect(close).toMatchObject({method:'image.surface.close',args:{id:binding.id}});
 await reply(open,result);await reply(close,{closed:true});expect(images).not.toContainEqual([1,9]);expect(mediaSurfaceOwners()).toEqual([]);expect(emitted.some(e=>e.t==='service.cancel')).toBe(false);
});
test('pending appearance changes coalesce without reopening the video player',async()=>{
 const values:any[]=[];const binding=bindMediaSurface({kind:'video',path:'clip.mp4',widthPx:466,heightPx:466,style:{speed:1}},{id:2} as any,value=>values.push(value));const open=emitted.at(-1);binding.update({speed:1.5,zoom:2});binding.update({speed:2,zoom:3});expect(emitted.at(-1)).toBe(open);
 await reply(open,result);const first=emitted.at(-1);expect(first).toMatchObject({method:'video.surface.update',args:{id:binding.id,speed:2,zoom:3}});binding.update({active:false});await reply(first,result);const pause=emitted.at(-1);expect(pause).toMatchObject({method:'video.surface.update',args:{id:binding.id,active:false}});await reply(pause,result);
 expect(emitted.filter(e=>e.method==='video.surface.open')).toHaveLength(1);binding.close();await reply(emitted.at(-1),{closed:true});expect(mediaSurfaceOwners()).toEqual([]);
});

const reject=async(request:any,message='surface failure')=>{
 events.push({t:'service.result',id:request.id,ok:false,code:'media_surface_error',message});
 __pumpPodEvents();for(let i=0;i<10;i++)await Promise.resolve();
};
const requests=(id:string,action:string)=>emitted.filter(event=>event.method?.endsWith('.surface.'+action)&&event.args?.id===id);

test('creation sampling is forwarded without changing omitted payloads or style updates',async()=>{
 for(const kind of ['image','video'] as const)for(const textureSampling of [undefined,'nearest','linear'] as const){
  const states:any[]=[],errors:unknown[]=[],node=101+emitted.length;
  const binding=bindMediaSurface({kind,path:'local.png',widthPx:48,heightPx:48,textureSampling,style:{brightness:.8}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
  const open=requests(binding.id,'open')[0];
  expect(open.args).toEqual({id:binding.id,path:'local.png',widthPx:48,heightPx:48,brightness:.8,...(textureSampling===undefined?{}:{textureSampling})});
  const state={...result,...(textureSampling==='linear'?{textureSampling:'linear'}:{})};
  await reply(open,state);expect(images).toContainEqual([node,9]);expect(states).toEqual([state]);expect(errors).toEqual([]);
  binding.update({blurPx:2});const update=requests(binding.id,'update')[0];expect(update.args).toEqual({id:binding.id,blurPx:2});
  await reply(update,state);binding.close();await reply(requests(binding.id,'close')[0],{closed:true});expect(mediaSurfaceOwners()).not.toContain(binding.id);
 }
});

test('linear confirmation is required before binding and mismatch closes the owned session',async()=>{
 for(const kind of ['image','video'] as const)for(const reported of [undefined,'nearest','LINEAR',null]){
  const states:any[]=[],errors:unknown[]=[],atError:any[]=[],node=201+emitted.length;
  let binding!:ReturnType<typeof bindMediaSurface>;
  binding=bindMediaSurface({kind,path:'local.png',widthPx:48,heightPx:48,textureSampling:'linear',style:{}},{id:node} as any,value=>states.push(value),error=>{errors.push(error);atError.push({owned:mediaSurfaceOwners().includes(binding.id),closes:requests(binding.id,'close').length});});
  binding.update({blurPx:1});await reply(requests(binding.id,'open')[0],{...result,textureSampling:reported});
  expect(states).toEqual([]);expect(errors).toHaveLength(1);expect(String(errors[0])).toMatch(/linear.*sampling/i);
  expect(atError).toEqual([{owned:false,closes:1}]);expect(images.filter(row=>row[0]===node&&row[1]>=0)).toEqual([]);
  expect(requests(binding.id,'update')).toEqual([]);binding.close();binding.update({brightness:.9});expect(requests(binding.id,'close')).toHaveLength(1);
  await reject(requests(binding.id,'close')[0],'cleanup failed');expect(errors).toHaveLength(1);
 }
});

test('closing a linear request before unsupported readback suppresses a late error',async()=>{
 const errors:unknown[]=[],states:any[]=[],node=3001;
 const binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,textureSampling:'linear',style:{}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
 const open=requests(binding.id,'open')[0];binding.close();await reply(open,result);
 expect(errors).toEqual([]);expect(states).toEqual([]);expect(images).not.toContainEqual([node,9]);expect(requests(binding.id,'close')).toHaveLength(1);
 await reply(requests(binding.id,'close')[0],{closed:true});expect(mediaSurfaceOwners()).not.toContain(binding.id);
});

test('a detach exception cannot retain a mismatched linear session or suppress its error',async()=>{
 const errors:unknown[]=[],states:any[]=[],node=3002;
 const binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,textureSampling:'linear',style:{}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
 detachFailures.add(node);await reply(requests(binding.id,'open')[0],result);
 expect(detachFailures.has(node)).toBe(false);expect(errors).toHaveLength(1);expect(String(errors[0])).toMatch(/linear.*sampling/i);
 expect(states).toEqual([]);expect(mediaSurfaceOwners()).not.toContain(binding.id);expect(images).not.toContainEqual([node,9]);
 expect(requests(binding.id,'close')).toHaveLength(1);binding.close();expect(requests(binding.id,'close')).toHaveLength(1);
 await reply(requests(binding.id,'close')[0],{closed:true});expect(errors).toHaveLength(1);
});

const presentationFrame=()=>({leftPx:-17.25,topPx:600.5,widthPx:240,heightPx:222,scale:1.2,opacity:.7});

test('presentation frames are acknowledged before image and video ready callbacks without changing style payloads',async()=>{
 for(const kind of ['image','video'] as const){
  const states:any[]=[],errors:unknown[]=[],node=4000+emitted.length,frame=presentationFrame();
  const binding=bindMediaSurface({kind,path:'local.png',widthPx:48,heightPx:48,style:{brightness:.8,presentationFrame:frame}},{id:node} as any,value=>{expect(images.at(-1)).toEqual([node,9]);states.push(value);},error=>errors.push(error));
  const open=requests(binding.id,'open')[0];
  expect(open.args).toEqual({id:binding.id,path:'local.png',widthPx:48,heightPx:48,brightness:.8,presentationFrame:frame});
  expect(images.filter(row=>row[0]===node&&row[1]>=0)).toEqual([]);
  const opened={...result,presentationFrame:frame};await reply(open,opened);
  const next={...frame,leftPx:32768,topPx:-32768,scale:.25,opacity:0};
  binding.update({blurPx:2,presentationFrame:next});const update=requests(binding.id,'update')[0];
  expect(update.args).toEqual({id:binding.id,blurPx:2,presentationFrame:next});
  const updated={...result,presentationFrame:next};await reply(update,updated);
  expect(states).toEqual([opened,updated]);expect(errors).toEqual([]);
  binding.close();await reply(requests(binding.id,'close')[0],{closed:true});expect(mediaSurfaceOwners()).not.toContain(binding.id);
 }
});

test('missing, mismatched and malformed open frame acknowledgements clean up before exactly one error',async()=>{
 const frame=presentationFrame(),{opacity:_,...incomplete}=frame;
 for(const reported of [undefined,null,{...frame,leftPx:frame.leftPx+1},incomplete,{...frame,scale:'1.2'},{...frame,opacity:NaN}]){
  const states:any[]=[],errors:unknown[]=[],atError:any[]=[],node=5000+emitted.length;
  let binding!:ReturnType<typeof bindMediaSurface>;
  binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,style:{presentationFrame:frame}},{id:node} as any,value=>states.push(value),error=>{errors.push(error);atError.push({owned:mediaSurfaceOwners().includes(binding.id),image:images.at(-1),closes:requests(binding.id,'close').length});});
  binding.update({presentationFrame:{...frame,opacity:1}});
  // The host event wire omits undefined and encodes nonfinite numbers as null.
  await reply(requests(binding.id,'open')[0],{...result,presentationFrame:reported});
  expect(states).toEqual([]);expect(errors).toHaveLength(1);expect(String(errors[0])).toMatch(/presentation.*frame/i);
  expect(atError).toEqual([{owned:false,image:[node,-1],closes:1}]);
  expect(images.filter(row=>row[0]===node&&row[1]>=0)).toEqual([]);expect(requests(binding.id,'update')).toEqual([]);
  binding.close();binding.update({presentationFrame:frame});expect(requests(binding.id,'close')).toHaveLength(1);
  await reject(requests(binding.id,'close')[0],'cleanup failed');expect(errors).toHaveLength(1);
 }
});

test('every update frame acknowledgement is checked before ready and a bad reply drops queued work',async()=>{
 const frame=presentationFrame(),{widthPx:_,...incomplete}=frame;
 for(const reported of [undefined,null,{...frame,heightPx:frame.heightPx+1},incomplete,{...frame,opacity:'0.7'},{...frame,scale:Infinity}]){
  const states:any[]=[],errors:unknown[]=[],atError:any[]=[],node=6000+emitted.length;
  let binding!:ReturnType<typeof bindMediaSurface>;
  binding=bindMediaSurface({kind:'video',widthPx:48,heightPx:48,style:{presentationFrame:frame}},{id:node} as any,value=>states.push(value),error=>{errors.push(error);atError.push({owned:mediaSurfaceOwners().includes(binding.id),image:images.at(-1),closes:requests(binding.id,'close').length});});
  const opened={...result,presentationFrame:frame};await reply(requests(binding.id,'open')[0],opened);
  binding.update({brightness:.8,presentationFrame:frame});const update=requests(binding.id,'update')[0];
  binding.update({presentationFrame:{...frame,opacity:1}});
  await reply(update,{...result,presentationFrame:reported});
  expect(states).toEqual([opened]);expect(errors).toHaveLength(1);expect(String(errors[0])).toMatch(/presentation.*frame/i);
  expect(atError).toEqual([{owned:false,image:[node,-1],closes:1}]);expect(requests(binding.id,'update')).toHaveLength(1);
  binding.close();binding.update({});expect(requests(binding.id,'close')).toHaveLength(1);
  await reject(requests(binding.id,'close')[0],'cleanup failed');expect(errors).toHaveLength(1);
 }
});

test('omitted default frames accept null or absent readback and a later omitted frame resets presentation',async()=>{
 for(const reported of [undefined,null]){
  const states:any[]=[],errors:unknown[]=[],node=7000+emitted.length,frame=presentationFrame();
  const binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,style:{}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
  const open=requests(binding.id,'open')[0];expect(open.args).not.toHaveProperty('presentationFrame');
  await reply(open,{...result,presentationFrame:reported});
  binding.update({presentationFrame:frame});await reply(requests(binding.id,'update')[0],{...result,presentationFrame:frame});
  binding.update({brightness:.8});const reset=requests(binding.id,'update')[1];
  expect(reset.args).toEqual({id:binding.id,brightness:.8});await reply(reset,{...result,presentationFrame:reported});
  binding.update({presentationFrame:undefined});const explicitUndefined=requests(binding.id,'update')[2];
  expect(explicitUndefined.args).toEqual({id:binding.id});await reply(explicitUndefined,{...result,presentationFrame:reported});
  expect(states).toHaveLength(4);expect(errors).toEqual([]);expect(mediaSurfaceOwners()).toContain(binding.id);
  binding.close();await reply(requests(binding.id,'close')[0],{closed:true});
 }
});

test('a default open or reset update rejects a residual presentation frame',async()=>{
 for(const phase of ['open','update'] as const){
  const states:any[]=[],errors:unknown[]=[],node=8000+emitted.length,frame=presentationFrame();
  const binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,style:phase==='open'?{}:{presentationFrame:frame}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
  let pending=requests(binding.id,'open')[0];
  const opened={...result,presentationFrame:frame};
  if(phase==='update'){await reply(pending,opened);binding.update({});pending=requests(binding.id,'update')[0];}
  await reply(pending,opened);
  expect(states).toEqual(phase==='open'?[]:[opened]);expect(errors).toHaveLength(1);expect(String(errors[0])).toMatch(/presentation.*frame/i);
  expect(mediaSurfaceOwners()).not.toContain(binding.id);expect(images.at(-1)).toEqual([node,-1]);
  if(phase==='open')expect(images.filter(row=>row[0]===node&&row[1]>=0)).toEqual([]);
  binding.close();expect(requests(binding.id,'close')).toHaveLength(1);await reply(requests(binding.id,'close')[0],{closed:true});expect(errors).toHaveLength(1);
 }
});

test('presentation readback compares both requested and reported fields at float32 precision',async()=>{
 const frame={leftPx:-.1,topPx:600.1,widthPx:240,heightPx:222,scale:1.2,opacity:.7};
 const rounded=Object.fromEntries(Object.entries(frame).map(([key,value])=>[key,Math.fround(value)])) as typeof frame;
 const states:any[]=[],errors:unknown[]=[],node=9000+emitted.length;
 const binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,style:{presentationFrame:frame}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
 await reply(requests(binding.id,'open')[0],{...result,presentationFrame:rounded});
 binding.update({presentationFrame:rounded});await reply(requests(binding.id,'update')[0],{...result,presentationFrame:frame});
 expect(states).toHaveLength(2);expect(errors).toEqual([]);expect(mediaSurfaceOwners()).toContain(binding.id);
 binding.close();await reply(requests(binding.id,'close')[0],{closed:true});
});

test('coalesced frame replies are checked against each sent snapshot despite caller mutations',async()=>{
 const frame=presentationFrame(),original={...frame},states:any[]=[],errors:unknown[]=[],node=10000+emitted.length;
 const binding=bindMediaSurface({kind:'video',widthPx:48,heightPx:48,style:{presentationFrame:frame}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
 const open=requests(binding.id,'open')[0];frame.leftPx=101;
 binding.update({presentationFrame:{...original,opacity:.8}});
 const queued={...original,leftPx:20,opacity:.9},queuedSnapshot={...queued};
 binding.update({presentationFrame:queued});queued.leftPx=102;
 await reply(open,{...result,presentationFrame:original});
 const first=requests(binding.id,'update')[0];expect(first.args.presentationFrame).toEqual(queuedSnapshot);
 const next={...original,leftPx:30,opacity:1},nextSnapshot={...next};
 binding.update({presentationFrame:{...original,opacity:.95}});binding.update({presentationFrame:next});next.leftPx=103;
 await reply(first,{...result,presentationFrame:queuedSnapshot});
 const second=requests(binding.id,'update')[1];expect(second.args.presentationFrame).toEqual(nextSnapshot);
 await reply(second,{...result,presentationFrame:nextSnapshot});
 // A frame handed directly to an already loaded binding is also copied before await.
 const immediate={...original,leftPx:40},immediateSnapshot={...immediate};
 binding.update({presentationFrame:immediate});const third=requests(binding.id,'update')[2];immediate.leftPx=104;
 await reply(third,{...result,presentationFrame:immediateSnapshot});
 expect(states.map(value=>value.presentationFrame)).toEqual([original,queuedSnapshot,nextSnapshot,immediateSnapshot]);expect(errors).toEqual([]);
 expect(requests(binding.id,'open')).toHaveLength(1);expect(requests(binding.id,'update')).toHaveLength(3);
 binding.close();await reply(requests(binding.id,'close')[0],{closed:true});expect(mediaSurfaceOwners()).not.toContain(binding.id);
});

test('a detach exception cannot prevent cleanup for an unacknowledged presentation frame',async()=>{
 const states:any[]=[],errors:unknown[]=[],node=11000+emitted.length;
 const binding=bindMediaSurface({kind:'image',widthPx:48,heightPx:48,style:{presentationFrame:presentationFrame()}},{id:node} as any,value=>states.push(value),error=>errors.push(error));
 detachFailures.add(node);await reply(requests(binding.id,'open')[0],result);
 expect(detachFailures.has(node)).toBe(false);expect(states).toEqual([]);expect(errors).toHaveLength(1);
 expect(mediaSurfaceOwners()).not.toContain(binding.id);expect(images.filter(row=>row[0]===node&&row[1]>=0)).toEqual([]);
 binding.close();expect(requests(binding.id,'close')).toHaveLength(1);await reply(requests(binding.id,'close')[0],{closed:true});expect(errors).toHaveLength(1);
});

test('an open rejection releases the owner and detaches before notifying exactly once',async()=>{
 const states:any[]=[],errors:unknown[]=[],atError:any[]=[];
 let binding!:ReturnType<typeof bindMediaSurface>;
 binding=bindMediaSurface({kind:'image',path:'missing.png',widthPx:240,heightPx:240,style:{}},{id:51} as any,
  value=>states.push(value),error=>{errors.push(error);atError.push({owned:mediaSurfaceOwners().includes(binding.id),image:images.at(-1),closes:requests(binding.id,'close').length});});
 const open=requests(binding.id,'open')[0];binding.update({blurPx:36});
 await reject(open,'image open rejected');
 expect(errors).toHaveLength(1);expect(errors[0]).toMatchObject({code:'media_surface_error',message:'image open rejected'});
 expect(atError).toEqual([{owned:false,image:[51,-1],closes:1}]);expect(states).toEqual([]);
 expect(images).not.toContainEqual([51,9]);expect(requests(binding.id,'update')).toEqual([]);
 binding.update({blurPx:0});binding.close();binding.close();
 expect(requests(binding.id,'close')).toHaveLength(1);expect(requests(binding.id,'update')).toEqual([]);
 // Failure of the best-effort native close cannot become a second surface error.
 await reject(requests(binding.id,'close')[0],'already absent');expect(errors).toHaveLength(1);
});

test('negative, missing and nonfinite open textures close instead of publishing a ready surface',async()=>{
 for(const [index,texture] of [-1,undefined,NaN].entries()){
  const states:any[]=[],errors:unknown[]=[],node=52+index*10;
  const binding=bindMediaSurface({kind:'image',path:'image.png',widthPx:240,heightPx:240,style:{}},{id:node} as any,
   value=>states.push(value),error=>errors.push(error));
  // The host event wire omits undefined and encodes nonfinite numbers as null.
  await reply(requests(binding.id,'open')[0],{...result,texture});
  expect(states).toEqual([]);expect(errors).toHaveLength(1);expect(errors[0]).toBeInstanceOf(Error);
  expect(String(errors[0])).toMatch(/texture/i);expect(mediaSurfaceOwners()).not.toContain(binding.id);
  expect(images.at(-1)).toEqual([node,-1]);binding.update({brightness:.8});binding.close();
  expect(requests(binding.id,'update')).toEqual([]);expect(requests(binding.id,'close')).toHaveLength(1);
  await reply(requests(binding.id,'close')[0],{closed:true});expect(errors).toHaveLength(1);
 }
});

test('an update rejection detaches the current texture and drops queued appearance changes',async()=>{
 const states:any[]=[],errors:unknown[]=[],atError:any[]=[];
 let binding!:ReturnType<typeof bindMediaSurface>;
 binding=bindMediaSurface({kind:'video',path:'clip.mp4',widthPx:240,heightPx:240,style:{}},{id:53} as any,
  value=>states.push(value),error=>{errors.push(error);atError.push({owned:mediaSurfaceOwners().includes(binding.id),image:images.at(-1),closes:requests(binding.id,'close').length});});
 await reply(requests(binding.id,'open')[0],result);expect(images.at(-1)).toEqual([53,9]);
 binding.update({blurPx:24});const update=requests(binding.id,'update')[0];
 binding.update({blurPx:12});binding.update({blurPx:0});
 await reject(update,'style update rejected');
 expect(errors).toHaveLength(1);expect(errors[0]).toMatchObject({code:'media_surface_error',message:'style update rejected'});
 expect(atError).toEqual([{owned:false,image:[53,-1],closes:1}]);expect(states).toEqual([result]);
 expect(requests(binding.id,'update')).toHaveLength(1);binding.update({active:false});binding.close();
 expect(requests(binding.id,'update')).toHaveLength(1);expect(requests(binding.id,'close')).toHaveLength(1);
 await reply(requests(binding.id,'close')[0],{closed:true});expect(errors).toHaveLength(1);
});

test('callers without an error callback still release failed open and update owners',async()=>{
 const warn=console.warn;console.warn=()=>{};
 try{
  for(const phase of ['open','update'] as const){
   const states:any[]=[],node=phase==='open'?54:55;
   const binding=bindMediaSurface({kind:'image',path:'image.png',widthPx:240,heightPx:240,style:{}},{id:node} as any,value=>states.push(value));
   let pending=requests(binding.id,'open')[0];
   if(phase==='update'){await reply(pending,result);binding.update({blurPx:4});pending=requests(binding.id,'update')[0];}
   await reject(pending);expect(mediaSurfaceOwners()).not.toContain(binding.id);expect(images.at(-1)).toEqual([node,-1]);
   expect(states).toEqual(phase==='open'?[]:[result]);const count=requests(binding.id,'update').length;
   binding.update({blurPx:0});binding.close();expect(requests(binding.id,'update')).toHaveLength(count);
   expect(requests(binding.id,'close')).toHaveLength(1);await reply(requests(binding.id,'close')[0],{closed:true});
  }
 }finally{console.warn=warn;}
});

test('explicit close suppresses a late open rejection or invalid texture reply',async()=>{
 for(const outcome of ['rejection','invalid-texture'] as const){
  const states:any[]=[],errors:unknown[]=[],node=outcome==='rejection'?56:57;
  const binding=bindMediaSurface({kind:'image',path:'image.png',widthPx:240,heightPx:240,style:{}},{id:node} as any,
   value=>states.push(value),error=>errors.push(error));
  const open=requests(binding.id,'open')[0];binding.close();
  if(outcome==='rejection')await reject(open);else await reply(open,{...result,texture:-1});
  expect(states).toEqual([]);expect(errors).toEqual([]);expect(mediaSurfaceOwners()).not.toContain(binding.id);
  expect(images.filter(row=>row[0]===node)).toEqual([[node,-1],[node,-1]]);
  binding.close();binding.update({blurPx:8});expect(requests(binding.id,'close')).toHaveLength(1);
  expect(requests(binding.id,'update')).toEqual([]);await reply(requests(binding.id,'close')[0],{closed:true});
 }
});

test('explicit close suppresses late update success and rejection without a second close',async()=>{
 for(const outcome of ['rejection','success'] as const){
  const states:any[]=[],errors:unknown[]=[],node=outcome==='rejection'?58:59;
  const binding=bindMediaSurface({kind:'video',path:'clip.mp4',widthPx:240,heightPx:240,style:{}},{id:node} as any,
   value=>states.push(value),error=>errors.push(error));
  await reply(requests(binding.id,'open')[0],result);binding.update({blurPx:10});
  const update=requests(binding.id,'update')[0];binding.update({blurPx:0});binding.close();
  if(outcome==='rejection')await reject(update);else await reply(update,{...result,texture:10});
  expect(states).toEqual([result]);expect(errors).toEqual([]);expect(mediaSurfaceOwners()).not.toContain(binding.id);
  expect(images.at(-1)).toEqual([node,-1]);expect(images).not.toContainEqual([node,10]);
  binding.close();binding.update({brightness:1});expect(requests(binding.id,'close')).toHaveLength(1);
  expect(requests(binding.id,'update')).toHaveLength(1);await reply(requests(binding.id,'close')[0],{closed:true});
 }
});
