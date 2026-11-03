import {benchmark,type VideoBenchmarkAction} from './benchmark.ts';
import {videoSessions,hostOperation,delay,type HostOperation,type VideoBounds,type VideoTransform,type VideoFrameCapture,type VideoFrame,type VideoPrepare,type VideoSessionCapabilities,type VideoSessionState,type VideoSessionTransitionState,type VideoState} from './services.ts';
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
  transition(options:{fromSessionId:string;toSessionId:string;progress:number;direction:VideoPagerDirection;bounds?:VideoBounds;benchmark?:VideoBenchmarkAction;trackPointer?:boolean}):Promise<VideoSessionTransitionState>;
}

const defaultSessionPort:VideoSessionPort={
  ...videoSessions,
  capabilities:(profile={})=>hostOperation<VideoSessionCapabilities>("video.session.capabilities",profile).result,
};

let nextPoolId=1;
const state=(value:VideoSessionState):VideoState=>({
  appliedVolume:value.appliedVolume,audioTrackSelected:value.audioTrackSelected,bufferedMs:value.bufferedMs,networkStalled:value.networkStalled,presented:value.presented,metrics:value.metrics,ok:value.ok,state:value.state,positionMs:value.positionMs,durationMs:value.durationMs,completedLoops:value.completedLoops,loop:value.loop,
  width:value.width,height:value.height,volume:value.volume,volumeMax:value.volumeMax??15,volumeIndex:value.volumeIndex,muted:value.muted,
  hasCachedFirstFrame:value.hasCachedFirstFrame,sourceGeneration:value.sourceGeneration,errorCode:value.errorCode,hasRenderedFirstFrame:value.hasRenderedFirstFrame,
});

/** Retained surfaces, bounded by native codec and byte budgets. Three slots retain immediate neighbours; five retain two on each side.
 * Two-slot hosts retain the selected/likely neighbour.
 * Slot-local revisions prevent one neighbour cancelling the other. */
