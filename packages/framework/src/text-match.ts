export function textMatches(text:string,keyword:string):{start:number;end:number}[] {
 const query=keyword.trim(),found:{start:number;end:number}[]=[];if(!query||!text)return found;
 const lower=text.toLowerCase(),needle=query.toLowerCase();let at=lower.indexOf(needle);
 while(at>=0){found.push({start:at,end:at+query.length});at=lower.indexOf(needle,at+query.length);}
 return found;
}

export function matchSegments(line:string,keyword:string,measure:(text:string)=>number){
 return textMatches(line,keyword).map(({start,end})=>({
  text:line.slice(start,end),x:measure(line.slice(0,start)),width:measure(line.slice(start,end))
 }));
}

/** Highlight in the source before line wrapping, as native AnnotatedString does. */
export function matchLines(source:string,lines:readonly string[],keyword:string,measure:(text:string)=>number){
 const normalized=source.replace(/\r\n?/g,'\n'),matches=textMatches(normalized,keyword);
 let cursor=0;
 return lines.map(line=>{
  const visible=line.endsWith('…')?line.slice(0,-1):line;
  if(!visible){return [];}
  const start=normalized.indexOf(visible,cursor);
  if(start<0)return matchSegments(visible,keyword,measure);
  cursor=start+visible.length;
  return matches.flatMap(match=>{
   const from=Math.max(start,match.start),to=Math.min(cursor,match.end);
   if(from>=to)return [];
   const localStart=from-start,text=visible.slice(localStart,to-start);
   return [{text,x:measure(visible.slice(0,localStart)),width:measure(text)}];
  });
 });
}
