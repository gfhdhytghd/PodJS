import {cpuWork,cpuJsonParse,cpuJsonStringify,cpuSort,cpuWorkAsync} from './cpu-work';
/** Bounded JSON syntax tree over immutable source text. Unknown values never
 * need to pass through JS Number or JSON.stringify when patching known fields. */
export interface RawJsonProperty {key:string;start:number;value:RawJsonNode}
export interface RawJsonNode {source:string;start:number;end:number;kind:'object'|'array'|'scalar';properties?:RawJsonProperty[];items?:RawJsonNode[]}
export interface RawJsonOptions {maxDepth?:number;maxBytes?:number;native?:boolean}
export class RawJsonError extends Error {constructor(public code:'syntax'|'duplicate-key'|'unicode'|'depth'|'size'){super('raw_json_'+code);}}
export function jsonUtf8Bytes(text:string):number {
 const root=globalThis as {pod?:{strictUtf8ByteLength?:Function};__podByteCodecs?:{strictUtf8ByteLength?:Function}};
 const native=(root.pod??root.__podByteCodecs)?.strictUtf8ByteLength;
 if(native&&text.length>=64){
  let bytes=0;for(let at=0;at<text.length;){let end=Math.min(text.length,at+65536);const last=text.charCodeAt(end-1);if(end<text.length&&last>=0xd800&&last<=0xdbff)end++;
   const part=text.slice(at,end),n=native(part);bytes+=typeof n==='number'?n:portableUtf8Bytes(part);at=end;
  }return bytes;
 }
 return portableUtf8Bytes(text);
}
function portableUtf8Bytes(text:string):number {
 let bytes=0;for(let i=0;i<text.length;i++){const n=text.charCodeAt(i);if(n>=0xd800&&n<=0xdbff){const next=text.charCodeAt(++i);if(!(next>=0xdc00&&next<=0xdfff))throw new RawJsonError('unicode');bytes+=4;}else if(n>=0xdc00&&n<=0xdfff)throw new RawJsonError('unicode');else bytes+=n<128?1:n<2048?2:3;}return bytes;
}
function* parseRawJsonSteps(source:string,options:RawJsonOptions={}):Generator<void,RawJsonNode> {
 const maxDepth=options.maxDepth??32,maxBytes=options.maxBytes??16*1024*1024;
 if(!Number.isInteger(maxDepth)||maxDepth<1||!Number.isSafeInteger(maxBytes)||maxBytes<0)throw new RawJsonError('size');
 let bytes=0;for(let start=0;start<source.length;){let end=Math.min(source.length,start+16384);const c=source.charCodeAt(end-1);if(c>=0xd800&&c<=0xdbff)end++;bytes+=jsonUtf8Bytes(source.slice(start,end));if(bytes>maxBytes)throw new RawJsonError('size');start=end;if(start<source.length)yield;}
 const scalar=/(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/y;
 let at=0;const fail=():never=>{throw new RawJsonError('syntax');};
 const ws=function*():Generator<void,void>{let scanned=0;while(at<source.length&&' \t\r\n'.includes(source[at])){at++;if(++scanned%16384===0)yield;}};
 const string=function*():Generator<void,string>{const start=at;if(source[at++]!=='"')return fail();let escaped=false,scanned=0;
  while(at<source.length){if(++scanned%2048===0)yield;const c=source.charCodeAt(at++);if(c<32)return fail();if(escaped){if(c===117){if(!/^[0-9a-fA-F]{4}$/.test(source.slice(at,at+4)))return fail();at+=4;}else if(!'"\\/bfnrt'.includes(String.fromCharCode(c)))return fail();escaped=false;}else if(c===92)escaped=true;else if(c===34){const value=cpuJsonParse(source.slice(start,at)) as string;jsonUtf8Bytes(value);return value;}}
  return fail();};
 let work=0;const value=function*(depth:number):Generator<void,RawJsonNode>{if(++work%256===0)yield;yield* ws();const start=at,c=source[at];
  if(c==='{'||c==='['){if(depth>maxDepth)throw new RawJsonError('depth');at++;yield* ws();
   if(c==='{'){const properties:RawJsonProperty[]=[],seen=new Set<string>();if(source[at]!=='}')for(;;){yield* ws();const p=at,key=yield* string();if(seen.has(key))throw new RawJsonError('duplicate-key');seen.add(key);yield* ws();if(source[at++]!==':')return fail();const child=yield* value(depth+1);properties.push({key,start:p,value:child});yield* ws();if(source[at]!==',')break;at++;}
    if(source[at++]!=='}')return fail();return {source,start,end:at,kind:'object',properties};
   }
   const items:RawJsonNode[]=[];if(source[at]!==']')for(;;){items.push(yield* value(depth+1));yield* ws();if(source[at]!==',')break;at++;}if(source[at++]!==']')return fail();return {source,start,end:at,kind:'array',items};
  }
  if(c==='"')yield* string();else {scalar.lastIndex=at;const match=scalar.exec(source);if(!match)return fail();at+=match[0].length;}
  return {source,start,end:at,kind:'scalar'};
 };
 const root=yield* value(1);yield* ws();if(at!==source.length)fail();return root;
}
export function parseRawJson(source:string,options:RawJsonOptions={}):RawJsonNode {return cpuWork('json-scan','chars',source.length,()=>parseRawJsonSync(source,options));}
function nativeScan(source:string,options:RawJsonOptions):RawJsonNode|undefined {
 // One embedded Rust call per bounded document; no Java/JNI service per item.
 // Large documents retain the cancellable scanner. native:false is the fallback switch.
 if(options.native===false||source.length<64||source.length>256*1024)return;
 const root=globalThis as {pod?:{rawJsonScan?:Function};__podByteCodecs?:{rawJsonScan?:Function}};
 const host=root.pod??root.__podByteCodecs;
 return host?.rawJsonScan?.(source,options.maxDepth??32,options.maxBytes??16*1024*1024)??undefined;
}
function parseRawJsonSync(source:string,options:RawJsonOptions={}):RawJsonNode {const native=nativeScan(source,options);if(native)return native;const steps=parseRawJsonSteps(source,options);for(;;){const next=steps.next();if(next.done)return next.value;}}
/** Yields after bounded scanning/projection work. The immutable source and byte
 * cap remain owned by the caller; cancellation is checked at every boundary. */
export function parseRawJsonAsync(source:string,options:RawJsonOptions&{yield?:()=>Promise<void>;check?:()=>void}={}):Promise<RawJsonNode> {return cpuWorkAsync('json-scan','chars',source.length,()=>parseRawJsonAsyncSteps(source,options));}
async function parseRawJsonAsyncSteps(source:string,options:RawJsonOptions&{yield?:()=>Promise<void>;check?:()=>void}={}):Promise<RawJsonNode> {
 const steps=parseRawJsonSteps(source,options),pause=options.yield??(()=>new Promise<void>(resolve=>setTimeout(resolve,0))),now=()=>globalThis.performance?.now?.()??Date.now();let count=0,started=now();
 try{for(;;){options.check?.();const next=steps.next();if(next.done)return next.value;
  // Keep bounded scanner fragments; share an IPC turn exactly as byte codecs
  // do. A headless timer roundtrip per 2 KiB token is not a CPU work budget.
  if(++count>=16||now()-started>=8){await pause();options.check?.();count=0;started=now();}
 }}finally{steps.return?.(undefined as never);}
}
export const rawJsonText=(node:RawJsonNode)=>node.source.slice(node.start,node.end);
/** RFC 8785 canonical JSON for protocols restricted to nonnegative safe
 * integers. Parse the original tokens first: JSON.parse alone rounds fractions
 * and underflow before Number.isSafeInteger can reject them. Unknown extension
 * data must use raw spans instead; this function deliberately canonicalizes. */
export function canonicalIntegerJson(source:string,options:RawJsonOptions={}):string {return cpuWork('canonical-json','chars',source.length,()=>{
 const root=parseRawJson(source,options);
 const integer=(token:string)=>{
  const m=/^(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(token);
  if(!m)throw new Error('json_integer_required');
  let digits=(m[2]+(m[3]??'')).replace(/^0+/,'');if(!digits)return '0';
  if(m[1])throw new Error('json_integer_required');
  const exponent=Number(m[4]??0)-(m[3]?.length??0);
  if(!Number.isSafeInteger(exponent)||Math.abs(exponent)>source.length+16)throw new Error('json_integer_required');
  if(exponent<0){const n=-exponent;if(n>digits.length||!/^0*$/.test(digits.slice(-n)))throw new Error('json_integer_required');digits=digits.slice(0,-n);}
  else {if(digits.length+exponent>16)throw new Error('json_integer_required');digits+='0'.repeat(exponent);}
  if(digits.length>16||(digits.length===16&&digits>'9007199254740991'))throw new Error('json_integer_required');
  return digits;
 };
 const emit=(node:RawJsonNode):string=>{
  if(node.kind==='array')return '['+(node.items??[]).map(emit).join(',')+']';
  if(node.kind==='object')return '{'+cpuSort([...(node.properties??[])],(a,b)=>a.key<b.key?-1:a.key>b.key?1:0).map(p=>cpuJsonStringify(p.key)+':'+emit(p.value)).join(',')+'}';
  const token=rawJsonText(node);return token[0]==='"'?cpuJsonStringify(rawJsonValue(node)):['true','false','null'].includes(token)?token:integer(token);
 };
 return emit(root);
});}
export function rawJsonProperty(node:RawJsonNode,key:string){for(const property of node.properties??[])if(property.key===key)return property.value;}
export function rawJsonValue<T=unknown>(node:RawJsonNode):T{return cpuJsonParse(rawJsonText(node)) as T;}
/** Values in edits are already complete JSON tokens. Only named properties are
 * changed; every untouched key-through-value span is copied byte for byte.
 * undefined explicitly removes a property. No mutation or whole-document parse. */
export function patchRawJsonObject(node:RawJsonNode,edits:ReadonlyMap<string,string|undefined>|Record<string,string|undefined>):string {
 if(node.kind!=='object')throw new RawJsonError('syntax');const changes=edits instanceof Map?edits:new Map(Object.entries(edits));
 const fields:string[]=[],seen=new Set<string>();
 for(const p of node.properties??[]){seen.add(p.key);if(changes.has(p.key)){const next=changes.get(p.key);if(next!==undefined){parseRawJson(next);fields.push(node.source.slice(p.start,p.value.start)+next);}}else fields.push(node.source.slice(p.start,p.value.end));}
 for(const [key,next]of changes)if(!seen.has(key)&&next!==undefined){jsonUtf8Bytes(key);parseRawJson(next);fields.push(cpuJsonStringify(key)+':'+next);}
 return '{'+fields.join(',')+'}';
}
export type RawJsonSchema=true|{[key:string]:RawJsonSchema};
/** Counts outermost unknown properties once, including key/colon/value whitespace. */
export function rawJsonUnknownBytes(node:RawJsonNode,schema:RawJsonSchema):number {
 if(schema===true)return 0;let bytes=0;
 for(const p of node.properties??[]){const known=Object.prototype.hasOwnProperty.call(schema,p.key)?schema[p.key]:undefined;if(known===undefined)bytes+=jsonUtf8Bytes(node.source.slice(p.start,p.value.end));else if(known!==true)bytes+=rawJsonUnknownBytes(p.value,known);}
 return bytes;
}
