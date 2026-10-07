export interface PreviewFrame {sessionId:string;sequence:number}
export interface PreviewClock {now():number;monotonic():number;after(ms:number,callback:()=>void):()=>void}
export interface PreviewSessionOptions<T,D,S extends PreviewFrame> {
 timeoutMs:number;extendedTimeoutMs:number;publishIntervalMs:number;
 check(disposed:boolean,sessionId:string,sequence:number):void;
 normalize(value:T):T;apply(base:T,changes:D):T;isEmpty(changes:D):boolean;
 missingBaseline():Error;
 frame(sessionId:string,sequence:number,value:T,extended:boolean,updatedAt:number):S;
 isExtended(state:S):boolean;
}
/** Ephemeral publication ownership. Accepted deltas and the published frame
 * are distinct; empty updates/heartbeats can advance the accepted sequence. */
export class PreviewSession<T extends {},D,S extends PreviewFrame> {
 private published:S|null=null;
 private pending:S|null=null;
 private sessionId:string|null=null;
 private sequence=-1;
 private value:T|null=null;
 private lastPublished=0;
 private cancelTimeout?:()=>void;
 private cancelPublish?:()=>void;
 private disposed=false;
 private listeners=new Set<(state:S|null)=>void>();
 constructor(private readonly clock:PreviewClock,private readonly options:PreviewSessionOptions<T,D,S>){}
 state(){return this.published;}
 subscribe(listener:(state:S|null)=>void):()=>void{this.listeners.add(listener);return()=>{this.listeners.delete(listener);};}
 private emit(state:S|null){this.published=state;for(const listener of this.listeners)listener(state);}
 private check(sessionId:string,sequence:number){this.options.check(this.disposed,sessionId,sequence);}
 update(sessionId:string,sequence:number,value:T,extended=false):boolean{
  this.check(sessionId,sequence);if(this.sessionId===sessionId&&sequence<=this.sequence)return false;
  const normalized=this.options.normalize(value);this.sessionId=sessionId;this.sequence=sequence;this.value=normalized;
  this.publish(this.options.frame(sessionId,sequence,normalized,extended,this.clock.now()));this.timeout(sessionId,extended?this.options.extendedTimeoutMs:this.options.timeoutMs);return true;
 }
 updateDelta(sessionId:string,sequence:number,changes:D,extended=false):boolean{
  this.check(sessionId,sequence);if(this.sessionId!==sessionId||this.value===null)throw this.options.missingBaseline();if(sequence<=this.sequence)return false;
  this.sequence=sequence;
  if(this.options.isEmpty(changes)){this.timeout(sessionId,this.options.timeoutMs);return true;}
  // Accepted sequences remain consumed if the caller's delta conversion fails.
  const value=this.options.apply(this.value,changes);this.value=value;
  const next=this.options.frame(sessionId,sequence,value,extended,this.clock.now()),remaining=this.options.publishIntervalMs-(this.clock.monotonic()-this.lastPublished);
  if(this.options.publishIntervalMs<=0||!this.published||remaining<=0)this.publish(next);
  else{this.pending=next;if(!this.cancelPublish)this.cancelPublish=this.clock.after(remaining,()=>{this.cancelPublish=undefined;const pending=this.pending;this.pending=null;if(pending&&pending.sessionId===this.sessionId)this.publish(pending);});}
  this.timeout(sessionId,extended?this.options.extendedTimeoutMs:this.options.timeoutMs);return true;
 }
 heartbeat(sessionId:string,sequence:number):number|null{if(this.sessionId!==sessionId)return null;if(sequence>this.sequence)this.sequence=sequence;this.timeout(sessionId,this.options.timeoutMs);return this.sequence;}
 refreshTimeout():boolean{const current=this.published;if(!current||!this.options.isExtended(current))return false;this.timeout(current.sessionId,this.options.extendedTimeoutMs);return true;}
 stop(sessionId:string):boolean{if(this.sessionId!==sessionId)return false;this.cancelTimeout?.();this.cancelTimeout=undefined;this.cancelPublish?.();this.cancelPublish=undefined;this.pending=null;this.sessionId=null;this.sequence=-1;this.value=null;this.emit(null);return true;}
 dispose(){if(this.sessionId!==null)this.stop(this.sessionId);this.disposed=true;this.listeners.clear();}
 private publish(state:S){this.cancelPublish?.();this.cancelPublish=undefined;this.pending=null;this.emit(state);this.lastPublished=this.clock.monotonic();}
 private timeout(sessionId:string,ms:number){this.cancelTimeout?.();this.cancelTimeout=this.clock.after(ms,()=>{this.cancelTimeout=undefined;this.stop(sessionId);});}
}
