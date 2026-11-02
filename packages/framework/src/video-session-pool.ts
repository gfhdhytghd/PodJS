import {benchmark,type VideoBenchmarkAction} from './benchmark.ts';
import {videoSessions,hostOperation,type HostOperation,type VideoBounds,type VideoFrameCapture,type VideoFrame,type VideoPrepare,type VideoSessionCapabilities,type VideoSessionState,type VideoSessionTransitionState,type VideoState} from './services.ts';
import type {VideoPagerDirection} from './video-paging.ts';
type VideoDecoderProfile=Pick<VideoPrepare,'videoMime'|'videoWidth'|'videoHeight'>;

export interface VideoSessionPort {
  captureFrame?(sessionId:string,options:VideoFrameCapture):Promise<VideoFrame>;
  capabilities(profile?:VideoDecoderProfile):Promise<VideoSessionCapabilities>;
  prepare(options:Parameters<typeof videoSessions.prepare>[0]):HostOperation<VideoSessionState>;
  play(sessionId:string):Promise<VideoSessionState>;
  retire(sessionId:string,handoff?:boolean):Promise<VideoSessionState>;
  pause(sessionId:string,benchmark?:VideoBenchmarkAction,handoff?:boolean):Promise<VideoSessionState>;
  seek(sessionId:string,positionMs:number):Promise<VideoSessionState>;
  stop(sessionId:string):Promise<VideoSessionState>;
  release(sessionId:string):Promise<{sessionId:string;released:boolean}>;
  setBounds(sessionId:string,bounds:VideoBounds):Promise<VideoSessionState>;
  state(sessionId:string):Promise<VideoSessionState>;
  setTransform(sessionId:string,transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}):Promise<VideoSessionState>;
  setVolume(sessionId:string,volume:number):Promise<VideoSessionState>;
  setMuted?(sessionId:string,muted:boolean):Promise<VideoSessionState>;
  setLoop(sessionId:string,loop:boolean):Promise<VideoSessionState>;
  promote(sessionId:string,options:{replaceSessionId?:string;bounds?:VideoBounds;autoplay?:boolean;loop?:boolean;adoptPreparing?:boolean;benchmark?:VideoBenchmarkAction}):Promise<VideoSessionState>;
  transition(options:{fromSessionId:string;toSessionId:string;progress:number;direction:VideoPagerDirection;bounds?:VideoBounds;benchmark?:VideoBenchmarkAction}):Promise<VideoSessionTransitionState>;
}

const defaultSessionPort:VideoSessionPort={
  ...videoSessions,
  capabilities:(profile={})=>hostOperation<VideoSessionCapabilities>("video.session.capabilities",profile).result,
};

let nextPoolId=1;
const state=(value:VideoSessionState):VideoState=>({
  appliedVolume:value.appliedVolume,audioTrackSelected:value.audioTrackSelected,bufferedMs:value.bufferedMs,networkStalled:value.networkStalled,presented:value.presented,metrics:value.metrics,ok:value.ok,state:value.state,positionMs:value.positionMs,durationMs:value.durationMs,completedLoops:value.completedLoops,loop:value.loop,
  width:value.width,height:value.height,volume:value.volume,volumeMax:value.volumeMax??15,volumeIndex:value.volumeIndex,muted:value.muted,
  sourceGeneration:value.sourceGeneration,errorCode:value.errorCode,hasRenderedFirstFrame:value.hasRenderedFirstFrame,
});

/** Retained surfaces, bounded by native codec and byte budgets. Three slots
 * retain both neighbours; two-slot hosts retain the selected/likely neighbour.
 * Slot-local revisions prevent one neighbour cancelling the other. */
