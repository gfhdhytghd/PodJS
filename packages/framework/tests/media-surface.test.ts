import {expect,test} from 'bun:test';
import {fileURLToPath} from 'node:url';

for(const suite of ['media-surface.isolated.ts','media-surface-component.isolated.ts']){
 test(suite+' in a fresh process',async()=>{
  const child=Bun.spawn([process.execPath,'test','--conditions=browser',fileURLToPath(new URL(suite,import.meta.url))],{stdout:'pipe',stderr:'pipe'});
  const [code,out,error]=await Promise.all([child.exited,new Response(child.stdout).text(),new Response(child.stderr).text()]);
  expect(code,out+'\n'+error).toBe(0);
 },30_000);
}
