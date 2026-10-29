/** Caller-scoped disposable receipts for deterministic work. Persistence and
 * authentication belong to the caller. A receipt is never a remote ACK.
 * Fingerprints must cover every input, including the algorithm version/scope. */
export class WorkReceipts<T> {
 private entries:Map<string,T>;
 constructor(saved:readonly (readonly [string,T])[]=[],private limit=10000){
  if(!Number.isSafeInteger(limit)||limit<1)throw Error('invalid_work_receipt_limit');
  this.entries=new Map(saved.slice(-limit));
 }
 get(key:string){return this.entries.get(key);}
 set(key:string,value:T){this.entries.delete(key);this.entries.set(key,value);while(this.entries.size>this.limit)this.entries.delete(this.entries.keys().next().value!);}
 snapshot(){return [...this.entries];}
}
