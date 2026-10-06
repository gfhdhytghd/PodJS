import {expect,test} from 'bun:test';
import {fileURLToPath} from 'node:url';
test('debug memory ownership through isolated production service bridge',async()=>{
 const suite=fileURLToPath(new URL('./debug-memory.isolated.ts',import.meta.url));
 const child=Bun.spawn([process.execPath,'--conditions=browser','test',suite],{stdout:'pipe',stderr:'pipe'});
 const [code,stdout,stderr]=await Promise.all([child.exited,new Response(child.stdout).text(),new Response(child.stderr).text()]);
 expect(code,`${stdout}\n${stderr}`).toBe(0);
},30_000);
