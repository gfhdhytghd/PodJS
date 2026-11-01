export {RepeatedWorkGuard,REPEATED_WORK_VERSION} from './repeated-work';
/** Cooperative admission for non-preemptible host units. The caller keeps its
 * lease until a started unit settles; no write is raced against a timer.
 * Estimates are conservative admission bounds, not real-time OS guarantees. */
export type WorkStage='secure-read'|'secure-write'|'decrypt'|'encrypt'|'file-read'|'file-write'|'file-commit'|'transform'|'network'|'host';
export interface WorkWindowMetric {event:'window-unit';stage:WorkStage;outcome:'started'|'complete'|'failed'|'deferred';durationMillis:number;remainingMillis:number;}
export class WorkWindow {
 private closed=false;
 private maxima=new Map<WorkStage,number>();
 constructor(private options:{deadlineMs:number;reserveMillis:number;now?:()=>number;expired:()=>Error;report?:(metric:WorkWindowMetric)=>void}){}
 private now(){return (this.options.now??Date.now)();}
 remainingMillis(){return this.closed?0:Math.max(0,this.options.deadlineMs-this.options.reserveMillis-this.now());}
 /** Finalizers may delete their temporary files during the shutdown reserve.
  * Each cleanup request is still admitted before the hard deadline. */
 async cleanup<T>(action:()=>Promise<T>):Promise<T>{
  if(this.options.deadlineMs-this.now()<=2000)throw this.options.expired();
  return action();
 }
 async run<T>(stage:WorkStage,estimateMillis:number,action:()=>Promise<T>):Promise<T>{
  const at=this.now(),remainingMillis=this.remainingMillis(),needed=Math.max(estimateMillis,(this.maxima.get(stage)??0)*2);
  const report=(outcome:WorkWindowMetric['outcome'])=>{try{this.options.report?.({event:'window-unit',stage,outcome,durationMillis:Math.max(0,this.now()-at),remainingMillis:this.remainingMillis()});}catch{}};
  if(remainingMillis<=needed){this.closed=true;report('deferred');throw this.options.expired();}
  report('started');try{const value=await action();report('complete');return value;}catch(error){report('failed');throw error;}
  finally{this.maxima.set(stage,Math.max(this.maxima.get(stage)??0,this.now()-at));}
 }
}
/** Standard Pod host phases. Unknown methods get the same admission gate.
 * Track temporary sizes so large native AEAD/transcode calls are admitted as
 * single units before dispatch; file transfer loops remain checked per piece. */
export function windowedHost<T extends <R>(method:string,args:Record<string,unknown>)=>Promise<R>>(host:T,window:WorkWindow):T {
 const sizes=new Map<string,number>();
 return (async<R>(method:string,args:Record<string,unknown>):Promise<R>=>{
  // Releasing a held lease and one bounded diagnostic flush must unwind even
  // after admission closes. They cannot start application work.
  if(['cloud.release','secure.release','log.append'].includes(method))return host<R>(method,args);
  if(method==='file.delete'||method==='crypto.closeSealedGzip'){try{return await window.cleanup(()=>host<R>(method,args));}finally{sizes.delete(String(args.path));}}
  const stage:WorkStage=method==='http.download'?'network':method==='secure.get'?'secure-read':method.startsWith('secure.')?'secure-write':(/aesgcmdecrypt/i.test(method)||method==='crypto.openSealedParts')?'decrypt':/aesgcmencrypt/i.test(method)?'encrypt':method==='file.read'?'file-read':method==='file.write'?'file-write':method==='file.commit'?'file-commit':/gzip|gunzip/.test(method)?'transform':'host';
  const input=typeof args.inputPath==='string'?args.inputPath:typeof args.path==='string'?args.path:'';
  const bytes=method==='crypto.openSealedParts'&&typeof args.length==='number'?args.length:sizes.get(input)??0;
  const estimate=method==='crypto.gunzipSealedParts'?2000+Math.ceil(Number(args.replayBytes??0)/1048576)*1000:method==='crypto.openSealedParts'?2000+Math.ceil(bytes/1048576)*1000:['decrypt','encrypt','transform'].includes(stage)?2000+Math.ceil(bytes/131072)*1000:1000;
  // A transport unit already has a native total-call timeout. Shorten it to
  // the current window rather than starting a 120s request at the boundary.
  let callArgs=method==='crypto.openSealedParts'||method==='crypto.gunzipSealedParts'?{...args,deadlineMs:Date.now()+window.remainingMillis()}:args;
  if(method==='http.download'){
   const limit=Math.max(1,Math.floor(window.remainingMillis()-1000));
   const bound=(value:unknown,fallback:number)=>Math.max(1,Math.min(typeof value==='number'&&Number.isFinite(value)?value:fallback,limit));
   callArgs={...args,callTimeoutMs:bound(args.callTimeoutMs,120000),timeoutMs:bound(args.timeoutMs,15000),readTimeoutMs:bound(args.readTimeoutMs,30000)};
  }
  const value=await window.run(stage,estimate,()=>host<R>(method,callArgs));
  if(value&&typeof value==='object'){
   const v=value as {path?:unknown;size?:unknown};const path=typeof v.path==='string'?v.path:input;
   if(path&&typeof v.size==='number'&&Number.isSafeInteger(v.size))sizes.set(path,v.size);
  }
  if(method==='file.commit'){sizes.delete(input);if(typeof args.target==='string'&&typeof args.expectedSize==='number')sizes.set(args.target,args.expectedSize);}
  return value;
 }) as T;
}
