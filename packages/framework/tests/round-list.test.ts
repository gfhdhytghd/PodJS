import {expect,test} from 'bun:test';
import {roundListInsets,roundRowRange,type RoundListOptions,type RoundListRow} from '../src/round-list.ts';
import {rectInsideCircle} from '../src/safe-qr-layout.ts';

function verify(rows:RoundListRow[],round:RoundListOptions,viewHeight=round.height,padding=18,endPadding=padding){
 const total=rows.at(-1)!.top+rows.at(-1)!.height+endPadding,insets=roundListInsets(rows,total,viewHeight,round,padding);
 const max=Math.max(0,total+insets.start+insets.end-viewHeight),viewport=round.viewport??{x:0,y:0,width:round.width};
 expect(insets.unfit).toEqual([]);
 for(const row of rows){
  const range=roundRowRange(row,round,viewHeight,padding)!;
  const top=row.top+insets.start,offset=Math.max(0,top-range.max),visibleTop=top-offset;
  expect(offset).toBeLessThanOrEqual(max+1e-6);expect(visibleTop).toBeGreaterThanOrEqual(range.min-1e-6);
  const b=row.bounds??{x:0,y:0,width:viewport.width-2*padding,height:row.height};
  expect(rectInsideCircle({x:viewport.x+padding+b.x,y:viewport.y+visibleTop+b.y,width:b.width,height:b.height},{cx:round.width/2,cy:round.height/2,radius:Math.min(round.width,round.height)/2},round.margin??2)).toBe(true);
 }
 expect(rows[0]!.top+insets.start).toBeGreaterThanOrEqual(roundRowRange(rows[0]!,round,viewHeight,padding)!.min-1e-6);
 return {insets,max};
}
test('varied first/middle/last rectangles have a fully visible scroll position and safe initial first row',()=>{
 for(const diameter of [160,233,466])for(const heights of [[10],[48],[12,48,28],[72,12,80],[24,70,24],[8,8,8,80]]){
  const scale=diameter/233,padding=18*scale;let top=padding;
  const rows=heights.map(h=>{const row={top,height:h*scale};top+=row.height+6*scale;return row;});
  verify(rows,{width:diameter,height:diameter},diameter,padding);
 }
});
test('narrow singleton uses minimal chord whitespace, not half a display',()=>{
 const row={top:0,height:30,bounds:{x:76.5,y:0,width:80,height:30}},round={width:233,height:233};
 const {insets,max}=verify([row],round,233,0,0);
 expect(insets.start).toBeGreaterThan(0);expect(insets.start).toBeLessThan(10);expect(max).toBe(0);
 const range=roundRowRange(row,round,233,0)!;expect(insets.start).toBeCloseTo(range.min,8);expect(insets.end).toBeCloseTo(233-range.max-30,8);
});
test('short list with narrow endpoints still makes wide middle content reachable',()=>{
 verify([{top:0,height:10,bounds:{x:96.5,y:0,width:40,height:10}},{top:10,height:48,bounds:{x:18,y:0,width:197,height:48}},{top:58,height:10,bounds:{x:96.5,y:0,width:40,height:10}}],{width:233,height:233},233,0,0);
});
test('fixed header and displaced/narrow viewport use the full display circle and clipping edges',()=>{
 verify([{top:18,height:48},{top:74,height:48}],{width:233,height:233,viewport:{x:0,y:80,width:233}},153);
 verify([{top:8,height:20},{top:40,height:40}],{width:300,height:260,margin:4,viewport:{x:40,y:30,width:220}},190,8);
 expect(roundRowRange({height:48},{width:233,height:233,viewport:{x:0,y:190,width:233}},43,18)).toBeUndefined();
});
test('unfit rows warn through indices without resizing, while inset text may still fit',()=>{
 const round={width:233,height:233};
 expect(roundListInsets([{top:0,height:140},{top:140,height:48}],188,233,round,18).unfit).toEqual([0]);
 expect(roundRowRange({height:140,bounds:{x:10,y:8,width:177,height:112}},round,233,18)).toBeDefined();
 expect(roundRowRange({height:1},round,233,0)).toBeUndefined();
 expect(roundRowRange({height:400},round,233,18)).toBeUndefined();
 expect(roundRowRange({height:48},{width:NaN,height:233},233,18)).toBeUndefined();
 expect(roundListInsets([],0,233,round,18)).toEqual({start:0,end:0,unfit:[]});
});
