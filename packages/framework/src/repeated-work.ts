/** Completed work identities between durable receipts, never a wall-time budget.
 * A first invocation (including a retry from a previous guest) always gets the
 * caller's full admission window. Only completed identical work can be replay.
 * Failed/in-flight units and cooperative yields do not count as completed work.
 * Identities stay in memory; callers must not log them or include credentials.
 */
export const REPEATED_WORK_VERSION=1;
export class RepeatedWorkGuard {
 private active=false;
 private completed=new Map<string,number>();
 constructor(private options:{repeated:()=>Error;maximumCompletions?:number;maximumIdentities?:number}){}
 reset(active:boolean){this.active=active;this.completed.clear();}
 before(identity:string|undefined){
  if(this.active&&identity!==undefined&&(this.completed.get(identity)??0)>=(this.options.maximumCompletions??2))throw this.options.repeated();
 }
 complete(identity:string|undefined){
  if(!this.active||identity===undefined)return;
  // Bounded FIFO retention can miss very old replay; it never rejects new work.
  if(!this.completed.has(identity)&&this.completed.size>=(this.options.maximumIdentities??2048))this.completed.delete(this.completed.keys().next().value!);
  this.completed.set(identity,(this.completed.get(identity)??0)+1);
 }
}
