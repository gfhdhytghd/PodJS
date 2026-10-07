import type {ActivityState} from './services.ts';

/** A guest has several state owners (startup and content). Merge their namespaces
 * synchronously before dispatch so late acknowledgements cannot replace newer state. */
export class ActivityStateHub {
 private state:ActivityState={saved:{},retained:{}};
 constructor(private readonly publish:(state:ActivityState)=>Promise<void>){}
 seed(state:ActivityState){this.state=state;}
 read(){return this.state;}
 write(next:ActivityState,keys:readonly string[]):Promise<void>{
  const merged:ActivityState={saved:{...this.state.saved},retained:{...this.state.retained}};
  for(const channel of ['saved','retained'] as const)for(const key of keys){
   if(Object.hasOwn(next[channel],key))merged[channel][key]=next[channel][key];
   else delete merged[channel][key];
  }
  this.state=merged;
  return this.publish(merged);
 }
}
