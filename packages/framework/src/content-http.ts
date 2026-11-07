import type {ContentHost} from './content-files';
import {encodeBase64,decodeBase64} from './byte-codecs';
export interface ContentTransport {request(r:{url:string;method:string;headers:Record<string,string>;body?:Uint8Array;timeoutMs?:number;readTimeoutMs?:number}):Promise<{status:number;headers:Record<string,string>;body:Uint8Array}>;}
export function createContentTransport(host:ContentHost,allowUrl:(url:string)=>boolean):ContentTransport {
 return {async request(r){
  if(!allowUrl(r.url))throw Error('isolated_backend_required');
  if((r.body?.length??0)>1048576)throw Error('object_too_large');
  let path:string|undefined;
  try{
   const out=await host<{path:string;status:number;size:number;headers:Record<string,string>}>('content.http',{url:r.url,managedTemp:true,temporary:true,method:r.method,headers:r.headers,bodyBase64:r.body?encodeBase64(r.body):'',acceptHttpErrors:true,followRedirects:false,maxBytes:1048576,callTimeoutMs:10000,timeoutMs:r.timeoutMs??10000,readTimeoutMs:r.readTimeoutMs??10000});
   path=out.path;if(!/^sync2-content-http\/slot-[0-7]\/response-[a-f0-9-]+\.data$/.test(path)||!Number.isSafeInteger(out.size)||out.size<0||out.size>1048576)throw Error('invalid_encoding');const body=new Uint8Array(out.size);
   for(let at=0;at<body.length;){const part=await host<{dataBase64:string;offset:number;size:number}>('file.read',{path,offset:at,maxBytes:Math.min(262144,body.length-at)}),bytes=decodeBase64(part.dataBase64);if(part.offset!==at||part.size!==body.length||!bytes.length||bytes.length>body.length-at)throw Error('invalid_encoding');body.set(bytes,at);at+=bytes.length;}
   return {status:out.status,headers:out.headers,body};
  }finally{if(path)await host('content.http.release',{path}).catch(()=>{});}
 }};
}
