import {cpuJsonParse} from './cpu-work';
import {runWorkSteps} from './async';
import type {CodecWorkOptions} from './async-bytes';
type JsonObject=Record<string,unknown>;
export interface JsonNumberOptions {
 maxDepth?:number;
 objectError?:()=>Error;
 depthError?:()=>Error;
}
const numericTokens=new WeakMap<object,Map<string,string>>();
/** Retains lexical number tokens beside JSON.parse results (1 and 1.0 differ).
 * Objects created outside this parser have no token metadata. */
function* parseJsonObjectNumberSteps(text:string,options:JsonNumberOptions={}):Generator<void,JsonObject> {
 const root:unknown=cpuJsonParse(text);
 if(!root||typeof root!=="object"||Array.isArray(root))throw (options.objectError?.()??new Error("JSON value must be an object"));
 let at=0;
 const space=function*():Generator<void,void>{let scanned=0;while(at<text.length&&' \t\r\n'.includes(text[at]!)){at++;if(++scanned%4096===0)yield;}};
 const string=function*():Generator<void,string>{const start=at++;let scanned=0;
  // JSON.parse already validated the text. Skip long string bodies in the
  // engine, retaining lexical number tokens without a JS loop per body byte.
  while(at<text.length){if(++scanned%128===0)yield;const quote=text.indexOf('"',at);let slash=quote-1;while(slash>=start&&text[slash]==='\\'){slash--;if(++scanned%4096===0)yield;}at=quote+1;if((quote-1-slash)%2===0)break;}
  return text.slice(start,at);
 };
 const mark=(parent:unknown,key:string,token:string|null)=>{if(parent&&typeof parent==="object"){let m=numericTokens.get(parent);if(token===null){m?.delete(key);return;}if(!m){m=new Map();numericTokens.set(parent,m);}m.set(key,token);}};
 let nodes=0;function* visit(value:unknown,parent:unknown,key:string,depth:number):Generator<void,void> {
  if(++nodes%128===0)yield;
  if(depth>(options.maxDepth??128))throw (options.depthError?.()??new Error("JSON nesting too deep"));yield* space();const c=text[at];
  mark(parent,key,null);
  if(c==='{'){
   at++;yield* space();if(text[at]==='}'){at++;return;}
   while(at<text.length){yield* space();const k=cpuJsonParse(yield* string()) as string;yield* space();at++;yield* visit(value&&typeof value==="object"?(value as JsonObject)[k]:undefined,value,k,depth+1);yield* space();if(text[at++]==='}')break;}
  }else if(c==='['){
   at++;yield* space();if(text[at]===']'){at++;return;}let index=0;
   while(at<text.length){yield* visit(Array.isArray(value)?value[index]:undefined,value,String(index++),depth+1);yield* space();if(text[at++]===']')break;}
  }else if(c==='"'){yield* string();}
  else{const start=at;while(at<text.length&&!' \t\r\n,]}'.includes(text[at]!)){at++;if((at-start)%4096===0)yield;}const token=text.slice(start,at);if(token!=="true"&&token!=="false"&&token!=="null")mark(parent,key,token);}
 }
 yield* visit(root,null,"",0);return root as JsonObject;
}
/** Synchronous compatibility entry point keeps the same numeric token metadata. */
export function parseJsonObjectNumbers(text:string,options:JsonNumberOptions={}):JsonObject {
 const steps=parseJsonObjectNumberSteps(text,options);for(;;){const next=steps.next();if(next.done)return next.value;}
}
/** The engine JSON.parse is indivisible; the subsequent token walk yields after
 * at most 128 values (and bounded whitespace/escaped-string scans), instead of
 * blocking the UI for the entire multi-megabyte manifest. */
export async function parseJsonObjectNumbersAsync(text:string,options:JsonNumberOptions&CodecWorkOptions={}):Promise<JsonObject>{
 const check=()=>{if(options.cancelled?.())throw Error('JSON parse cancelled');};check();
 if(text.length<16384){const value=parseJsonObjectNumbers(text,options);check();return value;}
 const pause=options.yield??(()=>{const scheduler=(globalThis as {__podYieldCodecTurn?:()=>Promise<void>}).__podYieldCodecTurn;return scheduler?scheduler():new Promise<void>(resolve=>setTimeout(resolve,0));});
 const steps=parseJsonObjectNumberSteps(text,options),first=steps.next();check();
 if(first.done)return first.value;
 return runWorkSteps(steps,{yieldTurn:pause,check,stepLimit:1,budgetMs:8,traceLabel:'json.numberTokens'});
}
export function jsonNumberToken(parent:object,key:string):string|undefined{return numericTokens.get(parent)?.get(key);}
export function isJsonInteger(parent:object,key:string):boolean{
 const value=(parent as Record<string,unknown>)[key],token=jsonNumberToken(parent,key);
 return typeof value==="number"&&Number.isSafeInteger(value)&&(token===undefined||/^-?(?:0|[1-9][0-9]*)$/.test(token));
}
