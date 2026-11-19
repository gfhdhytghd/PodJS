import {decodeUtf8Chunk} from './bytes';
export interface RangedLine {text:string;end:number}
interface RangedLineOptions {
 /** Bounded host read size. Checkpoints record returned newline offsets. */
 chunkBytes?:number;
}
/** Resume at a caller-committed newline byte offset. Reads stay bounded; UTF-8
 * is decoded only after a full line has arrived. No prefix is read or parsed. */
export async function* rangedLines(
 read:(offset:number,max:number)=>Promise<{size:number;bytes:Uint8Array}>,size:number,start=0,options:RangedLineOptions={},
):AsyncGenerator<RangedLine>{
 const chunkBytes=options.chunkBytes??65536;
 if(!Number.isSafeInteger(size)||!Number.isSafeInteger(start)||start<0||size<start||
    !Number.isInteger(chunkBytes)||chunkBytes<1||chunkBytes>262144)throw Error('invalid_line_range');
 let offset=start,parts:Uint8Array[]=[],length=0;
 for(;offset<size;){
  const max=Math.min(chunkBytes,size-offset),r=await read(offset,max);
  if(r.size!==size||!r.bytes.length||r.bytes.length>max)throw Error('line_source_changed');
  let begin=0;
  for(let i=r.bytes.indexOf(10);i>=0;i=r.bytes.indexOf(10,begin)){
   const piece=r.bytes.subarray(begin,i);
   let bytes=piece;
   if(parts.length){
    bytes=new Uint8Array(length+piece.length);
    let at=0;
    for(const part of parts){bytes.set(part,at);at+=part.length;}
    bytes.set(piece,at);
   }
   if(bytes.at(-1)===13||offset+i===bytes.length&&bytes[0]===0xef&&bytes[1]===0xbb&&bytes[2]===0xbf)throw Error('invalid_line_encoding');
   const text:string[]=[];
   for(let n=0;n<bytes.length;){
    const chunk=decodeUtf8Chunk(bytes,n,{maxBytes:chunkBytes>65536?262144:16384});
    text.push(chunk.text);
    n=chunk.next;
   }
   yield {text:text.join(''),end:offset+i+1};
   parts=[];length=0;begin=i+1;
  }
  if(begin<r.bytes.length){const tail=r.bytes.slice(begin);parts.push(tail);length+=tail.length;}
  offset+=r.bytes.length;
 }
 if(length)throw Error('unterminated_line');
}
