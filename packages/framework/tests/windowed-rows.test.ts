import {expect,test} from "bun:test";
import {createWindowRows} from "../src/windowed-list.ts";
const fixture=()=>Array.from({length:80},(_,index)=>({top:index*20,height:20,retain:index%7!==0}));
const visible=(rows:ReturnType<typeof fixture>,lower:number,upper:number)=>rows.filter(row=>row.top+row.height>=lower&&row.top<=upper);
test("unchanged visible boundaries reuse the same array including exact overscan edges",()=>{
 const rows=fixture(),window=createWindowRows<typeof rows[number]>();
 const first=window(rows,21,99);
 expect(first).toEqual(visible(rows,21,99));
 expect(window(rows,22,98)).toBe(first);
 expect(window(rows,20,100)).toEqual(visible(rows,20,100));
 expect(window(rows,2000,2100)).toEqual([]);
});
test("retention keeps only opted-in visited rows and reuses mounted rows on reversal",()=>{
 const rows=fixture(),window=createWindowRows<typeof rows[number]>(4);
 window(rows,21,79);
 const visited=window(rows,61,119);
 const returned=window(rows,21,79);
 expect(returned).toBe(visited);
 expect(returned).toContain(rows[1]);
 expect(returned).toContain(rows[5]);
 window(rows,121,179);const next=window(rows,161,219);
 expect(next).not.toContain(rows[0]);
 expect(next).not.toContain(rows[7]);
 expect(next.filter(row=>!visible(rows,161,219).includes(row)).length).toBeLessThanOrEqual(4);
});
test("all actual visible rows survive jumps and history is bounded and ordered",()=>{
 const rows=fixture(),window=createWindowRows<typeof rows[number]>(8);
 for(const offset of [0,35,90,330,520,180,900,40,1300,10]){
  const required=visible(rows,offset,offset+95),result=window(rows,offset,offset+95);
  for(const row of required)expect(result).toContain(row);
  const outside=result.filter(row=>!required.includes(row));
  expect(outside.length).toBeLessThanOrEqual(8);
  expect(outside.every(row=>row.retain)).toBe(true);
  expect(result.map(row=>row.top)).toEqual(result.map(row=>row.top).sort((a,b)=>a-b));
 }
});
test("source replacement clears font/layout/document history even with identical row references",()=>{
 const rows=fixture(),window=createWindowRows<typeof rows[number]>(8);
 window(rows,0,100);window(rows,200,300);
 const replacement=[...rows];
 expect(window(replacement,200,300)).toEqual(visible(replacement,200,300));
 const changed=rows.map(row=>({...row,height:18}));
 expect(window(changed,25,90)).toEqual(visible(changed,25,90));
});
test("zero, negative and nonfinite retention preserve the original slice",()=>{
 const rows=fixture();
 for(const limit of [0,-3,NaN,Infinity]){
  const window=createWindowRows<typeof rows[number]>(limit);window(rows,0,100);
  expect(window(rows,200,300)).toEqual(visible(rows,200,300));
 }
});
