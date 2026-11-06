/** Bounded content work. No account lock, timers, implicit wake or network. */
export type Confirmed = boolean | null;
export interface ContentSignals {
 charging:Confirmed; wifi:Confirmed; bluetooth:Confirmed; internet:Confirmed;
 cellular:Confirmed; localRoute:Confirmed; allowChargingCellular?:boolean;
}
export function contentEligibility(s:ContentSignals) {
 const largeEligible=s.charging===true||s.wifi===true||s.bluetooth===true;
 const chargingOnly=s.charging===true&&s.wifi!==true&&s.bluetooth!==true;
 return {largeEligible,cloudAllowed:largeEligible&&s.internet===true&&
  !(chargingOnly&&s.allowChargingCellular===false&&s.cellular!==false),
  localAllowed:largeEligible&&s.localRoute===true};
}
/** Only the application's authenticated session owner may supply a live lease.
 * Radio power, pairing and system ACL observations are deliberately absent. */
export class AuthenticatedContentSessions {
 private sessions=new Map<object,{alive:()=>boolean;bluetooth:boolean}>();
 attach(owner:object,session:{alive:()=>boolean;bluetooth:boolean}) {
  this.sessions.set(owner,session);return ()=>this.sessions.delete(owner);
 }
 snapshot(){let bluetooth=false,localRoute=false;for(const [owner,s] of this.sessions){if(!s.alive()){this.sessions.delete(owner);continue;}localRoute=true;bluetooth||=s.bluetooth;}return {bluetooth,localRoute};}
}
export interface Fairness {demandBlobs:number;ordinaryRank:number;}
export interface ContentTask {id:string;rank:number;}
/** Persist this state with the completed blob, never at frame/connection start. */
export function selectContentTask<T extends ContentTask>(tasks:readonly T[],state:Fairness):T|undefined {
 const demand=tasks.find(t=>t.rank===0),ordinary=tasks.filter(t=>t.rank>0);
 if(!demand)return ordinary[0];
 if(state.demandBlobs<8||!ordinary.length)return demand;
 for(let n=0;n<4;n++){const rank=(state.ordinaryRank-1+n)%4+1,task=ordinary.find(t=>t.rank===rank);if(task)return task;}
}
export function completedContentTask(state:Fairness,rank:number,isBlob=true):Fairness {
 if(!isBlob)return {...state};
 return rank===0?{...state,demandBlobs:Math.min(8,state.demandBlobs+1)}:
  {demandBlobs:0,ordinaryRank:rank%4+1};
}
export type ContentWindowResult={status:'complete'|'paused'|'waiting'|'busy';bytes:number;objects:number;frames:number;};
export class ContentWindow {
 private started:number;private consumed=0;
 constructor(private now:()=>number,readonly budgetMillis=300000,readonly reserveMillis=10000,readonly maxBytes=8*1048576){
  if(budgetMillis<=reserveMillis||budgetMillis>300000||reserveMillis<10000||maxBytes<1||maxBytes>8*1048576)throw Error('invalid_content_budget');this.started=now();
 }
 available(bytes=0){return this.now()-this.started<this.budgetMillis-this.reserveMillis&&this.consumed+bytes<=this.maxBytes;}
 commit(bytes:number){if(!Number.isSafeInteger(bytes)||bytes<0)throw Error('invalid_content_progress');this.consumed+=bytes;}
}
/** One successful step persists at most one 64KiB frame or one bounded phase.
 * Busy ends this window. A caller/OS must explicitly start the next window. */
export class ContentWorker {
 private running=false;
 async run(options:{window:ContentWindow;signals:()=>Promise<ContentSignals>;yieldControl:()=>Promise<void>;
  step:()=>Promise<{done?:boolean;bytes:number;objects:number;frames:number;progressed?:boolean}>}):Promise<ContentWindowResult>{
  if(this.running)return {status:'busy',bytes:0,objects:0,frames:0};this.running=true;
  const result:ContentWindowResult={status:'paused',bytes:0,objects:0,frames:0};
  try{while(options.window.available(65536)){
   await options.yieldControl();if(!contentEligibility(await options.signals()).cloudAllowed){result.status='waiting';break;}
   let step;try{step=await options.step();}catch(e){if(['cloud_account_busy','library_busy'].includes((e as Error).message)){result.status='busy';break;}throw e;}
   for(const n of [step.bytes,step.objects,step.frames])if(!Number.isSafeInteger(n)||n<0)throw Error('invalid_content_progress');
   if(step.bytes>65536||step.frames>1)throw Error('content_unit_too_large');
   result.bytes+=step.bytes;result.objects+=step.objects;result.frames+=step.frames;options.window.commit(step.bytes);
   if(step.done){result.status='complete';break;}
   if(!step.bytes&&!step.objects&&!step.frames&&!step.progressed)throw Error('content_no_progress');
  }return result;}finally{this.running=false;}
 }
}
