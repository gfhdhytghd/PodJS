import {test,expect} from 'bun:test';
import {RepeatedWorkGuard} from '../src/repeated-work';

test('completed identical replay is bounded; different parts and unsuccessful attempts get full admission',()=>{
 const guard=new RepeatedWorkGuard({repeated:()=>Error('repeated')});guard.reset(true);
 for(let i=0;i<10000;i++){guard.before('part-'+i);guard.complete('part-'+i);}
 guard.before('last');guard.before('last'); // In-flight/failed work is not completed.
 guard.complete('last');guard.before('last');guard.complete('last');
 expect(()=>guard.before('last')).toThrow('repeated');
 guard.before(undefined);guard.complete(undefined);
 guard.reset(true);guard.before('last'); // Fresh guest always admits the first unit.
 guard.complete('last');guard.complete('last');guard.reset(false);
 expect(()=>guard.before('last')).not.toThrow();
});
