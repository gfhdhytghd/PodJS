export {TextHistory} from './text-history';
export type AutosaveState='empty'|'pending'|'saving'|'saved'|'error';
export interface DraftAutosaveOptions<T,R> {
 initial:T; saved:R|null;
 save(value:T,previous:R|null):Promise<R>;
 valueOf(saved:R):T;
 equal(a:T,b:T):boolean;
 isEmpty(value:T):boolean;
 onState?:(state:AutosaveState)=>void;
 onSaved?:(saved:R)=>void;
 onError?:(error:unknown)=>void;
 delayMs?:number;
 now?:()=>number;
}
/** Caller supplies immutable draft snapshots. Flush includes edits arriving during a save.
 * Response normalization is compared with the current draft, just like a later edit.
 * Dispose cancels the scheduled tick only; it does not cancel an in-flight write. */
export class DraftAutosave<T,R> {
 value:T;
 saved:R|null;
 state:AutosaveState;
 private savedValue:T;
 private dueAt:number|undefined;
 private saving:Promise<R|null>|undefined;
 private readonly now:()=>number;
 constructor(private readonly options:DraftAutosaveOptions<T,R>){
  this.value=options.initial;this.savedValue=options.initial;this.saved=options.saved;
  this.state=options.saved!==null?'saved':'empty';this.now=options.now??Date.now;
 }
 setValue(value:T){if(this.options.equal(value,this.value))return;this.value=value;this.changed();}
 private setState(value:AutosaveState){if(this.state===value)return;this.state=value;this.options.onState?.(value);}
 private changed(){
  this.dueAt=undefined;
  if(this.saved===null&&this.options.isEmpty(this.value)){this.setState('empty');return;}
  this.setState(this.options.equal(this.value,this.savedValue)?'saved':'pending');
  if(this.state==='pending')this.dueAt=this.now()+(this.options.delayMs??600);
 }
 tick(){if(this.dueAt===undefined||this.now()<this.dueAt)return;this.dueAt=undefined;void this.flush().catch(error=>this.options.onError?.(error));}
 private async saveOnce():Promise<R|null>{
  if(this.saving)return this.saving;
  if(this.options.equal(this.value,this.savedValue))return this.saved;
  if(this.saved===null&&this.options.isEmpty(this.value)){this.setState('empty');return null;}
  const value=this.value,previous=this.saved;
  this.setState('saving');
  const work=this.options.save(value,previous);
  this.saving=work.then(saved=>{
   this.saved=saved;this.savedValue=this.options.valueOf(saved);
   this.options.onSaved?.(saved);
   this.setState(this.options.equal(this.value,this.savedValue)?'saved':'pending');
   return saved;
  },error=>{this.setState('error');throw error;}).finally(()=>{this.saving=undefined;});
  return this.saving;
 }
 async flush():Promise<R|null>{this.dueAt=undefined;while(true){await this.saveOnce();if(this.options.equal(this.value,this.savedValue))return this.saved;}}
 dispose(){this.dueAt=undefined;}
}
