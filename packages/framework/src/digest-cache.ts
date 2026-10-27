/** Bounded, memory-only memoization of a pure digest over exact small byte values.
 * No identity/schema assumptions, keys, disk state or acknowledgement shortcuts.
 * The caller's fence runs even on hits; failures are never retained. */
export function memoizeSmallDigest(digest:(bytes:Uint8Array)=>Promise<string>|string,options:{maxBytes?:number;maxEntries?:number;maxInputBytes?:number;beforeUse?:()=>void}={}){
 const cache=new Map<string,{promise:Promise<string>;bytes:number}>();let bytes=0;
 const maxBytes=options.maxBytes??8*1024*1024,maxEntries=options.maxEntries??32768,maxInput=options.maxInputBytes??1024;
 return async(input:Uint8Array):Promise<string>=>{
  options.beforeUse?.();
  if(input.length>maxInput||maxEntries<1||maxBytes<256)return digest(input);
  let key='';for(let at=0;at<input.length;at+=128)key+=String.fromCharCode(...input.subarray(at,at+128));
  const hit=cache.get(key);if(hit){cache.delete(key);cache.set(key,hit);return hit.promise;}
  const weight=256+2*key.length;if(weight>maxBytes)return digest(input);
  while(cache.size&&(cache.size>=maxEntries||bytes+weight>maxBytes)){const oldest=cache.keys().next().value!;bytes-=cache.get(oldest)!.bytes;cache.delete(oldest);}
  const entry={promise:Promise.resolve(digest(input.slice())),bytes:weight};cache.set(key,entry);bytes+=weight;
  try{return await entry.promise;}catch(error){if(cache.get(key)===entry){cache.delete(key);bytes-=weight;}throw error;}
 };
}
