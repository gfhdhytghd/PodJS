import {expect,test} from 'bun:test';
import {VideoSessionPool,type VideoSessionPort} from '../src/video-session-pool';
import type {VideoSessionState,VideoTransform} from '../src/services';
const bounds={x:0,y:0,width:233,height:233};
function contentRect(t:VideoTransform,w:number,h:number,offset=0){const factor=t.scaleMode==='expanded'?Math.max(233/w,233/h):t.scaleMode==='shrunk'?233/Math.hypot(w,h):Math.min(233/w,233/h);return {x:(233-w*factor)/2,y:(233-h*factor)/2+offset,width:w*factor,height:h*factor};}
function fixture(){
 const previews:any[]=[];let settled:any;
 const transforms=new Map<string,VideoTransform>(),states=new Map<string,VideoSessionState>();let prepares=0;
 const value=(id:string):VideoSessionState=>({ok:true,sessionId:id,state:'prewarmed',positionMs:0,durationMs:1000,width:100,height:200,volume:1,muted:false,prewarming:true,presented:false,hasRenderedFirstFrame:true,targetBufferBytes:1});
 const port={
  capabilities:async()=>({decoderLimit:3,maxSessions:4,independentKeyframes:true,keyframeCache:true,surfacePromotion:true,verticalTransition:true}),
  prepare:(o:any)=>{prepares++;transforms.set(o.sessionId,{...o.transform});states.set(o.sessionId,value(o.sessionId));return {cancel(){},result:Promise.resolve(states.get(o.sessionId)!)};},
  setTransform:async(id:string,t:VideoTransform)=>{expect(states.has(id)).toBe(true);transforms.set(id,{...t});return states.get(id)!;},
  setLoop:async(id:string)=>states.get(id)!,state:async(id:string)=>states.get(id)!,setBounds:async(id:string)=>states.get(id)!,
  release:async(id:string)=>{states.delete(id);return {sessionId:id,released:true};},
  promote:async(id:string,o:any)=>{settled={bounds:o.bounds,transform:transforms.get(id)};return {...states.get(id)!,replacedHasRenderedFirstFrame:true};},
  transition:async(o:any)=>{previews.push({...o,transform:transforms.get(o.toSessionId)});return {...o,composited:true,fromOffsetYPx:0,toOffsetYPx:0};},
 } as unknown as VideoSessionPort;
 return {pool:new VideoSessionPool(port),transforms,states,previews,get settled(){return settled;},get prepares(){return prepares;}};
}
for(const mode of ['standard','expanded','shrunk'] as const)for(const [width,height] of [[100,200],[200,100]])for(const direction of ['up','down'] as const){
 test(`preview inherits ${mode} ${width}x${height} ${direction} and keeps it after promotion`,async()=>{
  const f=fixture();await f.pool.supportsPrewarm();const transform={scaleMode:mode,rotationDeg:0,panX:0,panY:0};
  await f.pool.prepare({key:'a'},{url:'a',bounds,transform}).result;
  await f.pool.prewarm({key:'b'},{url:'b',bounds,videoWidth:width,videoHeight:height});
  f.pool.selectStandby('b');for(const progress of [0,.25,.5,1])await f.pool.transition(progress,direction,bounds);
  const target=(await f.pool.diagnostics())!.slots.find(s=>s.key==='b')!;
  expect(f.transforms.get(target.sessionId)).toEqual(transform);
  await f.pool.promote(direction,bounds);expect(f.transforms.get(target.sessionId)).toEqual(transform);expect(f.prepares).toBe(2);
  for(const p of f.previews){expect(p.bounds).toEqual(f.settled.bounds);const offset=(direction==='up'?1:-1)*(1-p.progress)*bounds.height;const preview=contentRect(p.transform,width,height,offset);preview.y-=offset;const result=contentRect(f.settled.transform,width,height);for(const key of ["x","y","width","height"] as const)expect(preview[key]).toBeCloseTo(result[key],10);}
 });
}
test('mode changes relayout cached, pending and future neighbours without preparing again',async()=>{
 const f=fixture();await f.pool.supportsPrewarm();await f.pool.prepare({key:'a'},{url:'a',bounds}).result;
 await f.pool.prewarm({key:'b'},{url:'b',bounds});
 const pending=f.pool.prewarm({key:'c'},{url:'c',bounds});
 for(const scaleMode of ['expanded','shrunk','standard'] as const){await f.pool.setTransform({scaleMode});for(const t of f.transforms.values())expect(t).toEqual({scaleMode});}
 await pending;expect(f.prepares).toBe(3);
 await f.pool.prewarm({key:'d'},{url:'d',bounds});expect([...f.transforms.values()].at(-1)).toEqual({scaleMode:'standard'});
});
