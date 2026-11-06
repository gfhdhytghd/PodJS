import {ContentFiles,type ContentHost} from './content-files';
import {type ContentSignals,type AuthenticatedContentSessions} from './content-worker';
import {createSyncV2Crypto} from './crypto';
import {encodeBase64,decodeBase64} from './byte-codecs';
/** A distinct native executor, device service and HTTP client own this lane. */
export function contentHostPorts(host:ContentHost,sessions:AuthenticatedContentSessions){
 const files=new ContentFiles(host);
 const crypto=createSyncV2Crypto(async<T>(method:string,args:Record<string,unknown>)=>{
  if(method==='crypto.v2.sign'||method==='crypto.v2.signingKey'||method==='crypto.v2.verify')return host<T>(method,args);
  await host('runtime.delay',{milliseconds:1});return host<T>('content.'+method,args);
 });
 return {files,crypto,async signals(allowChargingCellular=true):Promise<ContentSignals>{
  const native=await host<Record<string,unknown>>('content.conditions',{}),confirmed=(key:string)=>typeof native[key]==='boolean'?native[key] as boolean:null;
  return {charging:confirmed('charging'),wifi:confirmed('wifi'),internet:confirmed('internet'),cellular:confirmed('cellular'),...sessions.snapshot(),allowChargingCellular};
 }};
}
