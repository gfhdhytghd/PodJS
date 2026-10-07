export interface ScrollRow {top:number;height:number}
export type ScrollRows=readonly ScrollRow[]|{readonly length:number;at(index:number):ScrollRow|undefined};
export interface ScrollSnapshot {version:1;offset:number;anchor:{index:number;offset:number}|null}
export function validScrollSnapshot(value:unknown):value is ScrollSnapshot {
 if(!value||typeof value!=='object'||Array.isArray(value))return false;
 const v=value as ScrollSnapshot,a=v.anchor;
 return v.version===1&&Number.isFinite(v.offset)&&v.offset>=0&&(a===null||!!a&&Number.isSafeInteger(a.index)&&a.index>=0&&Number.isFinite(a.offset)&&a.offset>=0);
}
function anchorAt(offset:number,rows:ScrollRows):ScrollSnapshot['anchor'] {
 if(!rows.length)return null;
 const padding=rows.at(0)!.top;
 let low=0,high=rows.length;
 while(low<high){const mid=(low+high)>>>1,row=rows.at(mid)!;if(row.top-padding+row.height<=offset)low=mid+1;else high=mid;}
 const index=Math.min(low,rows.length-1);
 return {index,offset:Math.max(0,offset-(rows.at(index)!.top-padding))};
}
/** Keep a saved item anchor as late rows/layouts change, until user input or an
 * explicit scroll command takes ownership. Legacy absolute offsets still work. */
export class ScrollRestoration {
 private previousMax=-1;
 private previousRows:ScrollRows|undefined;
 constructor(private pending:number|ScrollSnapshot|undefined){}
 target(max:number,rows?:ScrollRows):number|undefined {
  if(this.pending===undefined||!Number.isFinite(max)||max<0||(max===this.previousMax&&rows===this.previousRows))return;
  this.previousMax=max;this.previousRows=rows;
  if(typeof this.pending==='number'){
   const value=Math.min(max,this.pending);
   if(max>=this.pending)this.pending=undefined;
   return value;
  }
  const anchor=this.pending.anchor;
  if(anchor&&rows?.length){
   const row=rows.at(anchor.index);
   // A placeholder or short first page must not consume an unavailable item.
   return row?Math.min(max,Math.max(0,row.top-rows.at(0)!.top+anchor.offset)):max;
  }
  return Math.min(max,this.pending.offset);
 }
 cancel(){this.pending=undefined;}
 snapshot(offset:number):number|ScrollSnapshot;
 snapshot(offset:number,rows:ScrollRows):ScrollSnapshot;
 snapshot(offset:number,rows?:ScrollRows):number|ScrollSnapshot {
  if(rows)return typeof this.pending==='object'?this.pending:{version:1,offset:typeof this.pending==='number'?this.pending:Math.max(0,offset),anchor:typeof this.pending==='number'?null:anchorAt(offset,rows)};
  return this.pending??Math.max(0,offset);
 }
}
