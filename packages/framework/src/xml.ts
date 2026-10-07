import {prepareTextLayout,yieldWorkTurn,type TextLayoutSliceOptions} from './text-layout.ts';
import {traceServiceWork} from './service-trace.ts';
export interface XmlNode {name:string;attrs:Record<string,string>;text:string;children:XmlNode[]}
type Host={xmlBegin?:()=>number|null;xmlAppend?:(id:number,part:string,last:boolean)=>boolean;xmlNext?:(id:number)=>[number,[number,number,string,string[],string,boolean][],string?]|null;xmlCancel?:(id:number)=>void};
/** Compatibility XML tree, not a validating XML parser. ASCII names, quoted
 * attributes, CDATA and descendant text. Unknown entities remain literal;
 * amp/lt/gt/quot/apos/nbsp and numeric references use ECMAScript semantics.
 * Native jobs bound upload/output pieces, retain at most 8MiB/65536 nodes and
 * release on completion/cancellation. Unsupported source uses the sliced SDK
 * fallback. No external entities, DTD resolution, URLs or network access. */
export function decodeXmlEntities(value:string):string {
 const entities:Record<string,string>={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",nbsp:' '};
 return value.replace(/&(#x?[\da-f]+|[a-z][\w]+);/gi,(_,key:string)=>{const low=key.toLowerCase();if(low[0]==='#'){const n=low[1]==='x'?parseInt(low.slice(2),16):parseInt(low.slice(1),10);return Number.isFinite(n)?String.fromCodePoint(Math.min(n,0x10ffff)):'';}return entities[low]??`&${key};`;});
}
const node=(name:string):XmlNode=>({name,attrs:{},text:'',children:[]});
export function* portableXmlSteps(source:string):Generator<void,XmlNode> {
 const root=node('#root'),stack=[root];const token=/<!--[\s\S]*?-->|<!\[CDATA\[([\s\S]*?)\]\]>|<\?[^>]*\?>|<\s*(\/?)\s*([\w:.-]+)([^>]*)>|([^<]+)/g;let m:RegExpExecArray|null;
 while((m=token.exec(source))){
  if(m[1]!==undefined)stack[stack.length-1].text+=m[1];
  else if(!m[3]){if(m[5]!==undefined)stack[stack.length-1].text+=m[5];}
  else if(m[2]==='/'){const n=stack.pop();if(!n||n.name!==m[3].toLowerCase())throw Error(`Malformed XML: unexpected closing tag ${m[3]}`);stack[stack.length-1].text+=n.text;}
  else{const n=node(m[3].toLowerCase());(m[4]??'').replace(/([\w:.-]+)\s*=\s*(?:"([\s\S]*?)"|'([\s\S]*?)')/g,(_,k,a,b)=>(n.attrs[k.toLowerCase()]=decodeXmlEntities(a??b),''));stack[stack.length-1].children.push(n);if(!/\/\s*$/.test(m[4]??''))stack.push(n);}
  yield;
 }
 if(stack.length!==1||root.children.length!==1)throw Error('Malformed XML: expected one root element');return root;
}
export function* parseXmlDocumentSteps(source:string):Generator<void,XmlNode> {
 const host=(globalThis as {pod?:Host}).pod;
 if(!host?.xmlBegin||!host.xmlAppend||!host.xmlNext||!host.xmlCancel)return yield* portableXmlSteps(source);
 const id=host.xmlBegin();if(id==null)return yield* portableXmlSteps(source);
 let fallback=false;const nodes:XmlNode[]=[],parts:Map<number,string[]>=new Map();
 try{
  for(let start=0;start<source.length||start===0;){let end=Math.min(source.length,start+4096);if(end<source.length&&source.charCodeAt(end-1)>=0xd800&&source.charCodeAt(end-1)<=0xdbff)end--;
   let accepted=false;try{accepted=host.xmlAppend(id,source.slice(start,end),end===source.length);}catch{}if(!accepted){fallback=true;break;}start=end;yield;if(start===source.length)break;
  }
  if(!fallback)for(;;){const result=traceServiceWork('xml.nativeStep',()=>host.xmlNext!(id));if(!result){fallback=true;break;}if(result[0]===3)throw Error(result[2]);
   for(const [index,parent,name,attrs,text,last]of result[1]){if(name){const n=node(name);for(let i=0;i<attrs.length;i+=2)n.attrs[attrs[i]]=attrs[i+1];nodes[index]=n;if(index>0)nodes[parent].children.push(n);}
    const chunks=parts.get(index)??[];chunks.push(text);if(last){nodes[index].text=chunks.join('');parts.delete(index);}else parts.set(index,chunks);
   }yield;if(result[0]===2)return nodes[0];
  }
 }finally{host.xmlCancel(id);}
 return yield* portableXmlSteps(source);
}
export function parseXmlDocument(source:string):XmlNode {const steps=parseXmlDocumentSteps(source);for(;;){const next=steps.next();if(next.done)return next.value;}}
/** Cancellation closes the iterator/native job and suppresses publication. */
export function requestXmlDocument(source:string,publish:(tree:XmlNode)=>void,options:TextLayoutSliceOptions={}){let key=0;return prepareTextLayout(parseXmlDocumentSteps(source),{key:()=>key++,entry:()=>undefined,publish:({layout})=>publish(layout)},{yieldTurn:yieldWorkTurn,...options});}
export async function parseXmlDocumentAsync(source:string,options:TextLayoutSliceOptions={}):Promise<XmlNode>{let tree:XmlNode|undefined;const task=requestXmlDocument(source,value=>tree=value,options);await task.done;if(!tree)throw Error('XML preparation cancelled');return tree;}
