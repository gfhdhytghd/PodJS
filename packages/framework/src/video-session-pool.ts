import type {VideoBenchmarkAction} from './benchmark.ts';
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
  presented:value.presented,metrics:value.metrics,ok:value.ok,state:value.state,positionMs:value.positionMs,durationMs:value.durationMs,completedLoops:value.completedLoops,loop:value.loop,
  width:value.width,height:value.height,volume:value.volume,volumeMax:15,
  sourceGeneration:value.sourceGeneration,errorCode:value.errorCode,hasRenderedFirstFrame:value.hasRenderedFirstFrame,
});

/** Two fixed host slots. Promotion swaps their roles, then the retired slot is
 * reused for the next forward prewarm, matching the native two-player plan. */
export class VideoSessionPool<Source extends {key:string}> {
  private loop=false;
  private activeId:string;
  private standbyId:string;
  private activeKey:string|undefined;
  private standby:Source|undefined;
  private standbyReady=false;
  private standbyPrepare:HostOperation<VideoSessionState>|undefined;
  private generation=0;
  private transitionRunning:Promise<void>|undefined;
  private latestTransition:{progress:number;direction:VideoPagerDirection;bounds:VideoBounds;benchmark?:VideoBenchmarkAction}|undefined;

  constructor(private readonly port:VideoSessionPort=defaultSessionPort){
    const id=nextPoolId++;
    this.activeId=`vp${id}a`;this.standbyId=`vp${id}b`;
  }

  /** On-demand host observations, independent of renderer diagnostics and playback scheduling. */
  async diagnostics(){
    const generation=this.generation,activeId=this.activeId,standbyId=this.standbyId;
    const slots=[{role:'active',sessionId:activeId,key:this.activeKey,bound:!!this.activeKey},
      {role:'standby',sessionId:standbyId,key:this.standby?.key,bound:!!this.standby}];
    const [capabilities,...states]=await Promise.allSettled([this.port.capabilities(),...slots.map(slot=>slot.bound?this.port.state(slot.sessionId):Promise.resolve(undefined))]);
    if(generation!==this.generation||activeId!==this.activeId||standbyId!==this.standbyId)return undefined;
    return {capabilities:capabilities.status==='fulfilled'?capabilities.value as VideoSessionCapabilities:undefined,
      generation,standbyReady:this.standbyReady,standbyPreparing:!!this.standbyPrepare,
      slots:slots.map((slot,i)=>({...slot,
        state:states[i]?.status==='fulfilled'?states[i].value as VideoSessionState|undefined:undefined})),
    };
  }

  async supportsPrewarm(profile:VideoDecoderProfile={}):Promise<boolean>{
    const capability=await this.port.capabilities(profile);
    return capability.decoderLimit>=2&&capability.surfacePromotion&&capability.verticalTransition;
  }

  prepare(source:Source,options:VideoPrepare):HostOperation<VideoState>{
    const own=++this.generation;
    this.loop=options.loop===true;
    this.activeKey=source.key;this.standby=undefined;this.standbyReady=false;
    this.standbyPrepare?.cancel();this.standbyPrepare=undefined;
    void this.port.release(this.standbyId).catch(()=>{});
    const operation=this.port.prepare({...options,sessionId:this.activeId,prewarm:false,autoplay:false});
    return {cancel:()=>operation.cancel(),result:operation.result.then(value=>{
      if(own!==this.generation)throw new Error("stale video session preparation");
      return this.syncLoop(this.activeId).then(state);
    })};
  }

  async prewarm(source:Source,options:VideoPrepare):Promise<VideoState>{
    if(source.key===this.activeKey)return this.state();
    if(this.standby?.key===source.key&&this.standbyReady)return this.port.state(this.standbyId).then(state);
    const own=++this.generation;
    this.standbyPrepare?.cancel();this.standby=source;this.standbyReady=false;
    // The host silences every prewarm internally while retaining this audible
    // volume for promotion. Passing muted=true would make the promoted slot
    // remain silent.
    const operation=this.port.prepare({...options,loop:this.loop,sessionId:this.standbyId,prewarm:true,autoplay:false,muted:false});
    this.standbyPrepare=operation;
    try{
      const value=await operation.result;
      if(own!==this.generation||this.standby?.key!==source.key)throw new Error("stale video session prewarm");
      while(true){
        const loop=this.loop;
        await this.port.setLoop(this.standbyId,loop);
        if(own!==this.generation||this.standby?.key!==source.key)throw new Error("stale video session prewarm");
        // Check and publish readiness in the same turn: a toggle between a
        // nested async helper's return and this continuation must not be lost.
        if(loop===this.loop){this.standbyReady=value.hasRenderedFirstFrame;return state(value);}
      }
    }finally{if(this.standbyPrepare===operation)this.standbyPrepare=undefined;}
  }

