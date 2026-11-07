/** A connection-local upgrade boundary. Authentication is supplied by the
 * owner of the already authenticated socket, never by an incoming DTO.
 * No cursor, pairing or persistent state is owned by this class. */
export interface FramingEvidence {
 authenticated?:boolean;scopeMatched?:boolean;ready?:boolean;commitAck?:boolean;
 sessionCommitted?:boolean;switchId?:unknown;ackSwitchId?:unknown;
}
const h32=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{32}$/.test(v);
export function framingAllowed(v:FramingEvidence){return v.authenticated===true&&v.scopeMatched===true&&v.ready===true&&v.commitAck===true&&v.sessionCommitted===true&&h32(v.switchId)&&h32(v.ackSwitchId)&&v.switchId===v.ackSwitchId;}
function fields(v:any,keys:string[]){if(!v||Object.getPrototypeOf(v)!==Object.prototype||Reflect.ownKeys(v).length!==keys.length||keys.some(k=>!Object.hasOwn(v,k)))throw Error('invalid_framing');}
export class FramingBoundary {
 private phase:'legacy'|'preparing'|'ready'|'committing'|'wrp2'|'closed'='legacy';
 private switchId?:string;
 private closeJob?:Promise<void>;
 constructor(readonly sessionId:string,private readonly authenticated:()=>boolean,private readonly closeSocket:()=>Promise<void>){if(!h32(sessionId))throw Error('invalid_framing');}
 get enabled(){return this.phase==='wrp2'&&this.authenticated();}
 private require(phase:string){if(this.phase!==phase||!this.authenticated())throw Error('invalid_framing');}
 /** The caller exchanges complete OLD frames on this same socket. A read
  * timeout, missing ACK, decode error or cancellation closes the socket. */
 async initiate(switchId:string,exchange:(action:string,body:any)=>Promise<any>){
  try{
   this.require('legacy');if(!h32(switchId))throw Error('invalid_framing');this.switchId=switchId;this.phase='preparing';
   const ready=await exchange('syncObjectsV2.prepareFraming',{sessionId:this.sessionId,framing:'WRP2',switchId});
   this.require('preparing');fields(ready,['switchId','ready']);if(ready.ready!==true||ready.switchId!==switchId)throw Error('invalid_framing');this.phase='committing';
   const ack=await exchange('syncObjectsV2.commitFraming',{switchId});
   this.require('committing');fields(ack,['switchId','committed']);if(ack.committed!==true||ack.switchId!==switchId)throw Error('invalid_framing');this.phase='wrp2';
  }catch(e){await this.close();throw e;}
 }
 async prepare(body:any,writeOld:(body:any)=>Promise<void>){
  try{
   this.require('legacy');fields(body,['sessionId','framing','switchId']);
   if(body.sessionId!==this.sessionId||body.framing!=='WRP2'||!h32(body.switchId))throw Error('invalid_framing');
   this.switchId=body.switchId;this.phase='preparing';await writeOld({switchId:this.switchId,ready:true});this.require('preparing');this.phase='ready';
  }catch(e){await this.close();throw e;}
 }
 async commit(body:any,writeOld:(body:any)=>Promise<void>){
  try{
   this.require('ready');fields(body,['switchId']);if(!h32(body.switchId)||body.switchId!==this.switchId)throw Error('invalid_framing');
   this.phase='committing';await writeOld({switchId:this.switchId,committed:true});this.require('committing');this.phase='wrp2';
  }catch(e){await this.close();throw e;}
 }
 close(){this.phase='closed';this.switchId=undefined;return this.closeJob??=this.closeSocket();}
}
