/** Stream authenticated local parts into individually durable AEAD outputs.
 * Receipts belong to the caller's transaction. Only done certifies gzip EOF/CRC.
 * A fresh host replays inflate up to offset; it never rewrites receipted output.
 */
export interface SealedPart {path:string;aadBase64:string;bytes:number;}
export interface SealedGzipInput {keyBase64:string;parts:SealedPart[];length:number;outputPrefix:string;aadPrefix:string;maxOutputBytes:number;}
export interface SealedGzipStep {offset:number;bytes:number;done:boolean;sha256?:string;}
export async function sealedGzip(request:<T>(method:string,args:Record<string,unknown>)=>Promise<T>,input:SealedGzipInput,offset:number,receipt:(step:SealedGzipStep)=>Promise<void>){
 try{
  for(let first=true;;first=false){
   const step=await request<SealedGzipStep>('crypto.gunzipSealedParts',{...input,offset,replayBytes:first?offset:0});
   if(step.offset!==offset||!Number.isSafeInteger(step.bytes)||step.bytes<0||step.bytes>262144||offset+step.bytes>input.maxOutputBytes||!step.done&&step.bytes!==262144||step.done&&offset+step.bytes!==input.maxOutputBytes||step.done&&!/^[a-f0-9]{64}$/.test(step.sha256??''))throw Error('invalid_sealed_gzip_step');
   // The native reply follows fsync + atomic rename, never file.write alone.
   await receipt(step);offset+=step.bytes;if(step.done)return step.sha256!;
  }
 }finally{await request('crypto.closeSealedGzip',{outputPrefix:input.outputPrefix}).catch(()=>{});}
}
