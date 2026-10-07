export interface MediaVolumeState { min:number; max:number; current:number; fixed:boolean }
export interface MediaVolumePort { state():Promise<MediaVolumeState>; setIndex(index:number):Promise<MediaVolumeState> }
export interface CrownVolumeProfile {
 sessionGapMs:number; capFraction:number; rawScale:number; rangeScale:number; hideMs:number;
}

const f = Math.fround;
const clamp = (value:number,min:number,max:number) => Math.min(max,Math.max(min,value));
export interface CrownGuardState { cap:number|null; lastEventMs:number|null; notified:boolean }
export const emptyCrownGuard = ():CrownGuardState => ({cap:null,lastEventMs:null,notified:false});
export function applyCrownVolumeGuard(current:number, delta:number, min:number, max:number, enabled:boolean, previous:CrownGuardState, eventMs:number, profile:Pick<CrownVolumeProfile,"sessionGapMs"|"capFraction">) {
  current=clamp(current,min,max);
  if (delta===0) return {target:current,state:previous,notify:false};
  const newSession=previous.lastEventMs===null || eventMs-previous.lastEventMs>profile.sessionGapMs;
  let cap=newSession || delta<=0 || !enabled ? null : previous.cap;
  let notified=cap===null ? false : previous.notified;
  let target=clamp(f(current+delta),min,max),limited=false;
  if(enabled && delta>0){
    const sessionCap=max<=min ? min : clamp(f(min+f((max-min)*f(profile.capFraction))),min+1,max);
    const activation=clamp(Math.round(sessionCap),min,max);
    cap=cap ?? (current<activation ? sessionCap : null);
    if(cap!==null){const capped=Math.min(target,cap);limited=capped<target;target=capped;}
  }
  const notify=limited&&!notified;
  return {target,state:{cap,lastEventMs:eventMs,notified:notified||notify},notify};
}
export function scaledCrownVolumeDelta(raw:number,min:number,max:number,profile:Pick<CrownVolumeProfile,"rawScale"|"rangeScale">):number {
  return f(f(-f(raw)*f(profile.rawScale))*f((max-min)*f(profile.rangeScale)));
}
export interface CrownVolumeDisplay { visible:boolean; percent:number }
export interface VolumeClock { after(ms:number,callback:()=>void):()=>void }

/** Read system state per batch, retain fractional motion, and serialize host writes.
 * Batching only coalesces the final write; direction, timestamps and guard transitions
 * are evaluated in order for every input sample.
 */
export class CrownVolumeController {
  private queue:{raw:number;eventMs:number;guard:boolean}[]=[];
  private running=false;
  private generation=0;
  private observed:MediaVolumeState|undefined;
  private virtual=0;
  private guard=emptyCrownGuard();
  private hide:(()=>void)|undefined;
  constructor(private readonly port:MediaVolumePort,private readonly clock:VolumeClock,
    private readonly changed:(state:CrownVolumeDisplay)=>void,private readonly notify:()=>void,
    private readonly failed:(error:unknown)=>void,private readonly profile:CrownVolumeProfile) {}
  adjust(raw:number,eventMs:number,guard:boolean){
    if(!Number.isFinite(raw)||raw===0||!Number.isFinite(eventMs))return;
    this.queue.push({raw,eventMs,guard});void this.pump();
  }
  reset(){
    this.generation++;this.queue=[];this.observed=undefined;this.guard=emptyCrownGuard();
    this.hide?.();this.hide=undefined;this.changed({visible:false,percent:0});
  }
  private async pump(){
    if(this.running)return;this.running=true;
    try {
      while(this.queue.length){
        const generation=this.generation;
        try {
          const actual=await this.port.state();
          if(generation!==this.generation)continue;
          if(!this.observed || this.observed.current!==actual.current || this.observed.min!==actual.min || this.observed.max!==actual.max){
            this.virtual=clamp(actual.current,actual.min,actual.max);this.guard=emptyCrownGuard();
          }
          this.observed=actual;
          const samples=this.queue.splice(0);
          for(const sample of samples){
            // Preserve each multiplication/rounding step using the caller's Float profile.
            const delta=scaledCrownVolumeDelta(sample.raw,actual.min,actual.max,this.profile);
            const next=applyCrownVolumeGuard(this.virtual,delta,actual.min,actual.max,sample.guard,this.guard,sample.eventMs,this.profile);
            this.virtual=next.target;this.guard=next.state;if(next.notify)this.notify();
          }
          const target=clamp(Math.round(this.virtual),actual.min,actual.max);
          if(!actual.fixed && target!==actual.current){
            const readback=await this.port.setIndex(target);
            if(generation!==this.generation)continue;
            this.observed=readback;
          }
          const progress=actual.max<=actual.min?0:clamp(f(f(this.virtual-actual.min)/f(actual.max-actual.min)),0,1);
          const percent=Math.round(f(progress*100));
          this.changed({visible:true,percent});this.hide?.();
          this.hide=this.clock.after(this.profile.hideMs,()=>{this.hide=undefined;this.changed({visible:false,percent});});
        }catch(error){
          if(generation===this.generation){this.queue=[];this.observed=undefined;this.guard=emptyCrownGuard();this.failed(error);}
        }
      }
    }finally{this.running=false;}
  }
}
