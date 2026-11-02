import {expect,test} from 'bun:test';
import {videoPagerDelta} from '../src/video-paging.ts';
test('fast flings skip one in either direction; reversals cancel and slow drags move once',()=>{
 expect(videoPagerDelta(.1,-2400,'up')).toBe(2);expect(videoPagerDelta(.1,2400,'down')).toBe(2);
 expect(videoPagerDelta(.5,2400,'up')).toBe(0);expect(videoPagerDelta(.5,-2400,'down')).toBe(0);
 expect(videoPagerDelta(.5,-500,'up')).toBe(1);expect(videoPagerDelta(.01,0,'down')).toBe(0);
});
