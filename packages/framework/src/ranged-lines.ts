import {decodeUtf8Chunk} from './bytes';
export interface RangedLine {text:string;end:number}
/** Resume at a caller-committed newline byte offset. Reads stay bounded; UTF-8
 * is decoded only after a full line has arrived. No prefix is read or parsed. */
export async function* rangedLines(read:(offset:number,max:number)=>Promise<{size:number;bytes:Uint8Array}>,size:number,start=0):AsyncGenerator<RangedLine>{
 if(!Number.isSafeInteger(size)||!Number.isSafeInteger(start)||start<0||size<start)throw Error('invalid_line_range');
 let offset=start,parts:Uint8Array[]=[],length=0;
 for(;offset<size;){const r=await read(offset,Math.min(65536,size-offset));
  if(r.size!==size||!r.bytes.length||r.bytes.length>Math.min(65536,size-offset))throw Error('line_source_changed');
  let begin=0;for(let i=0;i<r.bytes.length;i++)if(r.bytes[i]===10){
   const piece=r.bytes.subarray(begin,i);length+=piece.length;parts.push(piece);
   const bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}
   if(bytes.at(-1)===13||offset+i===length&&bytes[0]===0xef&&bytes[1]===0xbb&&bytes[2]===0xbf)throw Error('invalid_line_encoding');
   const text:string[]=[];for(let n=0;n<bytes.length;){const c=decodeUtf8Chunk(bytes,n);text.push(c.text);n=c.next;}
   yield {text:text.join(''),end:offset+i+1};parts=[];length=0;begin=i+1;
  }
  if(begin<r.bytes.length){const tail=r.bytes.slice(begin);parts.push(tail);length+=tail.length;}
  offset+=r.bytes.length;
 }
 if(length)throw Error('unterminated_line');
}
