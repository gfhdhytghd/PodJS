import {cpuWorkAsync} from './cpu-work';
/** Hash a large view with bounded pure native calls, without staging plaintext
 * through file.write. No guest input survives a native update. */
export async function sha256Stream(bytes:Uint8Array,yieldTurn?:()=>Promise<void>):Promise<Uint8Array|null>{
 const g=globalThis as {pod?:{sha256Create?:()=>Digest};__podByteCodecs?:{sha256Create?:()=>Digest}};
 const create=g.pod?.sha256Create??g.__podByteCodecs?.sha256Create;if(!create)return null;
 return cpuWorkAsync('sha256','bytes',bytes.length,async()=>{
  const hash=create();try{for(let at=0;at<bytes.length;at+=262144){if(!hash.update(bytes.subarray(at,at+262144)))throw Error('sha256_update_failed');if(at+262144<bytes.length)await yieldTurn?.();}const result=hash.finish();if(!(result instanceof Uint8Array)||result.length!==32)throw Error('sha256_finish_failed');return result;}catch(error){try{hash.finish();}catch{}throw error;}
 });
}
interface Digest {update(bytes:Uint8Array):boolean;finish():Uint8Array|null;}
