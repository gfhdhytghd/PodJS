export interface HtmlElement {tag:string;attrs:Record<string,string>;children:(HtmlElement|string)[]}
type Row=[parent:number,tag:string,attrs:string[],text:string];
type HtmlHost={htmlBegin?:(skip:string)=>number|null;htmlAppend?:(id:number,source:string,last:boolean)=>boolean;htmlNext?:(id:number)=>[boolean,Row[]]|null;htmlNormalizeWhitespace?:(source:string)=>string|null;htmlCancel?:(id:number)=>void};
/** HTML fragment rows, not a DOM/HTML5 parser. Tolerates unmatched tags and
 * optional p/li closes. Entity names outside amp/lt/gt/quot/apos/nbsp remain
 * literal; nbsp becomes a space. No CSS, selectors, URLs, or block semantics.
 * Old hosts/oversized tokens/WTF-16 return null for the caller's exact fallback.
 * Source upload, native scan, and flat-row adoption are all bounded steps.
 */
export function* parseHtmlFragmentSteps(source:string,skipTags:readonly string[]=['script','style']):Generator<void,HtmlElement|null> {
 const host=(globalThis as {pod?:HtmlHost}).pod;
 if(!host?.htmlBegin||!host.htmlAppend||!host.htmlNext||!host.htmlCancel)return null;
 const id=host.htmlBegin(skipTags.map(tag=>tag.toLowerCase()).join(','));if(id==null)return null;
 const root:HtmlElement={tag:'body',attrs:{},children:[]},nodes:(HtmlElement|string)[]=[root];
 try{
  for(let start=0;start<source.length||start===0;){let end=Math.min(source.length,start+4096);
   if(end<source.length&&source.charCodeAt(end-1)>=0xd800&&source.charCodeAt(end-1)<=0xdbff)end--;
   let accepted:boolean;try{accepted=host.htmlAppend(id,source.slice(start,end),end===source.length);}catch{return null;}
   if(!accepted)return null;yield;start=end;if(end===source.length)break;
  }
  for(;;){const batch=host.htmlNext(id);if(!batch)return null;
   for(const [parent,tag,attrs,value] of batch[1]){
    const node:HtmlElement|string=tag?{tag,attrs:{},children:[]}:value;
    if(typeof node!=='string')for(let i=0;i<attrs.length;i+=2)node.attrs[attrs[i]]=attrs[i+1];
    const owner=nodes[parent];if(typeof owner!=='object')throw Error('Invalid native HTML parent');owner.children.push(node);nodes.push(node);
   }
   yield;if(batch[0])return root;
  }
 }finally{host.htmlCancel(id);}
}
export function parseHtmlFragment(source:string,skipTags?:readonly string[]):HtmlElement|null {
 const steps=parseHtmlFragmentSteps(source,skipTags);for(;;){const next=steps.next();if(next.done)return next.value;}
}
/** ECMAScript whitespace collapse, preserving token boundaries and lone UTF-16. */
export function normalizeHtmlWhitespace(source:string):string {
 const native=(globalThis as {pod?:HtmlHost}).pod?.htmlNormalizeWhitespace;
 if(!native||source.length<256)return source.replace(/\s+/g,' ');
 const parts:string[]=[];let previousSpace=false;
 for(let start=0;start<source.length;){let end=Math.min(source.length,start+4096);
  if(end<source.length&&source.charCodeAt(end-1)>=0xd800&&source.charCodeAt(end-1)<=0xdbff)end--;
  const chunk=source.slice(start,end);let normalized:string|null;try{normalized=native(chunk);}catch{normalized=null;}
  let part=normalized??chunk.replace(/\s+/g,' ');if(previousSpace&&part.startsWith(' '))part=part.slice(1);
  if(part)previousSpace=part.endsWith(' ');parts.push(part);start=end;
 }return parts.join('');
}

export interface HtmlTextPreview {units:number;prefix:string;imageSource:string}
/** Lightweight generic text prefix and first img src, with whitespace collapsed.
 * Returns null for unsupported hosts/UTF-16 or rejected sources. Caller owns
 * truncation punctuation, URLs, enclosure precedence and exact legacy fallback.
 * No HTML5 parsing claim; the compatibility dialect uses simple markup regexes.
 */
export function htmlTextPreview(source:string,maxUnits:number):HtmlTextPreview|null {
 if(!Number.isSafeInteger(maxUnits)||maxUnits<0||maxUnits>4096)throw new RangeError('Invalid HTML text prefix limit');
 const host=(globalThis as {pod?:{htmlPreview?:(source:string,limit:number)=>[number,string,string]|null}}).pod?.htmlPreview;
 if(!host)return null;let result:[number,string,string]|null;try{result=host(source,maxUnits);}catch{return null;}
 return result?{units:result[0],prefix:result[1],imageSource:result[2]}:null;
}
