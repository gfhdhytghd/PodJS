import type {LayoutOptions} from "./text-wrap.ts";
import {lazyRegExpTest} from "./regexp.ts";
const forbiddenStart=lazyRegExpTest("^[\\p{Mark}\\u200d\\ufe0f，。！？：；、）》】」』”’…％%.,!?;:)/\\]}]","u");
const forbiddenEnd=/^[（《【「『“‘(\[{]$/u;
const word=/^[A-Za-z0-9]$/;
/** Android-style UI wrapping: keep closing punctuation off the next line and Latin words together. */
export function watchUiLines(text:string,options:LayoutOptions):string[]{
 const lines:string[]=[];
 for(const paragraph of text.replace(/\r\n?/g,"\n").split("\n")){
  const chars=Array.from(paragraph);if(!chars.length){lines.push("");continue;}
  for(let start=0;start<chars.length;){let end=start;
   while(end<chars.length&&(end===start||options.measure(chars.slice(start,end+1).join(""))<=options.width))end++;
   if(end<chars.length){
    while(end>start+1&&(forbiddenStart(chars[end])||forbiddenEnd.test(chars[end-1])))end--;
    if(word.test(chars[end-1])&&word.test(chars[end])){let boundary=end-1;while(boundary>start&&word.test(chars[boundary-1]))boundary--;if(boundary>start)end=boundary;}
   }
   lines.push(chars.slice(start,end).join("").trimEnd());start=end;while(start<chars.length&&/[ \t]/.test(chars[start]))start++;
  }
 }
 return options.maxLines?lines.slice(0,options.maxLines):lines;
}
