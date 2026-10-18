/** Exact String.split(/\r\n|\n|\r/) semantics without a RegExp execution at
 * every code unit in QuickJS. One step scans at most chunkUnits UTF-16 units.
 * A very long individual line still needs one final join/allocation. */
export function* splitTextLineSteps(source:string,chunkUnits=16384):Generator<void,string[]> {
 if(!Number.isSafeInteger(chunkUnits)||chunkUnits<1||chunkUnits>65536)throw new RangeError('invalid line split chunk size');
 const lines:string[]=[],pending:string[]=[];let skipLF=false;
 for(let at=0;at<source.length;at+=chunkUnits){
  let part=source.slice(at,at+chunkUnits);const endedCR=part.endsWith('\r');
  if(skipLF&&part.startsWith('\n'))part=part.slice(1);
  skipLF=endedCR;
  if(part.includes('\r'))part=part.split('\r\n').join('\n').split('\r').join('\n');
  const pieces=part.split('\n');
  if(pieces.length===1){if(pieces[0])pending.push(pieces[0]);}
  else {
   pending.push(pieces[0]);lines.push(pending.join(''));pending.length=0;
   for(let i=1;i<pieces.length-1;i++)lines.push(pieces[i]!);
   if(pieces.at(-1))pending.push(pieces.at(-1)!);
  }
  yield;
 }
 lines.push(pending.join(''));return lines;
}
