// Enabled only by an explicitly opted-in diagnostic host. No clock, allocations
// or effects on the default path. Spans measure synchronous CPU work, not awaits.
let enabled=false;
let callbackEnabled=false,callbackLogs=0;
export function __setServiceCallbackTraceEnabled(value:boolean):void {callbackEnabled=value;if(!value)callbackLogs=0;}
/** Synchronous service callback only. Promise continuations run later and are
 * attributed by the interrupt stack sampler, not charged to resolve/reject. */
export function traceServiceCallback<T>(service:string,callback:'resolve'|'reject'|'status',work:()=>T):T {
 if(!callbackEnabled)return work();
 let begin:number;try{begin=Date.now();}catch{return work();}
 try{return work();}finally{
  try{
  const elapsedMs=Date.now()-begin;
  if(elapsedMs>50&&callbackLogs<64){
   callbackLogs++;
   try{(globalThis as {pod?:{emit:(line:string)=>void}}).pod?.emit(JSON.stringify({t:'diagnostic.serviceCallback',service,callback,elapsedMs}));}
   catch{/* Preserve callback results and errors even if diagnostics fail. */}
  }
  }catch{/* A replaced clock cannot prevent settlement or replace its error. */}
 }
}
const stack:{child:number}[]=[];
/** @internal The host event pump owns this flag; it grants no host permission. */
export function __setServiceTraceEnabled(value:boolean):void {enabled=value;}
export function traceServiceWork<T>(label:string,work:()=>T,units?:number):T {
 if(!enabled)return work();
 const began=Date.now(),frame={child:0};stack.push(frame);
 try{return work();}finally{
  const ended=Date.now(),elapsedMs=ended-began;stack.pop();
  if(stack.length)stack[stack.length-1]!.child+=elapsedMs;
  if(/^[a-zA-Z][a-zA-Z0-9_.:-]{0,79}$/.test(label))try {
   (globalThis as {pod?:{emit:(line:string)=>void}}).pod?.emit(JSON.stringify({t:'diagnostic.serviceWork',label,elapsedMs,exclusiveMs:elapsedMs-frame.child,beginEpochMs:began,endEpochMs:ended,...(Number.isSafeInteger(units)&&units!>=0?{units}: {})}));
  }catch { /* Diagnostics cannot change the result or replace a product error. */ }
 }
}

/** Optional code-authored numeric attribution. The default path is one null check. */
export interface ServiceDiagnostics {request(method:string,args:Record<string,unknown>):number;completed(method:string,site:number,ok:boolean):void}
let serviceDiagnostics:ServiceDiagnostics|undefined;
export function setServiceDiagnostics(value:ServiceDiagnostics|undefined){serviceDiagnostics=value;}
export function serviceDiagnosticSite(method:string,args:Record<string,unknown>):number{try{const site=serviceDiagnostics?.request(method,args)??0;return Number.isInteger(site)&&site>=0&&site<64?site:0;}catch{return 0;}}
export function serviceDiagnosticComplete(method:string,site:number,ok:boolean):void{try{serviceDiagnostics?.completed(method,site,ok);}catch{/* Diagnostics never change settlement. */}}
