import {test,expect} from 'bun:test';
import {splitTextLineSteps} from '../src/line-split.ts';
import {splitTextLinesAsync} from '../src/text-layout.ts';
function run(text:string,size:number){const steps=splitTextLineSteps(text,size);let turns=0;for(;;){const next=steps.next();if(next.done)return {lines:next.value,turns};turns++;}}
test('line splitting is differential for mixed boundaries, empty tails and UTF-16 pairs',()=>{
 let seed=17;const next=()=>seed=(Math.imul(seed,1664525)+1013904223)>>>0;
 const inputs=['','a','\r','\n','\r\n','\r\r\n','🙂\r\n中\n\n','\ud800\r\udc00'];
 for(let i=0;i<200;i++)inputs.push(Array.from({length:next()%800},()=>['a','中','🙂','\r','\n','\ud800','\udc00'][next()%7]).join(''));
 for(const source of inputs)for(const size of [1,2,3,31,16384]){const actual=run(source,size);expect(actual.lines).toEqual(source.split(/\r\n|\n|\r/));expect(actual.turns).toBe(Math.ceil(source.length/size));}
 for(const size of [0,-1,1.5,65537,NaN])expect(()=>run('x',size)).toThrow();
});
test('production default async splitter yields bounded pieces for a rotation-sized input',async()=>{
 const row='[2026-10-18] native 元数据 fixture\r\n',source=row.repeat(Math.ceil(5244061/row.length));let slices=0,maxRows=0;
 const began=performance.now();const lines=await splitTextLinesAsync(source,{onSlice:(_ms,rows)=>{slices++;maxRows=Math.max(maxRows,rows);}});
 expect(lines).toEqual(source.split(/\r\n|\n|\r/));expect(slices).toBeGreaterThan(300);expect(maxRows).toBeLessThanOrEqual(1);expect(performance.now()-began).toBeLessThan(4000);
},10000);
