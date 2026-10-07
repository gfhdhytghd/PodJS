import type {BrowserAuthStatus} from './services.ts';
export interface BrowserQrWaitOptions {message:string}
export interface BrowserQrWaitStatus {visible:boolean;message:string}
/** QR acquisition can outlive page loading. A terminal operation never waits. */
export function browserQrWaitStatus(options:BrowserQrWaitOptions|undefined, state:{hasQr:boolean;verifying:boolean;ended:boolean}):BrowserQrWaitStatus {
 return {visible:!!options&&!state.hasQr&&!state.verifying&&!state.ended,message:options?.message??''};
}
export class BrowserQrWait {
 private hasQr=false;
 private verifying=false;
 private ended=false;
 constructor(private readonly options?:BrowserQrWaitOptions){}
 get status():BrowserQrWaitStatus {return browserQrWaitStatus(this.options,{hasQr:this.hasQr,verifying:this.verifying,ended:this.ended});}
 update(event:BrowserAuthStatus):BrowserQrWaitStatus {
  if(!this.ended)switch(event.type){
   case 'qr':if(!this.verifying)this.hasQr=true;break;
   case 'qrCleared':this.hasQr=false;break;
   case 'verifying':this.verifying=event.active;if(event.active)this.hasQr=false;break;
   case 'cancelled':this.ended=true;this.hasQr=false;break;
  }
  return this.status;
 }
 finish():BrowserQrWaitStatus {this.ended=true;this.hasQr=false;return this.status;}
}
