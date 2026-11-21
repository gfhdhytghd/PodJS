import {getOps} from '@pocketjs/framework/host';
import {measuredLines} from './measured-text.ts';

export interface AtlasTextOptions {
  width:number;
  fontSlot:number;
  lineHeight:number;
  maxLines?:number;
}
export function layoutAtlasText(text:string,{width,fontSlot,lineHeight,maxLines}:AtlasTextOptions) {
  const ops=getOps();
  const measure=(line:string)=>ops.measureText(line,fontSlot);
  const lines=width>0
    ? measuredLines(text,width,measure,maxLines).map(line=>measure(line)<=width ? line : measure('…')<=width ? '…' : '')
    : [];
  return {
    lines,
    style:{width:Math.max(0,width),fontSlot,lineHeight,height:lines.length*lineHeight},
  };
}
