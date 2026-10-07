import {utf8ToString} from './bytes.ts';
export interface HttpReadStream {read(max:number,timeoutMs:number):Promise<Uint8Array|null>}
export interface HttpRequest {method?:string;target:string;headers:Record<string,string>;body:string}
export interface HttpReadOptions {now?:()=>number;timeoutMs?:number;readBytes?:number;maxHeaderBytes?:number;maxBodyBytes?:number}
class InvalidRequest extends Error {}
/** One request per connection, with a whole-request deadline and bounded buffers. */
export async function readHttpRequest(stream:HttpReadStream,options:HttpReadOptions={}):Promise<HttpRequest> {
 const now=options.now??Date.now;
 const deadline=now()+(options.timeoutMs??15000);let buffer:Uint8Array=new Uint8Array(0);let offset=0,headerBytes=0;
 const read=async(length:number):Promise<Uint8Array>=>{
  const out=new Uint8Array(length);let used=0;
  while(used<length){if(offset===buffer.length){const remaining=deadline-now();if(remaining<=0)throw new InvalidRequest('Request timed out');const bytes=await stream.read(options.readBytes??4096,remaining);if(!bytes?.length||bytes.length>(options.readBytes??4096)||now()>deadline)throw new InvalidRequest('Truncated request');buffer=bytes;offset=0;}
   const count=Math.min(length-used,buffer.length-offset);out.set(buffer.subarray(offset,offset+count),used);used+=count;offset+=count;
  }return out;
 };
 const line=async():Promise<string>=>{const bytes:number[]=[];while(true){if(++headerBytes>(options.maxHeaderBytes??16384))throw new InvalidRequest('Headers too large');const b=(await read(1))[0];if(b===10){if(bytes.pop()!==13)throw new InvalidRequest('Invalid line ending');return utf8ToString(new Uint8Array(bytes));}bytes.push(b);}};
 const start=await line(),match=/^[A-Z]+ ([^\s]+) HTTP\/1\.[01]$/.exec(start);if(!match||!match[1].startsWith('/'))throw new InvalidRequest('Invalid request line');
 const headers:Record<string,string>=Object.create(null);
 while(true){const value=await line();if(!value)break;const colon=value.indexOf(':');if(colon<1)throw new InvalidRequest('Invalid header');const key=value.slice(0,colon).toLowerCase();if(!/^[a-z0-9!#$%&'*+.^_`|~-]+$/.test(key))throw new InvalidRequest('Invalid header');if(headers[key]!==undefined&&(key==='content-length'||key==='transfer-encoding'))throw new InvalidRequest('Duplicate framing');headers[key]=value.slice(colon+1).trim();}
 let bytes:Uint8Array;
 if(headers['transfer-encoding']){
  if(headers['content-length']!==undefined||headers['transfer-encoding'].toLowerCase()!=='chunked')throw new InvalidRequest('Invalid transfer encoding');
  const parts:Uint8Array[]=[];let size=0;
  while(true){const encoded=(await line()).split(';',1)[0];if(!/^[0-9a-f]+$/i.test(encoded))throw new InvalidRequest('Invalid chunk');const count=parseInt(encoded,16);if(!Number.isSafeInteger(count)||count>(options.maxBodyBytes??65536)-size)throw new InvalidRequest('Body too large');
   if(count===0){while(await line()){}break;}parts.push(await read(count));size+=count;if(await line()!=='')throw new InvalidRequest('Invalid chunk ending');
  }bytes=new Uint8Array(size);let at=0;for(const part of parts){bytes.set(part,at);at+=part.length;}
 }else{
  const length=headers['content-length']??'0';if(!/^\d+$/.test(length)||Number(length)>(options.maxBodyBytes??65536))throw new InvalidRequest('Body too large');bytes=await read(Number(length));
 }
 return {method:start.slice(0,start.indexOf(' ')),target:match[1],headers,body:utf8ToString(bytes)};
}
