export interface LayoutOptions {width:number;measure:(text:string)=>number;maxLines?:number}
const lazyRegExpTest=(source:string,flags:string)=>{let re:RegExp|undefined;return (value:string)=>(re??=new RegExp(source,flags)).test(value);};
// A small grapheme splitter for runtimes without Intl.Segmenter.
function clusters(value: string): string[] { const out:string[]=[]; for(const cp of Array.from(value)) { const last=out[out.length-1]; const code=cp.codePointAt(0)!; if(last && (code===0x200d || code===0xfe0f || (code>=0x300 && code<=0x36f) || (code>=0x1ab0 && code<=0x1aff) || (code>=0x1dc0 && code<=0x1dff) || (code>=0x20d0 && code<=0x20ff))) out[out.length-1]+=cp; else if(last && last.endsWith("\u200d")) out[out.length-1]+=cp; else out.push(cp); } return out; }
const cjk = lazyRegExpTest('[\\p{Script=Han}\\p{Script=Hiragana}\\p{Script=Katakana}\\p{Script=Hangul}\\u3000-\\u303f]','u');
export function layoutText(text: string, options: LayoutOptions): string[] { const lines:string[]=[]; for(const paragraph of text.replace(/\r\n?/g,"\n").split("\n")) { if(paragraph==="") { lines.push(""); continue; } let line="", pending=""; for(const g of clusters(paragraph)) { const whitespace=/\s/u.test(g) && !cjk(g); const candidate=line+pending+g; if(options.measure(candidate)>options.width && line) { lines.push(line); line=pending+g; pending=""; continue; } if(whitespace) pending+=g; else { line+=pending+g; pending=""; } } line+=pending; lines.push(line); } return options.maxLines ? lines.slice(0,options.maxLines) : lines; }

export interface AtlasWrapOptions {width:number;fontSlot:number;scale?:number;tracking?:number;measure:(text:string)=>number}
/** Native fast path is bounded; arbitrary measurers keep the portable layout. */
export function wrapAtlasText(text:string,options:AtlasWrapOptions):string[]{
 const native=(globalThis as {pod?:{wrapAtlasTextChunk?:(text:string,slot:number,width:number,scale:number,tracking:number)=>string[]|null|undefined}}).pod?.wrapAtlasTextChunk;
 if(typeof native==='function'&&text.length<=4096){const lines=native(text,options.fontSlot,options.width,options.scale??1,options.tracking??0);if(Array.isArray(lines))return lines;}
 return layoutText(text,{width:options.width,measure:options.measure});
}
