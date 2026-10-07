import {prepareTextLayout,yieldWorkTurn,type TextLayoutSliceOptions} from './text-layout.ts';
import {traceServiceWork} from './service-trace.ts';
/** null retains a subtree; an object selects fields; [selection] selects every
 * array element. A type mismatch retains the original value. */
export type JsonSelection=null|{readonly [field:string]:JsonSelection}|readonly [JsonSelection];
type Host={jsonProjectionBegin?:(selection:string)=>number|null;jsonProjectionAppend?:(id:number,part:string,last:boolean)=>boolean;jsonProjectionNext?:(id:number)=>[number,string,number,string]|null;jsonProjectionCancel?:(id:number)=>void};
function field(target:Record<string,unknown>,key:string,value:unknown){Object.defineProperty(target,key,{value,writable:true,enumerable:true,configurable:true});}
export function projectJsonValue(value:unknown,selection:JsonSelection):unknown {
 if(selection===null)return value;
 if(Array.isArray(selection))return Array.isArray(value)?value.map(row=>projectJsonValue(row,selection[0])):value;
 if(!value||typeof value!=='object'||Array.isArray(value))return value;
 const out:Record<string,unknown>={};for(const key of Object.keys(value))if(Object.prototype.hasOwnProperty.call(selection,key))field(out,key,projectJsonValue((value as Record<string,unknown>)[key],(selection as Record<string,JsonSelection>)[key]));return out;
}
export function* projectJsonSteps(source:string,selection:Readonly<Record<string,JsonSelection>>):Generator<void,unknown> {
 const host=(globalThis as {pod?:Host}).pod;let id:number|null=null;
 if(host?.jsonProjectionBegin&&host.jsonProjectionAppend&&host.jsonProjectionNext&&host.jsonProjectionCancel){
  try{id=host.jsonProjectionBegin(JSON.stringify(selection));}catch{}
  if(id!==null){let fallback=false;const out:Record<string,unknown>={};try{
   for(let start=0;start<source.length||start===0;){let end=Math.min(source.length,start+4096);if(end<source.length&&source.charCodeAt(end-1)>=0xd800&&source.charCodeAt(end-1)<=0xdbff)end--;
    let accepted=false;try{accepted=host.jsonProjectionAppend(id,source.slice(start,end),end===source.length);}catch{}if(!accepted){fallback=true;break;}start=end;yield;if(start===source.length)break;
   }
   if(!fallback)for(;;){let row;try{row=traceServiceWork('json.nativeStep',()=>host.jsonProjectionNext!(id!));}catch{fallback=true;break;}if(!row){fallback=true;break;}const[status,key,index,json]=row;
    if(status===2)return out;if(status===1){if(index===-2)field(out,key,[]);else{const value=traceServiceWork('json.adopt',()=>JSON.parse(json),json.length);if(index===-1)field(out,key,value);else (out[key] as unknown[]).push(value);}}yield;
   }
  }finally{host.jsonProjectionCancel(id);}}
 }
 return projectJsonValue(JSON.parse(source),selection);
}
/** Host jobs use <=8MiB source, four concurrent jobs and <=65536 array elements.
 * Upload and item adoption yield between slices. A single huge selected subtree
 * or native validation step is indivisible; this is not a hard frame budget.
 * Unsupported input falls back to JSON.parse, including its exact errors. */
export function requestJsonProjection(source:string,selection:Readonly<Record<string,JsonSelection>>,publish:(value:unknown)=>void,options:TextLayoutSliceOptions={}){
 let key=0;return prepareTextLayout(projectJsonSteps(source,selection),{key:()=>key++,entry:()=>undefined,publish:({layout})=>publish(layout)},{yieldTurn:yieldWorkTurn,...options});
}
export async function projectJsonAsync(source:string,selection:Readonly<Record<string,JsonSelection>>,options:TextLayoutSliceOptions={}):Promise<unknown>{let finished=false,result:unknown;const task=requestJsonProjection(source,selection,value=>{finished=true;result=value;},options);await task.done;if(!finished)throw Error('JSON projection cancelled');return result;}
