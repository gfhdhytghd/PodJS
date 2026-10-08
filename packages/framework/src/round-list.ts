import {rectInsideCircle, type QrRect} from './safe-qr-layout.ts';

export interface RoundListOptions {
  /** Full display dimensions, in the same logical units as the rows. */
  width:number; height:number; margin?:number;
  /** List viewport within that display; defaults to the full display. */
  viewport?:{x:number;y:number;width:number};
  /** Enable layout warnings in development/debug builds only. */
  development?:boolean;
}
export interface RoundListRow {top:number;height:number;bounds?:QrRect}
export interface RoundListInsets {start:number;end:number;unfit:number[]}

/** Allowed row-top positions in viewport coordinates. Bounds are relative to
 * the row's padded left edge; omitted bounds conservatively use the entire row.
 * Intersect the circle with the clipping viewport, including displaced lists. */
export function roundRowRange(row:Pick<RoundListRow,'height'|'bounds'>, round:RoundListOptions, viewportHeight:number, padding=0):{min:number;max:number}|undefined {
  const viewport=round.viewport??{x:0,y:0,width:round.width};
  const bounds=row.bounds??{x:0,y:0,width:viewport.width-2*padding,height:row.height};
  const margin=round.margin??2, radius=Math.min(round.width,round.height)/2;
  if(![round.width,round.height,viewportHeight,padding,viewport.x,viewport.y,viewport.width,margin,row.height,bounds.x,bounds.y,bounds.width,bounds.height].every(Number.isFinite)
    ||round.width<=0||round.height<=0||viewportHeight<=0||margin<0||radius<=margin||bounds.width<0||bounds.height<0)return;
  const x=viewport.x+padding+bounds.x,cx=round.width/2,cy=round.height/2;
  if(x<viewport.x||x+bounds.width>viewport.x+viewport.width)return;
  const dx=Math.max(Math.abs(x-cx),Math.abs(x+bounds.width-cx)),inner=radius-margin;
  if(dx>inner)return;
  const chord=Math.sqrt(Math.max(0,inner*inner-dx*dx));
  const min=Math.max(0,cy-chord-viewport.y)-bounds.y;
  const max=Math.min(viewportHeight,cy+chord-viewport.y)-bounds.y-bounds.height;
  if(min>max+1e-6)return;
  // Use the shared four-corner predicate, including its rounding tolerance.
  if(!rectInsideCircle({x,y:viewport.y+min+bounds.y,width:bounds.width,height:bounds.height},{cx,cy,radius},margin))return;
  return {min,max:Math.max(min,max)};
}

/** Additional (not replacement) start/end whitespace. Consider every row:
 * narrow endpoint labels must not strand a wider middle card in a short list.
 * An oversized row is reported and never resized. Indexed bodies stay lazy. */
export function roundListInsets(rows:{length:number;at(index:number):RoundListRow|undefined},total:number,viewportHeight:number,round:RoundListOptions,padding=0):RoundListInsets {
  let start=0,end=0;const unfit:number[]=[];
  for(let i=0;i<rows.length;i++){
    const row=rows.at(i)!,range=roundRowRange(row,round,viewportHeight,padding);
    if(!range){unfit.push(i);continue;}
    start=Math.max(start,range.min-row.top);
    end=Math.max(end,viewportHeight-range.max-(total-row.top));
  }
  return {start:Math.max(0,start),end:Math.max(0,end),unfit};
}
