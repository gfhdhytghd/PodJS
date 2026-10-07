import {createEffect,onCleanup,untrack} from 'solid-js';
export type ModalInput=(title:string,value:string,options?:{restorationKey:string})=>Promise<string|null>;
export interface ModalInputRecovery {pending(key:string):Promise<boolean>;dismiss(key:string):Promise<unknown>}
export function useRestorableInput(options:{key?:string;missingInputMessage?:string;title:string;value:()=>string;input?:ModalInput;recovery?:ModalInputRecovery;active:()=>boolean;onValue:(value:string)=>void|Promise<void>;onError:(error:unknown)=>void}) {
 const key=options.key;
 let disposed=false,busy=false,epoch=0,checked=false;
 const dismiss=()=>{if(key&&options.recovery)void options.recovery.dismiss(key).catch(()=>{});};
 const open=async()=>{
  if(disposed||busy||!options.active())return;
  if(!options.input){options.onError(new Error(options.missingInputMessage??'Text input is unavailable'));return;}
  busy=true;const current=++epoch;
  try {
   const value=await options.input(options.title,options.value(),key&&options.recovery?{restorationKey:key}:undefined);
   if(!disposed&&current===epoch&&options.active()&&value!==null)await options.onValue(value);
  } catch(error) {if(!disposed&&current===epoch&&options.active())options.onError(error);}
  finally{if(current===epoch)busy=false;}
 };
 createEffect(()=>{
  const active=options.active();
  if(!active){epoch++;busy=false;if(checked)dismiss();return;}
  if(checked||!key||!options.recovery)return;
  checked=true;const current=epoch;
  void options.recovery.pending(key).then(pending=>{
   if(pending&&!disposed&&current===epoch&&options.active())void untrack(open);
  }).catch(()=>{}); // Older hosts and corrupt state keep the ordinary input path.
 });
 onCleanup(()=>{disposed=true;epoch++;dismiss();});
 return open;
}
