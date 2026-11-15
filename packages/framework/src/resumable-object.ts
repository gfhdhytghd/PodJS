import {VerifiedChunkStore,type ObjectLease,type VerifiedObject} from './verified-chunk-store';
/** Business-free facade. A file publication is STAGED, never business APPLIED. */
export class ResumableObject {
 constructor(readonly store:VerifiedChunkStore,readonly lease:ObjectLease,readonly descriptor:VerifiedObject){}
 stage(index:number,bytes:Uint8Array,request:{id:string;digest:string}){return this.store.stage(this.lease,this.descriptor,index,bytes,request);}
 status(){return this.store.status(this.lease,this.descriptor);}
 missing(){return this.store.missing(this.lease,this.descriptor);}
 finalize(path?:string){return this.store.finalize(this.lease,this.descriptor,path);}
 release(discardPublication=false){return this.store.release(this.lease,this.descriptor,discardPublication);}
}
