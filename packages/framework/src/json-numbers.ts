type JsonObject=Record<string,unknown>;
export interface JsonNumberOptions {
 maxDepth?:number;
 objectError?:()=>Error;
 depthError?:()=>Error;
}
const numericTokens=new WeakMap<object,Map<string,string>>();
/** Retains lexical number tokens beside JSON.parse results (1 and 1.0 differ).
 * Objects created outside this parser have no token metadata. */
export function parseJsonObjectNumbers(text:string,options:JsonNumberOptions={}):JsonObject {
 const root:unknown=JSON.parse(text);
 if(!root||typeof root!=="object"||Array.isArray(root))throw (options.objectError?.()??new Error("JSON value must be an object"));
 let at=0;
 const space=()=>{while(at<text.length&&/[\x20\t\r\n]/.test(text[at]!))at++;};
 const string=()=>{const start=at++;
  // JSON.parse already validated the text. Skip long string bodies in the
  // engine, retaining lexical number tokens without a JS loop per body byte.
  while(at<text.length){const quote=text.indexOf('"',at);let slash=quote-1;while(slash>=start&&text[slash]==='\\')slash--;at=quote+1;if((quote-1-slash)%2===0)break;}
  return text.slice(start,at);
 };
 const mark=(parent:unknown,key:string,token:string|null)=>{if(parent&&typeof parent==="object"){let m=numericTokens.get(parent);if(token===null){m?.delete(key);return;}if(!m){m=new Map();numericTokens.set(parent,m);}m.set(key,token);}};
 function visit(value:unknown,parent:unknown,key:string,depth:number):void {
  if(depth>(options.maxDepth??128))throw (options.depthError?.()??new Error("JSON nesting too deep"));space();const c=text[at];
  mark(parent,key,null);
  if(c==='{'){
   at++;space();if(text[at]==='}'){at++;return;}
   while(at<text.length){space();const k=JSON.parse(string()) as string;space();at++;visit(value&&typeof value==="object"?(value as JsonObject)[k]:undefined,value,k,depth+1);space();if(text[at++]==='}')break;}
  }else if(c==='['){
   at++;space();if(text[at]===']'){at++;return;}let index=0;
   while(at<text.length){visit(Array.isArray(value)?value[index]:undefined,value,String(index++),depth+1);space();if(text[at++]===']')break;}
  }else if(c==='"'){string();}
  else{const start=at;while(at<text.length&&!/[\x20\t\r\n,\]}]/.test(text[at]!))at++;const token=text.slice(start,at);if(token!=="true"&&token!=="false"&&token!=="null")mark(parent,key,token);}
 }
 visit(root,null,"",0);return root as JsonObject;
}
export function jsonNumberToken(parent:object,key:string):string|undefined{return numericTokens.get(parent)?.get(key);}
export function isJsonInteger(parent:object,key:string):boolean{
 const value=(parent as Record<string,unknown>)[key],token=jsonNumberToken(parent,key);
 return typeof value==="number"&&Number.isSafeInteger(value)&&(token===undefined||/^-?(?:0|[1-9][0-9]*)$/.test(token));
}