interface WarmSlot<S> {direction?:'next'|'prev';id:string;source?:S;identity?:string;ready:boolean;revision:number;recentlyActive?:boolean;prepareSeq?:number;operation?:HostOperation<VideoSessionState>;pending?:Promise<VideoState>}
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
  private readonly ordinals=new Map<string,number>();
  private ordinal(key:string){let value=this.ordinals.get(key);if(value===undefined&&this.ordinals.size<4096){value=this.ordinals.size+1;this.ordinals.set(key,value);}return value??0;}
  private slotOrdinal(id:string){return id.charCodeAt(id.length-1)-96;}
  traceSelection(key:string|undefined,action?:VideoBenchmarkAction){if(action)benchmark.mark('pool_stage',action.seq,{pool_stage:'select',source_ordinal:key?this.ordinal(key):0,generation:action.generation,pool_generation:this.generation});}
  traceNeighbour(key:string,index:number,anchor:number){benchmark.begin('pool_stage',{pool_stage:'neighbour',source_ordinal:this.ordinal(key),queue_index:index,anchor_index:anchor,pool_generation:this.generation});}
  private loop=false;
  private transform:VideoTransform={};
  private activeId:string;
  private activeKey:string|undefined;
  private activeSource:Source|undefined;
  private activeIdentity:string|undefined;
  private activePrepareSeq:number|undefined;
  private slots:WarmSlot<Source>[];
  private capacity=2;
  private keyframeCache=false;
  private independentKeyframes=false;
  private neighbourhoodRevision=0;
  private neighbourhoodSlot?:WarmSlot<Source>;
  private neighbourhoodWait?:HostOperation<void>;
  private selected:WarmSlot<Source>|undefined;
  private generation=0;
  private promoting=false;
  private transitionRunning:Promise<void>|undefined;
  private dragEpoch=0;
  private dragTracksPointer=false;
  private nativeDragKey:string|undefined;
  private latestTransition:{slot:WarmSlot<Source>;trackPointer:boolean;dragEpoch:number;key:string;epoch:number;revision:number;fromId:string;toId:string;progress:number;direction:VideoPagerDirection;bounds:VideoBounds;benchmark?:VideoBenchmarkAction}|undefined;
  constructor(private readonly port:VideoSessionPort=defaultSessionPort){
    const id=nextPoolId++;
    this.activeId=`vp${id}a`;this.slots=Array.from({length:7},(_,i)=>({id:`vp${id}${String.fromCharCode(98+i)}`,ready:false,revision:0}));
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
    this.independentKeyframes=c.independentKeyframes===true;
    this.capacity=Math.max(1,Math.min(8,this.independentKeyframes?4:c.decoderLimit,c.maxSessions??2));
    this.keyframeCache=c.keyframeCache===true;
    this.slots.sort((a,b)=>Number(b===this.selected)-Number(a===this.selected)||Number(b.direction==='next')-Number(a.direction==='next'));
    await Promise.all(this.slots.slice(this.capacity-1).filter(s=>s.source||s.operation).map(s=>this.evict(s,'capacity')));
    const supported=this.capacity>=2&&c.surfacePromotion&&c.verticalTransition;
    benchmark.begin('prewarm_policy',{reason:supported?(this.capacity>=3?'bidirectional':'single_neighbour'):'capacity',slots:this.capacity});
    return supported;
  }
  get supportsKeyframes(){return this.keyframeCache;}
  get supportsIndependentKeyframes(){return this.independentKeyframes;}
  get neighbourCapacity(){return this.capacity-1;}
  private evict(slot:WarmSlot<Source>,reason:'stale'|'capacity'|'release'|'failure'|'timeout'){
    if(slot.source){const fields={reason,direction:slot.direction,generation:slot.revision,source_ordinal:this.ordinal(slot.source.key),slot_ordinal:this.slotOrdinal(slot.id),pool_generation:this.generation};if(slot.prepareSeq)benchmark.mark('prewarm_evicted',slot.prepareSeq,fields);else benchmark.begin('prewarm_evicted',fields);}
    slot.revision++;slot.operation?.cancel();slot.operation=undefined;slot.pending=undefined;slot.source=undefined;slot.identity=undefined;slot.prepareSeq=undefined;slot.ready=false;
    if(this.selected===slot)this.selected=undefined;
    return this.port.release(slot.id).catch(()=>undefined);
  }
  retainNeighbours(keys:readonly string[]){for(const slot of this.slots)if(slot.source&&!keys.includes(slot.source.key))this.evict(slot,'stale');}
  prepare(source:Source,options:VideoPrepare):HostOperation<VideoState>{
    this.transform={...(options.transform??this.transform)};
    options={...options,transform:this.transform};
    this.cancelNeighbourhood();
    this.traceSelection(source.key,options.benchmark);
    const initial=this.initialPreparation;this.initialPreparation=undefined;
    if(initial&&initial.key===source.key&&initial.identity===this.prepareIdentity(options)&&initial.generation===this.generation){
      const own=this.generation;let cancelled=false,settled=false;
      this.loop=options.loop===true;
      const current=()=>!cancelled&&own===this.generation;
      const result=initial.operation.result.then(async()=>{
        if(!current())throw Error('stale initial video preparation');
        if(Object.keys(this.transform).length)await this.port.setTransform(this.activeId,this.transform);
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
    const own=++this.generation;this.activePrepareSeq=undefined;this.loop=options.loop===true;this.activeKey=source.key;this.activeSource=source;this.activeIdentity=this.prepareIdentity(options);
    const retired=this.slots.filter(slot=>slot.source||slot.operation).map(slot=>({slot,revision:slot.revision}));
    if(!this.keyframeCache)for(const {slot} of retired)void this.evict(slot,'stale');
    // Explicit sessions are foreground-only. Do not leak a legacy preference
    // into the native API (which correctly rejects backgroundPlayback=true).
    const {backgroundPlayback,...foreground}=options;
    const operation=this.port.prepare({...foreground,sessionId:this.activeId,sourceOrdinal:this.ordinal(source.key),slotOrdinal:this.slotOrdinal(this.activeId),prewarm:false,autoplay:options.autoplay===true});
    return {cancel:()=>operation.cancel(),result:operation.result.then(value=>{
      if(own!==this.generation)throw new Error('stale video session preparation');
      // Reveal a cached native frame before releasing unrelated codec leases.
      if(this.keyframeCache)for(const {slot,revision} of retired)if(slot.revision===revision)void this.evict(slot,'stale');
      return this.syncLoop(this.activeId).then(state);
    })};
  }
  /** A released gesture owns this source now. Keep its in-flight transport,
   * extractor and decoder instead of cancelling them and preparing it again. */
  private adoptPreparing(slot:WarmSlot<Source>,source:Source,options:VideoPrepare):HostOperation<VideoState>{
    const own=++this.generation,id=slot.id,replacedId=this.activeId;
    const previousSource=this.activeSource,previousIdentity=this.activeIdentity,previousPrepareSeq=this.activePrepareSeq;
    this.activePrepareSeq=slot.prepareSeq;slot.prepareSeq=previousPrepareSeq;
    const operation=slot.operation;
    this.loop=options.loop===true;this.selected=undefined;this.latestTransition=undefined;
    slot.revision++;slot.operation=undefined;slot.pending=undefined;
    slot.direction=slot.direction==='next'?'prev':slot.direction==='prev'?'next':undefined;
    for(const other of this.slots)other.recentlyActive=false;
    slot.recentlyActive=true;slot.id=replacedId;slot.source=previousSource;slot.identity=previousIdentity;slot.ready=false;
    this.activeId=id;this.activeKey=source.key;this.activeSource=source;this.activeIdentity=this.prepareIdentity(options);
    // A speculative codec release can block the UI thread. Reclaim its slot
    // when the following prewarm needs it, after the foreground reveal.
    let cancelled=false,settled=false,recovery:HostOperation<VideoSessionState>|undefined;
    const result=(async()=>{
      const loop=this.loop;
      let promoted:VideoSessionState;
      try{if(Object.keys(this.transform).length)await this.port.setTransform(id,this.transform);promoted=await this.port.promote(id,{replaceSessionId:replacedId,bounds:options.bounds,autoplay:options.autoplay===true,loop,adoptPreparing:true,...(options.benchmark?{benchmark:options.benchmark}:{})});}
      catch(error){
        const failure=error as {code?:string;message?:string};
        // Native expiry can win between guest selection and host promotion.
        // Only this absent-session acknowledgement permits one cold prepare.
        // Transport, authorization and decoder failures remain terminal.
        if(failure.code!=='invalid_argument'||failure.message!=='unknown_video_session'||cancelled||own!==this.generation)throw error;
        const {backgroundPlayback,...foreground}=options;
        await this.port.retire(replacedId,true).catch(()=>undefined);
        if(cancelled||own!==this.generation)throw Error('stale video session adoption');
        recovery=this.port.prepare({...foreground,sessionId:id,sourceOrdinal:this.ordinal(source.key),slotOrdinal:this.slotOrdinal(id),prewarm:false,autoplay:options.autoplay===true});
        promoted=await recovery.result;
      }
      if(cancelled||own!==this.generation)throw Error('stale video session adoption');
      // Current hosts return old-surface readiness in the promotion command.
      // Older hosts retain the diagnostic fallback. Neither path reparses media.
      const retired=typeof promoted.replacedHasRenderedFirstFrame==='boolean'?{hasRenderedFirstFrame:promoted.replacedHasRenderedFirstFrame,state:'prewarmed'}:await this.port.state(replacedId).catch(()=>undefined);
      if(cancelled||own!==this.generation)throw Error('stale video session adoption');
      slot.ready=!!previousSource&&(retired?.hasRenderedFirstFrame===true||promoted.replacedHasCachedFirstFrame===true)&&retired?.state!=='error';
      if(typeof promoted.replacedHasRenderedFirstFrame!=='boolean'||loop!==this.loop)await this.syncLoop(id);
      if(cancelled||own!==this.generation)throw Error('stale video session adoption');
      return state(promoted);
    })().finally(()=>{settled=true;});
    return {result,cancel:()=>{if(cancelled||settled)return;cancelled=true;if(own===this.generation){this.generation++;operation?.cancel();recovery?.cancel();}}};
  }
  async prewarm(source:Source,options:VideoPrepare,direction?:'next'|'prev',background=false):Promise<VideoState>{
    if(source.key===this.activeKey)return this.state();
    let slot=this.slots.find(s=>s.source?.key===source.key);
    if(slot?.ready&&slot.identity===this.prepareIdentity(options))return this.port.state(slot.id).then(state);
    if(slot?.pending&&slot.identity===this.prepareIdentity(options))return slot.pending;
    if(!background)this.cancelNeighbourhood();
    let released:Promise<unknown>|undefined;
    if(slot&&(slot.operation||slot.ready||slot.pending))released=this.evict(slot,'stale');
    slot??=this.slots.slice(0,this.capacity-1).find(s=>!s.source);
    if(!slot){const usable=this.slots.slice(0,this.capacity-1);slot=usable.find(s=>s!==this.selected&&!s.recentlyActive&&!s.pending)??usable.find(s=>s!==this.selected&&!s.recentlyActive)??usable.find(s=>s!==this.selected)??usable[0];if(!slot)throw Error('video_decoder_limit');released=this.evict(slot,'capacity');}
    const target=slot,own=++target.revision;
    // Reserve before waiting for native release. Concurrent neighbours must not
    // claim the same temporarily empty slot while its old codec is closing.
    target.recentlyActive=false;target.source=source;target.identity=this.prepareIdentity(options);target.ready=false;target.direction=direction;
    const seq=benchmark.begin('prewarm_requested',{generation:own,direction,source_ordinal:this.ordinal(source.key),slot_ordinal:this.slotOrdinal(target.id),pool_generation:this.generation});target.prepareSeq=seq;
    const {backgroundPlayback,...foreground}=options;
    let operation:HostOperation<VideoSessionState>|undefined;
    const valid=()=>own===target.revision&&target.source?.key===source.key;
    const pending=(async()=>{try{
      if(released)await released;
      if(!valid())throw new Error('stale video session prewarm reservation');
      let preparedLoop=this.loop;
      operation=this.port.prepare({...foreground,transform:this.transform,loop:preparedLoop,sessionId:target.id,sourceOrdinal:this.ordinal(source.key),slotOrdinal:this.slotOrdinal(target.id),prepareSeq:seq,prewarm:true,autoplay:false,muted:false});
      target.operation=operation;
      const value=await operation.result;
      if(!valid())throw new Error('stale video session prewarm');
      // Prepare already applied this loop value. A redundant host round trip
      // must not hold decoded pixels behind unrelated native commands.
      while(preparedLoop!==this.loop){preparedLoop=this.loop;await this.port.setLoop(target.id,preparedLoop);if(!valid())throw new Error('stale video session prewarm');}
      target.ready=value.hasRenderedFirstFrame||value.hasCachedFirstFrame===true;
      if(target.ready)benchmark.mark('prewarm_ready',seq,{generation:own,direction,...(value.bufferedMs===undefined?{}:{buffered_ms:value.bufferedMs})});return state(value);
    }catch(error){if(valid()){const code=String((error as {code?:unknown})?.code??'');this.evict(target,code==='timeout'?'timeout':/resource|decoder.*limit/.test(code)?'capacity':'failure');}throw error;}finally{if(valid()&&target.operation===operation){target.operation=undefined;target.pending=undefined;}}})();
    target.pending=pending;return pending;
  }
  cancelNeighbourhood(){
    this.neighbourhoodRevision++;
    this.neighbourhoodWait?.cancel();this.neighbourhoodWait=undefined;
    this.neighbourhoodSlot?.operation?.cancel();this.neighbourhoodSlot=undefined;
  }
  /** Decode the bounded neighbourhood once, retaining pixels after codec eviction.
   * Loaders run serially and must use already-authorized media (no detail fetch).
   * The nearest two live standbys are restored last on small codec budgets. */
  async warmNeighbourhood(loaders:readonly (()=>Promise<{source:Source;options:VideoPrepare;direction:'next'|'prev'}|undefined>)[]):Promise<void>{
    if(this.independentKeyframes||!this.keyframeCache||this.capacity<2)return;
    this.cancelNeighbourhood();const revision=this.neighbourhoodRevision,generation=this.generation;
    const current=()=>revision===this.neighbourhoodRevision&&generation===this.generation;
    const nearest:{source:Source;options:VideoPrepare;direction:'next'|'prev'}[]=[];
    const loaded:typeof nearest=[];
    const deadline=Date.now()+30_000;
    const foregroundReady=async()=>{
      while(current()){
        const active=await this.port.state(this.activeId);if(!current())return false;
        if(active.hasRenderedFirstFrame&&active.state!=='buffering'&&!active.networkStalled&&(active.state==='paused'||(active.bufferedMs??0)>=1000))return true;
        if(Date.now()>=deadline)return false;
        this.neighbourhoodWait=delay(250);await this.neighbourhoodWait.result;this.neighbourhoodWait=undefined;
      }return false;
    };
    try{
      for(const load of loaders.slice(0,7)){
        if(!current())return;
        const entry=await load();if(!current())return;if(!entry)continue;
        if(!nearest.some(n=>n.direction===entry.direction))nearest.push(entry);loaded.push(entry);
        while(current()){
          if(!await foregroundReady())return;
          try{await this.decodeKeyframe(entry.source,entry.options,entry.direction,current);break;}
          catch(error){
            const value=error as {code?:string;message?:string};
            if(!current())return;
            if(value.message!=='video_foreground_busy'&&value.code!=='cancelled')throw error;
            if(Date.now()>=deadline)return;
            this.neighbourhoodWait=delay(250);await this.neighbourhoodWait.result;this.neighbourhoodWait=undefined;
          }
        }
        if(!current())return;
      }
      const live=[...nearest,...loaded.filter(entry=>!nearest.includes(entry))].slice(0,this.capacity-1);
      for(const entry of live){
        if(!current()||!await foregroundReady())return;
        try{await this.prewarm(entry.source,entry.options,entry.direction,true);}
        catch(error){if(String((error as {code?:unknown})?.code)==='resource_exhausted'){await this.supportsPrewarm(entry.options);return;}throw error;}
      }
    }finally{if(current())this.neighbourhoodSlot=undefined;}
  }
  private async decodeKeyframe(source:Source,options:VideoPrepare,direction:'next'|'prev',current:()=>boolean){
    const live=this.slots.find(s=>s.source?.key===source.key&&s.identity===this.prepareIdentity(options)&&s.ready);
    if(live)return; // A live standby already captured its frame zero.
    // Native admission may never steal the active decoder. Free one speculative
    // lease before constructing the short-lived worker, even on a 3-codec watch.
    const usable=this.slots.slice(0,this.capacity-1);
    // Frame-zero readback is opportunistic. Never evict either live neighbour
    // to decode a more distant image on a three-decoder host.
    let slot=usable.find(s=>!s.source&&!s.operation);
    // A retired page may lend its codec only after native confirms retained
    // decoded pixels. A missing readback never sacrifices either live neighbour.
    if(!slot){
      const retired=usable.find(s=>s.recentlyActive&&s.ready&&s!==this.selected);
      if(retired){const revision=retired.revision,value=await this.port.state(retired.id);
        if(!current())return;
        if(retired.revision===revision&&value.keyframeAvailable)slot=retired;
      }
    }
    if(!slot)return;
    await this.evict(slot,'capacity');
    if(!current())return;
    this.neighbourhoodSlot=slot;
    const {backgroundPlayback,...foreground}=options;
    const operation=this.port.prepare({...foreground,sessionId:slot.id,sourceOrdinal:this.ordinal(source.key),slotOrdinal:this.slotOrdinal(slot.id),prewarm:true,keyframeOnly:true,autoplay:false});
    slot.operation=operation;
    try{const value=await operation.result;if(!value.keyframeAvailable)throw Error('keyframe_unavailable');}
    finally{if(slot.operation===operation)slot.operation=undefined;}
  }
  ownsActiveSession(id:string){return id===this.activeId;}
  captureFrame(options:VideoFrameCapture){if(!this.port.captureFrame)return Promise.reject(Error('frame_capture_unavailable'));return this.port.captureFrame(this.activeId,options);}
  invalidateStandby(key:string){const slot=this.slots.find(s=>s.source?.key===key);if(slot)this.evict(slot,'stale');}
  hasStandby(key?:string):boolean{return !!key&&this.slots.some(s=>s.ready&&s.source?.key===key);}
  selectStandby(key:string,allowPreparing=false):boolean{this.cancelNeighbourhood();if(this.promoting)return false;const slot=this.slots.find(s=>(s.ready||allowPreparing&&this.independentKeyframes&&!!s.operation)&&s.source?.key===key);if(!slot)return false;this.selected=slot;return true;}
  /** Authorized metadata may be adopted while decode is still pending. */
  preparingSourceFor(key:string|undefined):Source|undefined{return key?this.slots.find(s=>(s.ready||s.operation)&&s.source?.key===key)?.source:undefined;}
  sourceFor(key:string|undefined):Source|undefined{return key?this.slots.find(s=>s.ready&&s.source?.key===key)?.source:undefined;}
  standbySource():Source|undefined{return (this.selected??this.slots.find(s=>s.ready))?.source;}
  /** Opt in only after the gesture recognizer selects a physical pan. */
  beginDrag(){this.cancelNeighbourhood();this.endDrag();this.dragTracksPointer=true;}
  /** Rebound and programmatic transitions must never reacquire a live pointer. */
  endDrag(){this.dragTracksPointer=false;this.invalidateNativeDrag();}
  private invalidateNativeDrag(){this.dragEpoch++;this.nativeDragKey=undefined;this.latestTransition=undefined;}
  async transition(progress:number,direction:VideoPagerDirection,bounds:VideoBounds,benchmark?:VideoBenchmarkAction):Promise<void>{
    if(this.promoting)throw Error('stale video session transition during promotion');
    const slot=this.selected??this.slots.find(s=>s.ready);if(!slot?.source||!(slot.ready||this.independentKeyframes&&slot.operation))throw new Error('video session target is not prewarmed');
    const key=JSON.stringify([this.generation,slot.revision,this.activeId,slot.id,direction,bounds]);
    if(!benchmark&&this.nativeDragKey===key)return;
    this.selected=slot;this.latestTransition={slot,trackPointer:this.dragTracksPointer,dragEpoch:this.dragEpoch,key,epoch:this.generation,revision:slot.revision,fromId:this.activeId,toId:slot.id,progress:Math.max(0,Math.min(1,progress)),direction,bounds,...(benchmark?{benchmark}:{})};
    if(this.transitionRunning)return this.transitionRunning;
    this.transitionRunning=(async()=>{while(this.latestTransition){
      const {slot,dragEpoch,key,epoch,revision,fromId,toId,...next}=this.latestTransition;this.latestTransition=undefined;
      if(epoch!==this.generation||revision!==slot.revision||fromId===toId||!next.benchmark&&this.nativeDragKey===key)continue;
      const reply=await this.port.transition({fromSessionId:fromId,toSessionId:toId,...next});
      if(reply.nativeTracking===true&&dragEpoch===this.dragEpoch&&epoch===this.generation&&revision===slot.revision)this.nativeDragKey=key;
    }})();
    try{await this.transitionRunning;}finally{this.transitionRunning=undefined;}
  }
  async promote(direction:VideoPagerDirection,bounds:VideoBounds,autoplay=true,benchmark?:VideoBenchmarkAction,retainKeys:readonly string[]=[]):Promise<{source:Source;state:VideoState}>{
    const slot=this.selected??this.slots.find(s=>s.ready),target=slot?.source;if(!slot||!target||!slot.ready)throw new Error('video session target is not prewarmed');
    if(this.promoting)throw Error('stale video session promotion');
    this.endDrag();this.promoting=true;
    try{
    if(this.transitionRunning)await this.transitionRunning;
    const own=this.generation,id=slot.id,identity=slot.identity,prepareSeq=slot.prepareSeq;
    const loop=this.loop;
    // The ready receipt can outlive its cached pixels. A committed selection
    // still owns this authorized source and may finish preparing on black.
    const promoted=await this.port.promote(id,{replaceSessionId:this.activeId,bounds,autoplay,loop,adoptPreparing:true,...(benchmark?{benchmark}:{})});
    // A fast swipe can retire the initial active before it decoded anything.
    // Retain its source, but never label that surface ready without host proof.
    const retired=typeof promoted.replacedHasRenderedFirstFrame==='boolean'?{hasRenderedFirstFrame:promoted.replacedHasRenderedFirstFrame,state:'prewarmed'}:await this.port.state(this.activeId).catch(()=>undefined);
    if(typeof promoted.replacedHasRenderedFirstFrame!=='boolean'||loop!==this.loop)await this.syncLoop(id);
    if(own!==this.generation||slot.source!==target)throw new Error('stale video session promotion');
    this.generation++;
    for(const other of this.slots)other.recentlyActive=false;
    slot.prepareSeq=this.activePrepareSeq;this.activePrepareSeq=prepareSeq;
    slot.recentlyActive=true;slot.id=this.activeId;slot.direction=direction==='up'?'prev':'next';slot.source=this.activeSource;slot.identity=this.activeIdentity;slot.ready=!!this.activeSource&&(retired?.hasRenderedFirstFrame===true||promoted.replacedHasCachedFirstFrame===true)&&retired?.state!=='error';slot.revision++;
    this.activeId=id;this.activeKey=target.key;this.activeSource=target;this.activeIdentity=identity;this.selected=undefined;
    for(const other of this.slots)if(other!==slot&&!retainKeys.includes(other.source?.key??''))this.evict(other,'stale');
    return {source:target,state:state(promoted)};
    }finally{this.promoting=false;}
  }
  private async syncLoop(id:string):Promise<VideoSessionState>{while(true){const loop=this.loop,value=await this.port.setLoop(id,loop);if(loop===this.loop)return value;}}
  private neighbourUpdate(slot:WarmSlot<Source>,work:Promise<unknown>){
    const revision=slot.revision,id=slot.id;
    // A hidden decoder cannot fail the active video. Retire only the slot
    // whose receipt failed; a reused slot belongs to a newer request.
    void work.catch(async()=>{if(slot.revision===revision&&slot.id===id)await this.evict(slot,'failure');}).catch(()=>{});
  }
  async setLoop(loop:boolean):Promise<void>{this.loop=loop;for(const slot of this.slots.filter(s=>s.ready))this.neighbourUpdate(slot,this.syncLoop(slot.id));if(this.activeKey)await this.syncLoop(this.activeId);}
  play():Promise<VideoState>{return this.port.play(this.activeId).then(state);}
  retire(handoff=false):Promise<VideoState>{this.cancelNeighbourhood();return this.port.retire(this.activeId,handoff).then(state);}
  pause(benchmark?:VideoBenchmarkAction,handoff=false):Promise<VideoState>{return this.port.pause(this.activeId,benchmark,handoff).then(state);}
  seek(positionMs:number):Promise<VideoState>{return this.port.seek(this.activeId,positionMs).then(state);}
  stop():Promise<VideoState>{return this.port.stop(this.activeId).then(state);}
  // Bounds can change before a cold neighbour decodes. Invalidate the host
  // receipt without ending the recognizer's still-active physical gesture.
  setBounds(bounds:VideoBounds):Promise<VideoState>{this.invalidateNativeDrag();return this.port.setBounds(this.activeId,bounds).then(state);}
  state():Promise<VideoState>{return this.port.state(this.activeId).then(state);}
  async setTransform(transform:VideoTransform):Promise<VideoState>{
    this.transform={...transform};
    // Include cached and decoding neighbours. Their pixels and native drag
    // offsets stay in place; changing presentation must never re-prepare media.
    const neighbours=this.slots.filter(s=>s.source&&(s.ready||s.operation));
    for(const slot of neighbours)this.neighbourUpdate(slot,this.port.setTransform(slot.id,this.transform));
    const active=await this.port.setTransform(this.activeId,this.transform);
    return state(active);
  }
  setMuted(muted:boolean):Promise<VideoState>{if(!this.port.setMuted)return Promise.reject(new Error("unsupported"));return this.port.setMuted(this.activeId,muted).then(state);}
  setVolume(volume:number):Promise<VideoState>{return this.port.setVolume(this.activeId,volume).then(state);}
  async releaseAll():Promise<void>{this.endDrag();this.cancelNeighbourhood();this.initialPreparation?.operation.cancel();this.initialPreparation=undefined;this.generation++;this.latestTransition=undefined;this.activeKey=undefined;this.activeSource=undefined;this.activeIdentity=undefined;await Promise.allSettled([this.port.release(this.activeId),...this.slots.map(s=>this.evict(s,'release'))]);}
}
