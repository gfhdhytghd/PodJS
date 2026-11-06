import {unpack} from '../../../vendor/pocketjs/framework/compiler/pak.ts';

/** Device-resolved fonts carry slot/size metadata only, never font bytes or pixels. */
export function systemFontManifest(pak:Uint8Array,warmChars:string) {
  const blobs=unpack(pak);
  if(blobs.some(b=>b.key.startsWith('ui:font-source.')))throw Error('System font package contains a full font source');
  const slots=blobs.filter(b=>b.key.startsWith('ui:font.')).map(b=>{
    const v=new DataView(b.data.buffer,b.data.byteOffset,b.data.byteLength);
    if(b.data.length!==24||v.getUint32(0,true)!==0x41464344||v.getUint16(4,true)!==7)throw Error('Expected system font descriptor');
    const px=v.getFloat64(16,true);
    if(!Number.isFinite(px)||px<1||px>128||b.data[12]!>63||b.data[13]!>1||b.data[14]!<1||b.data[14]!>4||b.data[15]!==0||b.data.subarray(6,12).some(x=>x!==0)||b.key!==`ui:font.${b.data[12]}`)throw Error('Invalid system font descriptor');
    return {slot:b.data[12]!,px:v.getFloat64(16,true),bold:!!(b.data[13]!&1),density:b.data[14]!};
  });
  if(!slots.length)throw Error('System font package has no slots');
  return {version:1,diskCacheBytes:16*1024*1024,warmChars:[...new Set([...Array.from({length:95},(_,i)=>String.fromCodePoint(32+i)),...warmChars])].join(''),slots};
}
