import {expect,test} from 'bun:test';
import {projectJsonAsync,projectJsonSteps,projectJsonValue,requestJsonProjection} from '../src/json.ts';
test('default constructor, nested selection, number and string semantics',async()=>{
 const raw='{"rows":[{"n":-0,"s":"\\ud800","unused":3},{"n":1e400}],"__proto__":{"ok":1},"discard":9}';
 const selection=JSON.parse('{"rows":[{"n":null,"s":null}],"__proto__":null}');
 const value:any=await projectJsonAsync(raw,selection);expect(Object.keys(value)).toEqual(['rows','__proto__']);expect(Object.getPrototypeOf(value)).toBe(Object.prototype);expect(Object.is(value.rows[0].n,-0)).toBe(true);expect(value.rows[0].s).toBe('\ud800');expect(value.rows[1].n).toBe(Infinity);
 expect(value).toEqual(projectJsonValue(JSON.parse(raw),selection));
});
test('fallback preserves JSON errors and nonobject or mismatched types',async()=>{
 for(const raw of ['','{"x":1,}','[1,2','{"x":"\\uZZZZ"}'])await expect(projectJsonAsync(raw,{x:null})).rejects.toThrow(SyntaxError);
 for(const raw of ['null','4','[1,2]','{"rows":false}'])expect(await projectJsonAsync(raw,{rows:[{n:null}]})).toEqual(projectJsonValue(JSON.parse(raw),{rows:[{n:null}]}));
});
test('cancel closes native job before publication and bounds upload',async()=>{
 const previous=(globalThis as any).pod;let freed=0;let largest=0;let published=0;const pending:any[]=[];
 (globalThis as any).pod={jsonProjectionBegin:()=>3,jsonProjectionAppend:(_:number,part:string)=>{largest=Math.max(largest,part.length);return true;},jsonProjectionNext:()=>[0,'',-1,''],jsonProjectionCancel:()=>freed++};
 try{const task=requestJsonProjection('x'.repeat(20000),{x:null},()=>published++,{rowLimit:1,yieldTurn:()=>({result:new Promise<void>(resolve=>pending.push(resolve)),cancel(){pending.shift()?.();}})});pending.shift()();await Promise.resolve();task.cancel();await task.done;expect(published).toBe(0);expect(freed).toBe(1);expect(largest).toBeLessThanOrEqual(4096);
 for(let i=0;i<8;i++){const steps=projectJsonSteps('{}',{x:null});steps.next();steps.return(undefined);}expect(freed).toBe(9);
 }finally{(globalThis as any).pod=previous;}
});
