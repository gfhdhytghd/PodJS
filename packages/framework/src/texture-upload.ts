import {getOps} from '@pocketjs/framework/host';
import {uploadTextureFile} from './services.ts';

export interface TextureUploadOps {
 uploadTexture(bytes:Uint8Array,width:number,height:number,psm:number):number;
 uploadTextureSampled?(bytes:Uint8Array,width:number,height:number,psm:number,sample:number):number|null|undefined;
 freeTexture?(handle:number):void;
}
export interface TextureUploadPorts {
 /** Resolved at each call, so host replacement does not strand a texture. */
 ops?():TextureUploadOps;
 uploadFile?(path:string,width:number,height:number,psm:number):number|null;
 /** Caller may defer, queue, or otherwise implement release. No automatic release. */
 release?(handle:number):void;
}
export interface TextureFileOwnership {
 /** Runs before the upload call, including a throwing/rejected host call. */
 take():void;
 /** Only null (unavailable) returns ownership; negative rejection never does. */
 unavailable?():void;
}
/** No cache, validation, pixel conversion, or lifetime scheduling. Owners choose
 * the exact call sites and policies; a sample requests the optional sampled op.
 * Nullish sampled results fall back, while negative results remain rejections. */
export function createTextureUploader(ports:TextureUploadPorts={}) {
 const ops=ports.ops??getOps,uploadFile=ports.uploadFile??uploadTextureFile;
 return {
  uploadRgba(bytes:Uint8Array,width:number,height:number,options:{psm?:number;sample?:number}={}):number {
   const host=ops(),psm=options.psm??3;
   return (options.sample===undefined?undefined:host.uploadTextureSampled?.(bytes,width,height,psm,options.sample))
    ??host.uploadTexture(bytes,width,height,psm);
  },
  uploadFile(path:string,width:number,height:number,ownership:TextureFileOwnership,psm=3):number|null {
   ownership.take();const handle=uploadFile(path,width,height,psm);
   if(handle===null)ownership.unavailable?.();return handle;
  },
  release(handle:number):void {
   if(ports.release)ports.release(handle);else ops().freeTexture?.(handle);
  },
 };
}
