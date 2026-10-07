/** UTF-16 source anchors keep the same text visible after font/width changes. */
export interface TextPositionAnchor { character:number; fraction:number }
export function lineStarts(text:string,lines:readonly string[]):number[]{
  const normalized=text.replace(/\r\n?/g,"\n");
  let cursor=0;
  return lines.map(line=>{
    const found=normalized.indexOf(line,cursor), start=found<0?cursor:found;
    cursor=start+line.length;
    if(normalized[cursor]==="\n")cursor++;
    return start;
  });
}
export function anchorAtOffset(offset:number,lineHeight:number,starts:readonly number[]):TextPositionAnchor {
  const position=Math.max(0,offset)/Math.max(1,lineHeight),index=Math.min(Math.max(0,starts.length-1),Math.floor(position));
  return {character:starts[index]??0,fraction:position-Math.floor(position)};
}
export function offsetForAnchor(anchor:TextPositionAnchor,lineHeight:number,starts:readonly number[],maximum:number):number {
  let index=0;
  for(let i=1;i<starts.length&&starts[i]!<=anchor.character;i++)index=i;
  return Math.max(0,Math.min(maximum,(index+Math.min(0.999,Math.max(0,anchor.fraction)))*lineHeight));
}