interface WarmSlot<S> {direction?:'next'|'prev';id:string;source?:S;identity?:string;ready:boolean;revision:number;recentlyActive?:boolean;operation?:HostOperation<VideoSessionState>;pending?:Promise<VideoState>}
export class VideoSessionPool<Source extends {key:string}> {
  private initialPreparation?:{key:string;identity:string;generation:number;operation:HostOperation<VideoState>};
  private prepareIdentity(options:VideoPrepare){return JSON.stringify([options.url,options.videoUrl,options.audioUrl,options.type,options.videoMime,options.videoWidth,options.videoHeight,Object.entries(options.headers??{}).sort()]);}
  /** Begin a user-requested foreground prepare while navigation is being saved.
   * Hidden bounds retain the surface. The first visible prepare adopts it once. */
  prime(source:Source,options:VideoPrepare):HostOperation<VideoState>{
    const operation=this.prepare(source,{...options,autoplay:false,bounds:{...options.bounds,visible:false}});
    this.initialPreparation={key:source.key,identity:this.prepareIdentity(options),generation:this.generation,operation};
    return operation;
  }
  private loop=false;
  private activeId:string;
  private activeKey:string|undefined;
  private activeSource:Source|undefined;
  private activeIdentity:string|undefined;
  private slots:WarmSlot<Source>[];
  private capacity=2;
  private selected:WarmSlot<Source>|undefined;
  private generation=0;
  private promoting=false;
  private transitionRunning:Promise<void>|undefined;
  private latestTransition:{slot:WarmSlot<Source>;epoch:number;revision:number;fromId:string;toId:string;progress:number;direction:VideoPagerDirection;bounds:VideoBounds;benchmark?:VideoBenchmarkAction}|undefined;
  constructor(private readonly port:VideoSessionPort=defaultSessionPort){
    const id=nextPoolId++;
    this.activeId=`vp${id}a`;this.slots=[{id:`vp${id}b`,ready:false,revision:0},{id:`vp${id}c`,ready:false,revision:0}];
  }
  async diagnostics(){
    const generation=this.generation;
    const slots=[{role:'active',sessionId:this.activeId,key:this.activeKey,bound:!!this.activeKey},...this.slots.slice(0,this.capacity-1).map(s=>({role:'standby',sessionId:s.id,key:s.source?.key,bound:!!s.source}))];
    const [capabilities,...states]=await Promise.allSettled([this.port.capabilities(),...slots.map(slot=>slot.bound?this.port.state(slot.sessionId):Promise.resolve(undefined))]);
    if(generation!==this.generation)return undefined;
    return {capabilities:capabilities.status==='fulfilled'?capabilities.value as VideoSessionCapabilities:undefined,generation,standbyReady:this.slots.some(s=>s.ready),standbyPreparing:this.slots.some(s=>!!s.operation),slots:slots.map((slot,i)=>({...slot,state:states[i]?.status==='fulfilled'?states[i].value as VideoSessionState|undefined:undefined}))};
  }
  async supportsPrewarm(profile:VideoDecoderProfile={}):Promise<boolean>{
    const c=await this.port.capabilities(profile);
    this.capacity=Math.max(1,Math.min(3,c.decoderLimit,c.maxSessions??2));
    this.slots.sort((a,b)=>Number(b===this.selected)-Number(a===this.selected)||Number(b.direction==='next')-Number(a.direction==='next'));
    await Promise.all(this.slots.slice(this.capacity-1).filter(s=>s.source||s.operation).map(s=>this.evict(s,'capacity')));
    const supported=this.capacity>=2&&c.surfacePromotion&&c.verticalTransition;
    benchmark.begin('prewarm_policy',{reason:supported?(this.capacity>=3?'bidirectional':'single_neighbour'):'capacity',slots:this.capacity});
    return supported;
  }
  get neighbourCapacity(){return this.capacity-1;}
  private evict(slot:WarmSlot<Source>,reason:'stale'|'capacity'|'release'|'failure'|'timeout'){
    if(slot.source)benchmark.begin('prewarm_evicted',{reason,direction:slot.direction,generation:slot.revision});
    slot.revision++;slot.operation?.cancel();slot.operation=undefined;slot.pending=undefined;slot.source=undefined;slot.identity=undefined;slot.ready=false;
    if(this.selected===slot)this.selected=undefined;
    return this.port.release(slot.id).catch(()=>undefined);
  }
  retainNeighbours(keys:readonly string[]){for(const slot of this.slots)if(slot.source&&!keys.includes(slot.source.key))this.evict(slot,'stale');}
  prepare(source:Source,options:VideoPrepare):HostOperation<VideoState>{
    const initial=this.initialPreparation;this.initialPreparation=undefined;
    if(initial&&initial.key===source.key&&initial.identity===this.prepareIdentity(options)&&initial.generation===this.generation){
      const own=this.generation;let cancelled=false,settled=false;
      this.loop=options.loop===true;
      const current=()=>!cancelled&&own===this.generation;
      const result=initial.operation.result.then(async()=>{
        if(!current())throw Error('stale initial video preparation');
        await this.port.setBounds(this.activeId,options.bounds);
        if(!current())throw Error('stale initial video preparation');
        let value=await this.syncLoop(this.activeId);
        if(!current())throw Error('stale initial video preparation');
        if(options.autoplay===true)value=await this.port.play(this.activeId);
        if(!current())throw Error('stale initial video preparation');
        return state(value);
      }).finally(()=>{settled=true;});
      // An early native failure stays terminal; adoption must not silently retry it.
      return {result,cancel:()=>{if(settled||cancelled)return;cancelled=true;if(own===this.generation){this.generation++;initial.operation.cancel();void this.port.release(this.activeId).catch(()=>{});}}};
    }
    initial?.operation.cancel();
    const identity=this.prepareIdentity(options);
    const warming=this.slots.find(s=>s.source?.key===source.key&&s.identity===identity&&(s.ready||s.operation));
    if(warming)return this.adoptPreparing(warming,source,options);
    const own=++this.generation;this.loop=options.loop===true;this.activeKey=source.key;this.activeSource=source;this.activeIdentity=this.prepareIdentity(options);
    for(const slot of this.slots)if(slot.source||slot.operation)void this.evict(slot,'stale');
    // Explicit sessions are foreground-only. Do not leak a legacy preference
    // into the native API (which correctly rejects backgroundPlayback=true).
    const {backgroundPlayback,...foreground}=options;
    const operation=this.port.prepare({...foreground,sessionId:this.activeId,prewarm:false,autoplay:options.autoplay===true});
    return {cancel:()=>operation.cancel(),result:operation.result.then(value=>{
      if(own!==this.generation)throw new Error('stale video session preparation');
      return this.syncLoop(this.activeId).then(state);
    })};
  }
  /** A released gesture owns this source now. Keep its in-flight transport,
   * extractor and decoder instead of cancelling them and preparing it again. */
  private adoptPreparing(slot:WarmSlot<Source>,source:Source,options:VideoPrepare):HostOperation<VideoState>{
    const own=++this.generation,id=slot.id,replacedId=this.activeId;
    const previousSource=this.activeSource,previousIdentity=this.activeIdentity;
    const operation=slot.operation;
    this.loop=options.loop===true;this.selected=undefined;this.latestTransition=undefined;
    slot.revision++;slot.operation=undefined;slot.pending=undefined;
    slot.direction=slot.direction==='next'?'prev':slot.direction==='prev'?'next':undefined;
    slot.recentlyActive=true;slot.id=replacedId;slot.source=previousSource;slot.identity=previousIdentity;slot.ready=false;
    this.activeId=id;this.activeKey=source.key;this.activeSource=source;this.activeIdentity=this.prepareIdentity(options);
    // A speculative codec release can block the UI thread. Reclaim its slot
    // when the following prewarm needs it, after the foreground reveal.
    let cancelled=false,settled=false;
    const result=(async()=>{
      const loop=this.loop;
      const promoted=await this.port.promote(id,{replaceSessionId:replacedId,bounds:options.bounds,autoplay:options.autoplay===true,loop,adoptPreparing:true,...(options.benchmark?{benchmark:options.benchmark}:{})});
      if(cancelled||own!==this.generation)throw Error('stale video session adoption');
      // Current hosts return old-surface readiness in the promotion command.
      // Older hosts retain the diagnostic fallback. Neither path reparses media.
      const retired=typeof promoted.replacedHasRenderedFirstFrame==='boolean'?{hasRenderedFirstFrame:promoted.replacedHasRenderedFirstFrame,state:'prewarmed'}:await this.port.state(replacedId).catch(()=>undefined);
      if(cancelled||own!==this.generation)throw Error('stale video session adoption');
      slot.ready=!!previousSource&&retired?.hasRenderedFirstFrame===true&&retired.state!=='error';
      if(typeof promoted.replacedHasRenderedFirstFrame!=='boolean'||loop!==this.loop)await this.syncLoop(id);
      if(cancelled||own!==this.generation)throw Error('stale video session adoption');
      return state(promoted);
    })().finally(()=>{settled=true;});
    return {result,cancel:()=>{if(cancelled||settled)return;cancelled=true;if(own===this.generation){this.generation++;operation?.cancel();}}};
  }
  async prewarm(source:Source,options:VideoPrepare,direction?:'next'|'prev'):Promise<VideoState>{
    if(source.key===this.activeKey)return this.state();
    let slot=this.slots.find(s=>s.source?.key===source.key);
    if(slot?.ready)return this.port.state(slot.id).then(state);
    if(slot?.pending)return slot.pending;
    slot??=this.slots.slice(0,this.capacity-1).find(s=>!s.source);
    if(!slot){slot=this.slots.find(s=>s!==this.selected&&!s.recentlyActive)??this.slots.find(s=>s!==this.selected)??this.slots[0];this.evict(slot,'capacity');}
    const target=slot,own=++target.revision;
    target.recentlyActive=false;target.source=source;target.identity=this.prepareIdentity(options);target.ready=false;target.direction=direction;
    const seq=benchmark.begin('prewarm_requested',{generation:own,direction});
    const {backgroundPlayback,...foreground}=options;
    const operation=this.port.prepare({...foreground,loop:this.loop,sessionId:target.id,prepareSeq:seq,prewarm:true,autoplay:false,muted:false});
    target.operation=operation;
    // Slot revisions fence evictions. A promotion can explicitly retain an
    // in-flight following neighbour while the pool generation advances.
    const valid=()=>own===target.revision&&target.source?.key===source.key;
    const pending=(async()=>{try{
      const value=await operation.result;
      if(!valid())throw new Error('stale video session prewarm');
      while(true){const loop=this.loop;await this.port.setLoop(target.id,loop);if(!valid())throw new Error('stale video session prewarm');
        if(loop===this.loop){target.ready=value.hasRenderedFirstFrame;if(target.ready)benchmark.mark('prewarm_ready',seq,{generation:own,direction,...(value.bufferedMs===undefined?{}:{buffered_ms:value.bufferedMs})});return state(value);}}
    }catch(error){if(valid()){const code=String((error as {code?:unknown})?.code??'');this.evict(target,code==='timeout'?'timeout':/resource|decoder.*limit/.test(code)?'capacity':'failure');}throw error;}finally{if(target.operation===operation){target.operation=undefined;target.pending=undefined;}}})();
    target.pending=pending;return pending;
  }
  ownsActiveSession(id:string){return id===this.activeId;}
  captureFrame(options:VideoFrameCapture){if(!this.port.captureFrame)return Promise.reject(Error('frame_capture_unavailable'));return this.port.captureFrame(this.activeId,options);}
  invalidateStandby(key:string){const slot=this.slots.find(s=>s.source?.key===key);if(slot)this.evict(slot,'stale');}
  hasStandby(key?:string):boolean{return !!key&&this.slots.some(s=>s.ready&&s.source?.key===key);}
  selectStandby(key:string):boolean{if(this.promoting)return false;const slot=this.slots.find(s=>s.ready&&s.source?.key===key);if(!slot)return false;this.selected=slot;return true;}
  standbySource():Source|undefined{return (this.selected??this.slots.find(s=>s.ready))?.source;}
  async transition(progress:number,direction:VideoPagerDirection,bounds:VideoBounds,benchmark?:VideoBenchmarkAction):Promise<void>{
    if(this.promoting)throw Error('stale video session transition during promotion');
    const slot=this.selected??this.slots.find(s=>s.ready);if(!slot?.ready||!slot.source)throw new Error('video session target is not prewarmed');
    this.selected=slot;this.latestTransition={slot,epoch:this.generation,revision:slot.revision,fromId:this.activeId,toId:slot.id,progress:Math.max(0,Math.min(1,progress)),direction,bounds,...(benchmark?{benchmark}:{})};
    if(this.transitionRunning)return this.transitionRunning;
    this.transitionRunning=(async()=>{while(this.latestTransition){const {slot,epoch,revision,fromId,toId,...next}=this.latestTransition;this.latestTransition=undefined;if(epoch!==this.generation||revision!==slot.revision||fromId===toId)continue;await this.port.transition({fromSessionId:fromId,toSessionId:toId,...next});}})();
    try{await this.transitionRunning;}finally{this.transitionRunning=undefined;}
  }
  async promote(direction:VideoPagerDirection,bounds:VideoBounds,autoplay=true,benchmark?:VideoBenchmarkAction,retainKeys:readonly string[]=[]):Promise<{source:Source;state:VideoState}>{
    const slot=this.selected??this.slots.find(s=>s.ready),target=slot?.source;if(!slot||!target||!slot.ready)throw new Error('video session target is not prewarmed');
    if(this.promoting)throw Error('stale video session promotion');
    this.promoting=true;
    try{
    if(this.transitionRunning)await this.transitionRunning;
    const own=this.generation,id=slot.id,identity=slot.identity;
    const loop=this.loop;
    const promoted=await this.port.promote(id,{replaceSessionId:this.activeId,bounds,autoplay,loop,...(benchmark?{benchmark}:{})});
    // A fast swipe can retire the initial active before it decoded anything.
    // Retain its source, but never label that surface ready without host proof.
    const retired=typeof promoted.replacedHasRenderedFirstFrame==='boolean'?{hasRenderedFirstFrame:promoted.replacedHasRenderedFirstFrame,state:'prewarmed'}:await this.port.state(this.activeId).catch(()=>undefined);
    if(typeof promoted.replacedHasRenderedFirstFrame!=='boolean'||loop!==this.loop)await this.syncLoop(id);
    if(own!==this.generation||slot.source!==target)throw new Error('stale video session promotion');
    this.generation++;
    slot.recentlyActive=true;slot.id=this.activeId;slot.direction=direction==='up'?'prev':'next';slot.source=this.activeSource;slot.identity=this.activeIdentity;slot.ready=!!this.activeSource&&retired?.hasRenderedFirstFrame===true&&retired.state!=='error';slot.revision++;
    this.activeId=id;this.activeKey=target.key;this.activeSource=target;this.activeIdentity=identity;this.selected=undefined;
    for(const other of this.slots)if(other!==slot&&!retainKeys.includes(other.source?.key??''))this.evict(other,'stale');
    return {source:target,state:state(promoted)};
    }finally{this.promoting=false;}
  }
  private async syncLoop(id:string):Promise<VideoSessionState>{while(true){const loop=this.loop,value=await this.port.setLoop(id,loop);if(loop===this.loop)return value;}}
  async setLoop(loop:boolean):Promise<void>{this.loop=loop;await Promise.all([...(this.activeKey?[this.syncLoop(this.activeId)]:[]),...this.slots.filter(s=>s.ready).map(s=>this.syncLoop(s.id))]);}
  play():Promise<VideoState>{return this.port.play(this.activeId).then(state);}
  retire(handoff=false):Promise<VideoState>{return this.port.retire(this.activeId,handoff).then(state);}
  pause(benchmark?:VideoBenchmarkAction,handoff=false):Promise<VideoState>{return this.port.pause(this.activeId,benchmark,handoff).then(state);}
  seek(positionMs:number):Promise<VideoState>{return this.port.seek(this.activeId,positionMs).then(state);}
  stop():Promise<VideoState>{return this.port.stop(this.activeId).then(state);}
  setBounds(bounds:VideoBounds):Promise<VideoState>{return this.port.setBounds(this.activeId,bounds).then(state);}
  state():Promise<VideoState>{return this.port.state(this.activeId).then(state);}
  setTransform(transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}):Promise<VideoState>{return this.port.setTransform(this.activeId,transform).then(state);}
  setMuted(muted:boolean):Promise<VideoState>{if(!this.port.setMuted)return Promise.reject(new Error("unsupported"));return this.port.setMuted(this.activeId,muted).then(state);}
  setVolume(volume:number):Promise<VideoState>{return this.port.setVolume(this.activeId,volume).then(state);}
  async releaseAll():Promise<void>{this.initialPreparation?.operation.cancel();this.initialPreparation=undefined;this.generation++;this.latestTransition=undefined;this.activeKey=undefined;this.activeSource=undefined;this.activeIdentity=undefined;await Promise.allSettled([this.port.release(this.activeId),...this.slots.map(s=>this.evict(s,'release'))]);}
}
