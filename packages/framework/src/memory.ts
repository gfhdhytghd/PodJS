
export function processMemoryBudgetBytes():number {
 const host=(globalThis as {pod?:{memoryBudgetBytes?:()=>number}}).pod;
 const value=host?.memoryBudgetBytes?.();
 return typeof value==='number'&&Number.isSafeInteger(value)&&value>0?value:0;
}
type Reclaimer={order:number;run:(level:number)=>void};
const reclaimers=new Set<Reclaimer>();let level=0;
export function memoryPressureLevel(){return level;}
/** Called by the existing serialized host pump, not an additional event loop. */
export function dispatchMemoryPressure(event:Record<string,unknown>):void {
 if(event.t!=='memory.pressure'||!Number.isInteger(event.level)||(event.level as number)<0||(event.level as number)>3)return;
 level=event.level as number;
 for(const reclaimer of [...reclaimers].sort((a,b)=>a.order-b.order)){try{reclaimer.run(level);}catch{console.warn('Memory trim callback failed');}}
}
/** Numeric notification only. No guest can reset authentication or a codec gate. */
export function onMemoryTrim(order:number,run:(level:number)=>void):()=>void {
 if(!processMemoryBudgetBytes())return ()=>{};
 const entry={order,run};reclaimers.add(entry);
 return ()=>{reclaimers.delete(entry);if(!reclaimers.size)level=0;};
}
