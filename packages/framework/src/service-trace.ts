// Enabled only by an explicitly opted-in diagnostic host. No clock, allocations
// or effects on the default path. Spans measure synchronous CPU work, not awaits.
let enabled=false;
const stack:{child:number}[]=[];
/** @internal The host event pump owns this flag; it grants no host permission. */
export function __setServiceTraceEnabled(value:boolean):void {enabled=value;}
export function traceServiceWork<T>(label:string,work:()=>T,units?:number):T {
 if(!enabled)return work();
 const began=Date.now(),frame={child:0};stack.push(frame);
 try{return work();}finally{
  const elapsedMs=Date.now()-began;stack.pop();
  if(stack.length)stack[stack.length-1]!.child+=elapsedMs;
  if(/^[a-zA-Z][a-zA-Z0-9_.:-]{0,79}$/.test(label))try {
   (globalThis as {pod?:{emit:(line:string)=>void}}).pod?.emit(JSON.stringify({t:'diagnostic.serviceWork',label,elapsedMs,exclusiveMs:elapsedMs-frame.child,...(Number.isSafeInteger(units)&&units!>=0?{units}: {})}));
  }catch { /* Diagnostics cannot change the result or replace a product error. */ }
 }
}
