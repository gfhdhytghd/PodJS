/** Bounded release diagnostics for host calls. Caller-defined numeric catalogues
 * are closed at construction. No argument value, path, identity or content is
 * retained. Count requests actually dispatched, including failed requests. */
export class HostCallsiteTrace {
 private counts=new Map<number,number>();
 private bands=[0,0,0,0,0,0];
 private last=0;private receipts=0;
 private sites:Set<number>;private classes:Set<number>;
 constructor(sites:readonly number[],receiptClasses:readonly number[]){
  const valid=(n:number)=>Number.isSafeInteger(n)&&n>=0&&n<=127;
  if(!sites.every(valid)||!receiptClasses.every(valid))throw Error('invalid_callsite_catalogue');
  this.sites=new Set(sites);this.classes=new Set(receiptClasses);
 }
 observe(args:Record<string,unknown>){const {callsiteId,sizeBand}=args;if(typeof callsiteId!=='number'||!this.sites.has(callsiteId)||typeof sizeBand!=='number'||!Number.isInteger(sizeBand)||sizeBand<0||sizeBand>5)return;
  this.counts.set(callsiteId,Math.min(1000000,(this.counts.get(callsiteId)??0)+1));this.bands[sizeBand]=Math.min(1000000,this.bands[sizeBand]+1);
 }
 receipt(id:number){if(!this.classes.has(id))return;this.last=id;this.receipts=Math.min(1000000,this.receipts+1);}
 summary(){return {event:'host-callsite-summary' as const,counts:[...this.counts].sort((a,b)=>a[0]-b[0]),bands:[...this.bands],lastReceiptClass:this.last,receipts:this.receipts};}
}
/** Coarse byte sizes. Invalid or unknown sizes have their own closed bucket. */
export function hostSizeBand(size:number){if(!Number.isSafeInteger(size)||size<0)return 5;return size<=65536?0:size<=262144?1:size<=1048576?2:size<=8388608?3:4;}
