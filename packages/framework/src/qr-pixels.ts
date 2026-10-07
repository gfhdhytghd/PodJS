import {stringToUtf8} from "./bytes";
import qrcode from "qrcode-generator";

export interface QrRasterOptions {encoding?:"utf8"|"byte";bitmapSize?:number;errorCorrection?:"L"|"M"|"Q"|"H";margin?:number;invert?:boolean;messages?:{content:string;raster:string;capacity:string}}

/** Single GPU texture: QR modules never become hundreds of layout nodes. */
export function qrPixels(value: string, dimension = 256, options:QrRasterOptions = {}): Uint8Array {
  const bitmapAligned=options.bitmapSize!==undefined,bitmapSize=options.bitmapSize??400,messages=options.messages;
  if (!value || value.length > 2048) throw new Error(messages?.content??"Invalid QR content");
  if (bitmapAligned && (!Number.isInteger(bitmapSize) || bitmapSize < 1)) throw new Error(messages?.raster??"Invalid QR raster");
  const qr = qrcode(0, options.errorCorrection??"M");
  const bytes=options.encoding==="utf8"?Array.from(stringToUtf8(value),byte=>String.fromCharCode(byte)).join(""):value;
  qr.addData(bytes, "Byte"); qr.make();
  const modules = qr.getModuleCount(), margin=options.margin??1, total = modules + margin*2;
  const cell = Math.floor(dimension / total);
  if (cell < 1) throw new Error(messages?.capacity??"Invalid QR capacity");
  const nativeCell=Math.floor(bitmapSize/modules),nativePadding=Math.floor((bitmapSize-nativeCell*modules)/2);
  if (bitmapAligned && nativeCell < 1) throw new Error(messages?.capacity??"Invalid QR capacity");
  const boundary=(index:number)=>bitmapAligned?Math.round((nativePadding+index*nativeCell)*dimension/bitmapSize):Math.round((index+margin)*dimension/total);
  const data = new Uint8Array(dimension * dimension * 4).fill(255);
  for (let row = 0; row < modules; row++) for (let col = 0; col < modules; col++) {
    if (!qr.isDark(row, col)) continue;
    // Map module boundaries to the power-of-two texture without surplus padding.
    for (let y = boundary(row); y < boundary(row+1); y++)
      for (let x = boundary(col); x < boundary(col+1); x++) {
        const i = (y * dimension + x) * 4;
        data[i] = data[i + 1] = data[i + 2] = 0;
      }
  }
  if(options.invert)for(let i=0;i<data.length;i+=4)data[i]=data[i+1]=data[i+2]=255-data[i];
  return data;
}
