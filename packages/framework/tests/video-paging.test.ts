import {expect,test} from 'bun:test';
import {VideoFirstFrameWait,videoPagerOffsets,videoPagerSettleProgress} from '../src/video-paging';
test('strip edges touch from the first pixel in both directions, including snapback',()=>{
 for(const direction of ['up','down'] as const)for(const progress of [0,1/233,.1,.5,1]){
  const offsets=videoPagerOffsets(progress,233,direction);
  expect(Math.abs(offsets.incoming-offsets.outgoing)).toBeCloseTo(233);
 }
 expect(videoPagerSettleProgress(.8,0,220)).toBe(0);
});
test('first-frame spinner waits 400ms from settle and never flashes for a rendered item',()=>{
 let time=1000;const wait=new VideoFirstFrameWait(()=>time);wait.reset();
 time+=399;expect(wait.visible()).toBe(false);time++;expect(wait.visible()).toBe(true);
 wait.observe(true);expect(wait.visible()).toBe(false);time+=10000;expect(wait.visible()).toBe(false);
 wait.reset();time+=100;wait.observe(true);time+=1000;expect(wait.visible()).toBe(false);
});
test('default first-frame clock is constructed as in production',()=>{
 const wait=new VideoFirstFrameWait();wait.reset();expect(wait.visible()).toBe(false);
});