  ownsActiveSession(id:string){return id===this.activeId;}
  captureFrame(options:VideoFrameCapture){if(!this.port.captureFrame)return Promise.reject(Error('frame_capture_unavailable'));return this.port.captureFrame(this.activeId,options);}
  invalidateStandby(key:string){if(this.standby?.key!==key)return;this.generation++;this.standbyPrepare?.cancel();this.standbyPrepare=undefined;this.standby=undefined;this.standbyReady=false;void this.port.release(this.standbyId).catch(()=>{});}
  hasStandby(key?:string):boolean{return this.standbyReady&&!!key&&this.standby?.key===key;}
  standbySource():Source|undefined{return this.standbyReady?this.standby:undefined;}

  async transition(progress:number,direction:VideoPagerDirection,bounds:VideoBounds,benchmark?:VideoBenchmarkAction):Promise<void>{
    if(!this.standbyReady||!this.standby)throw new Error("video session target is not prewarmed");
    this.latestTransition={progress:Math.max(0,Math.min(1,progress)),direction,bounds,...(benchmark?{benchmark}:{})};
    if(this.transitionRunning)return this.transitionRunning;
    this.transitionRunning=(async()=>{
      while(this.latestTransition){
        const next=this.latestTransition;this.latestTransition=undefined;
        await this.port.transition({fromSessionId:this.activeId,toSessionId:this.standbyId,...next});
      }
    })();
    try{await this.transitionRunning;}finally{this.transitionRunning=undefined;}
  }

  async promote(direction:VideoPagerDirection,bounds:VideoBounds,autoplay=true,benchmark?:VideoBenchmarkAction):Promise<{source:Source;state:VideoState}>{
    const target=this.standby;if(!target||!this.standbyReady)throw new Error("video session target is not prewarmed");
    if(this.transitionRunning)await this.transitionRunning;
    const promoted=await this.port.promote(this.standbyId,{replaceSessionId:this.activeId,bounds,autoplay,...(benchmark?{benchmark}:{})});
    [this.activeId,this.standbyId]=[this.standbyId,this.activeId];
    this.activeKey=target.key;this.standby=undefined;this.standbyReady=false;this.generation++;
    return {source:target,state:state(promoted)};
  }

  private async syncLoop(id:string):Promise<VideoSessionState>{
    while(true){const loop=this.loop,value=await this.port.setLoop(id,loop);if(loop===this.loop)return value;}
  }

  async setLoop(loop:boolean):Promise<void>{
    this.loop=loop;
    await Promise.all([...(this.activeKey?[this.syncLoop(this.activeId)]:[]),...(this.standbyReady?[this.syncLoop(this.standbyId)]:[])]);
  }

  play():Promise<VideoState>{return this.port.play(this.activeId).then(state);}
  retire():Promise<VideoState>{return this.port.retire(this.activeId).then(state);}
  pause():Promise<VideoState>{return this.port.pause(this.activeId).then(state);}
  seek(positionMs:number):Promise<VideoState>{return this.port.seek(this.activeId,positionMs).then(state);}
  stop():Promise<VideoState>{return this.port.stop(this.activeId).then(state);}
  setBounds(bounds:VideoBounds):Promise<VideoState>{return this.port.setBounds(this.activeId,bounds).then(state);}
  state():Promise<VideoState>{return this.port.state(this.activeId).then(state);}
  setTransform(transform:{rotationDeg?:number;scaleMode?:"standard"|"expanded"|"shrunk";panX?:number;panY?:number}):Promise<VideoState>{return this.port.setTransform(this.activeId,transform).then(state);}
  setVolume(volume:number):Promise<VideoState>{return this.port.setVolume(this.activeId,volume).then(state);}

  async releaseAll():Promise<void>{
    this.generation++;this.latestTransition=undefined;this.standbyPrepare?.cancel();this.standbyPrepare=undefined;
    this.activeKey=undefined;this.standby=undefined;this.standbyReady=false;
    await Promise.allSettled([this.port.release(this.activeId),this.port.release(this.standbyId)]);
  }
}
