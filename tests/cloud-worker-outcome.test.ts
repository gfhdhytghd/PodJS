import {test,expect} from 'bun:test';
import {readFileSync} from 'node:fs';
const read=(p:string)=>readFileSync(new URL('../'+p,import.meta.url),'utf8'),root='platforms/android/runtime/src/main/java/dev/podjs/runtime/';
test('all Rust background outcome producers belong to the Java diagnostic closed set',()=>{
 const java=read(root+'CloudWorkerOutcome.java'),codes=new Set([...java.matchAll(/"([a-z_]+)"/g)].map(m=>m[1])),rust=read('crates/podjs-runtime/src/background.rs').split('#[cfg(test)]')[0].split('fn stop_code')[1];
 const emitted=[...rust.matchAll(/(?:finish\([^\n]*?,|Err\(|map_err\(\|[^|]*\|)\s*"([a-z_]+)"/g)].map(m=>m[1]);emitted.push(...[...rust.split('pub fn run')[0].matchAll(/Some\(\s*"([a-z_]+)"/g)].map(m=>m[1]));for(const code of emitted)expect(codes.has(code),code).toBe(true);expect(emitted.length).toBeGreaterThan(20);
});
test('actual worker fences periodic work before guest creation and logs every return through one closed-code finally',()=>{
 const java=read(root+'PodCloudWorker.java'),body=java.slice(java.indexOf('@Override public Result doWork'));
 expect(body.indexOf('PodCloudWork.manualRunning(context)')).toBeLessThan(body.indexOf('new PodBackgroundRun('));
 expect(body.indexOf('try{')).toBeLessThan(body.indexOf('return Result.'));expect(body).toContain('outcomeCode=CloudWorkerOutcome.code(code)');expect(body).toContain('CloudWorkerOutcome.benign(code)');expect(body).toContain('code="+outcomeCode');expect(body).toContain('PodCloudWork.yielded(context,manualToken,requestId)');
});

test('bounded local retry crosses the actual Rust-to-worker queue wiring without claiming progress',()=>{
 const rust=read('crates/podjs-runtime/src/background.rs'),worker=read(root+'PodCloudWorker.java');
 expect(rust).toContain('pub retry_class: Option');expect(worker).toContain('"bounded".equals(outcome.optString("retryClass"))');
 expect(worker).toContain('PodCloudWork.next(context,queuedToken,requestId,progress,manualToken==null,bounded)');
 expect(read(root+'PodCloudWork.java')).toContain('queue(c).next(token,id,progress,periodic,bounded)');
});
