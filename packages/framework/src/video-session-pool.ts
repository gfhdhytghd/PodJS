import {benchmark,type VideoBenchmarkAction} from './benchmark.ts';
import {videoSessions,hostOperation,type HostOperation,type VideoBounds,type VideoFrameCapture,type VideoFrame,type VideoPrepare,type VideoSessionCapabilities,type VideoSessionState,type VideoSessionTransitionState,type VideoState} from './services.ts';
import type {VideoPagerDirection} from './video-paging.ts';
type VideoDecoderProfile=Pick<VideoPrepare,'videoMime'|'videoWidth'|'videoHeight'>;

export interface VideoSessionPort {
  captureFrame?(sessionId:string,options:VideoFrameCapture):Promise<VideoFrame>;
  capabilities(profile?:VideoDecoderProfile):Promise<VideoSessionCapabilities>;
  prepare(options:Parameters<typeof videoSessions.prepare>[0]):HostOperation<VideoSessionState>;
  play(sessionId:string):Promise<VideoSessionState>;
  retire(sessionId:string):Promise<VideoSessionState>;
  pause(sessionId:string):Promise<VideoSessionState>;
  seek(sessionId:string,positionMs:number):Promise<VideoSessionState>;
  stop(sessionId:string):Promise<VideoSessionState>;
  release(sessionId:string):Promise<{sessionId:string;released:boolean}>;
  setBounds(sessionId:string,bounds:VideoBounds):Promise<VideoSessionState>;
  state(sessionId:string):Promise<VideoSessionState>;
  setTransform(sessionId:string,transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}):Promise<VideoSessionState>;
  setVolume(sessionId:string,volume:number):Promise<VideoSessionState>;
  setLoop(sessionId:string,loop:boolean):Promise<VideoSessionState>;
  promote(sessionId:string,options:{replaceSessionId?:string;bounds?:VideoBounds;autoplay?:boolean;benchmark?:VideoBenchmarkAction}):Promise<VideoSessionState>;
  transition(options:{fromSessionId:string;toSessionId:string;progress:number;direction:VideoPagerDirection;bounds?:VideoBounds;benchmark?:VideoBenchmarkAction}):Promise<VideoSessionTransitionState>;
}

const defaultSessionPort:VideoSessionPort={
  ...videoSessions,
  capabilities:(profile={})=>hostOperation<VideoSessionCapabilities>("video.session.capabilities",profile).result,
};

let nextPoolId=1;
const state=(value:VideoSessionState):VideoState=>({
  bufferedMs:value.bufferedMs,networkStalled:value.networkStalled,presented:value.presented,metrics:value.metrics,ok:value.ok,state:value.state,positionMs:value.positionMs,durationMs:value.durationMs,completedLoops:value.completedLoops,loop:value.loop,
  width:value.width,height:value.height,volume:value.volume,volumeMax:15,
  sourceGeneration:value.sourceGeneration,errorCode:value.errorCode,hasRenderedFirstFrame:value.hasRenderedFirstFrame,
});

/** Retained surfaces, bounded by native codec and byte budgets. Three slots
 * retain both neighbours; two-slot hosts retain the selected/likely neighbour.
 * Slot-local revisions prevent one neighbour cancelling the other. */
