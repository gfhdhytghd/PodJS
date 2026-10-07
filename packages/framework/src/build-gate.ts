/** Resolves an asynchronous host capability once. It starts closed and shares
 * initialization (including failure) across callers; no environment fallback. */
export class FailClosedGate {
 private enabled=false;
 private pending?:Promise<boolean>;
 constructor(private readonly accepts:(value:unknown)=>boolean){}
 get allowed(){return this.enabled;}
 initialize(read:()=>Promise<unknown>):Promise<boolean>{
  return this.pending??=Promise.resolve().then(read).then(value=>this.enabled=this.accepts(value),()=>false);
 }
}