interface WarmSlot<S> {direction?:'next'|'prev';id:string;source?:S;ready:boolean;revision:number;operation?:HostOperation<VideoSessionState>;pending?:Promise<VideoState>}
export class VideoSessionPool<Source extends {key:string}> {
  private loop=false;
  private activeId:string;
  private activeKey:string|undefined;
  private activeSource:Source|undefined;
  private slots:WarmSlot<Source>[];
  private capacity=2;
  private selected:WarmSlot<Source>|undefined;
  private generation=0;
  private transitionRunning:Promise<void>|undefined;
  private latestTransition:{slot:WarmSlot<Source>;progress:number;direction:VideoPagerDirection;bounds:VideoBounds;benchmark?:VideoBenchmarkAction}|undefined;
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
    const supported=this.capacity>=2&&c.surfacePromotion&&c.verticalTransition;
    benchmark.begin('prewarm_policy',{reason:supported?(this.capacity>=3?'bidirectional':'single_neighbour'):'capacity',slots:this.capacity});
    return supported;
  }
  get neighbourCapacity(){return this.capacity-1;}
  private evict(slot:WarmSlot<Source>,reason:'stale'|'capacity'|'release'|'failure'|'timeout'){
    if(slot.source)benchmark.begin('prewarm_evicted',{reason,direction:slot.direction,generation:slot.revision});
    slot.revision++;slot.operation?.cancel();slot.operation=undefined;slot.pending=undefined;slot.source=undefined;slot.ready=false;
    if(this.selected===slot)this.selected=undefined;
    return this.port.release(slot.id).catch(()=>undefined);
  }
  retainNeighbours(keys:readonly string[]){for(const slot of this.slots)if(slot.source&&!keys.includes(slot.source.key))this.evict(slot,'stale');}
  prepare(source:Source,options:VideoPrepare):HostOperation<VideoState>{
    const own=++this.generation;this.loop=options.loop===true;this.activeKey=source.key;this.activeSource=source;
    for(const slot of this.slots.slice(0,this.capacity-1))void this.evict(slot,'stale');
    // Explicit sessions are foreground-only. Do not leak a legacy preference
    // into the native API (which correctly rejects backgroundPlayback=true).
    const {backgroundPlayback,...foreground}=options;
    const operation=this.port.prepare({...foreground,sessionId:this.activeId,prewarm:false,autoplay:false});
    return {cancel:()=>operation.cancel(),result:operation.result.then(value=>{
      if(own!==this.generation)throw new Error('stale video session preparation');
      return this.syncLoop(this.activeId).then(state);
    })};
  }
  async prewarm(source:Source,options:VideoPrepare,direction?:'next'|'prev'):Promise<VideoState>{
    if(source.key===this.activeKey)return this.state();
    let slot=this.slots.find(s=>s.source?.key===source.key);
    if(slot?.ready)return this.port.state(slot.id).then(state);
    if(slot?.pending)return slot.pending;
    slot??=this.slots.slice(0,this.capacity-1).find(s=>!s.source);
    if(!slot){slot=this.slots.find(s=>s!==this.selected)??this.slots[0];this.evict(slot,'capacity');}
    const target=slot,own=++target.revision,epoch=this.generation;
    target.source=source;target.ready=false;target.direction=direction;
    const seq=benchmark.begin('prewarm_requested',{generation:own,direction});
    const {backgroundPlayback,...foreground}=options;
    const operation=this.port.prepare({...foreground,loop:this.loop,sessionId:target.id,prewarm:true,autoplay:false,muted:false});
    target.operation=operation;
    const valid=()=>epoch===this.generation&&own===target.revision&&target.source?.key===source.key;
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
  selectStandby(key:string):boolean{const slot=this.slots.find(s=>s.ready&&s.source?.key===key);if(!slot)return false;this.selected=slot;return true;}
  standbySource():Source|undefined{return (this.selected??this.slots.find(s=>s.ready))?.source;}
  async transition(progress:number,direction:VideoPagerDirection,bounds:VideoBounds,benchmark?:VideoBenchmarkAction):Promise<void>{
    const slot=this.selected??this.slots.find(s=>s.ready);if(!slot?.ready||!slot.source)throw new Error('video session target is not prewarmed');
    this.selected=slot;this.latestTransition={slot,progress:Math.max(0,Math.min(1,progress)),direction,bounds,...(benchmark?{benchmark}:{})};
    if(this.transitionRunning)return this.transitionRunning;
    this.transitionRunning=(async()=>{while(this.latestTransition){const {slot,...next}=this.latestTransition;this.latestTransition=undefined;await this.port.transition({fromSessionId:this.activeId,toSessionId:slot.id,...next});}})();
    try{await this.transitionRunning;}finally{this.transitionRunning=undefined;}
  }
  async promote(direction:VideoPagerDirection,bounds:VideoBounds,autoplay=true,benchmark?:VideoBenchmarkAction):Promise<{source:Source;state:VideoState}>{
    const slot=this.selected??this.slots.find(s=>s.ready),target=slot?.source;if(!slot||!target||!slot.ready)throw new Error('video session target is not prewarmed');
    if(this.transitionRunning)await this.transitionRunning;
    const own=this.generation,id=slot.id;
    const promoted=await this.port.promote(id,{replaceSessionId:this.activeId,bounds,autoplay,...(benchmark?{benchmark}:{})});
    // A fast swipe can retire the initial active before it decoded anything.
    // Retain its source, but never label that surface ready without host proof.
    const retired=await this.port.state(this.activeId).catch(()=>undefined);
    if(own!==this.generation||slot.source!==target)throw new Error('stale video session promotion');
    this.generation++;
    slot.id=this.activeId;slot.direction=direction==='up'?'prev':'next';slot.source=this.activeSource;slot.ready=!!this.activeSource&&retired?.hasRenderedFirstFrame===true&&retired.state!=='error';slot.revision++;
    this.activeId=id;this.activeKey=target.key;this.activeSource=target;this.selected=undefined;
    for(const other of this.slots)if(other!==slot)this.evict(other,'stale');
    return {source:target,state:state(promoted)};
  }
  private async syncLoop(id:string):Promise<VideoSessionState>{while(true){const loop=this.loop,value=await this.port.setLoop(id,loop);if(loop===this.loop)return value;}}
  async setLoop(loop:boolean):Promise<void>{this.loop=loop;await Promise.all([...(this.activeKey?[this.syncLoop(this.activeId)]:[]),...this.slots.filter(s=>s.ready).map(s=>this.syncLoop(s.id))]);}
  play():Promise<VideoState>{return this.port.play(this.activeId).then(state);}
  retire():Promise<VideoState>{return this.port.retire(this.activeId).then(state);}
  pause():Promise<VideoState>{return this.port.pause(this.activeId).then(state);}
  seek(positionMs:number):Promise<VideoState>{return this.port.seek(this.activeId,positionMs).then(state);}
  stop():Promise<VideoState>{return this.port.stop(this.activeId).then(state);}
  setBounds(bounds:VideoBounds):Promise<VideoState>{return this.port.setBounds(this.activeId,bounds).then(state);}
  state():Promise<VideoState>{return this.port.state(this.activeId).then(state);}
  setTransform(transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}):Promise<VideoState>{return this.port.setTransform(this.activeId,transform).then(state);}
  setVolume(volume:number):Promise<VideoState>{return this.port.setVolume(this.activeId,volume).then(state);}
  async releaseAll():Promise<void>{this.generation++;this.latestTransition=undefined;this.activeKey=undefined;this.activeSource=undefined;await Promise.allSettled([this.port.release(this.activeId),...this.slots.slice(0,this.capacity-1).map(s=>this.evict(s,'release'))]);}
}
