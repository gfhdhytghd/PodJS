import {test,expect} from 'bun:test';
import {readFileSync} from 'node:fs';
import {resolvePodProject} from '../packages/cli/src/project.ts';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {POD_TARGETS} from '../packages/framework/src/targets.ts';
test('shell config validates allowlist and persists it',()=>{
 const dir=mkdtempSync(join(tmpdir(),'pod-shell-'));try{
  writeFileSync(join(dir,'main.ts'),'');const config={entry:'main.ts',appId:'example.app',name:'Shell',versionName:'1',versionCode:1,capabilities:[],resources:{fontSource:'system'}};
  const resolve=(shellCommands:unknown)=>{writeFileSync(join(dir,'pod.config.json'),JSON.stringify({...config,shellCommands}));return resolvePodProject(dir,POD_TARGETS,{fontRegular:'',fontBold:'',outputRoot:dir});};
  expect(resolve(['demo.go']).config.shellCommands).toEqual(['demo.go']);
  for(const value of [['demo.go','demo.go'],['eval'],['bad/><.go'],Array(65).fill('demo.go'),{},[5]])expect(()=>resolve(value)).toThrow();
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('both call overloads check Binder UID as their first statement and release manifest exports no grants',()=>{
 const source=readFileSync(new URL('../platforms/android/runtime/src/main/java/dev/podjs/runtime/PodShellProvider.java',import.meta.url),'utf8');
 expect(source.match(/public Bundle call\([^)]*\) \{\s*ShellCommandQueue.requireUid\(Binder.getCallingUid\(\)\);/g)?.length).toBe(2);
 expect(source).not.toContain('getCallingPackage(');expect(source).not.toContain('FLAG_DEBUGGABLE');
 const manifest=readFileSync(new URL('../platforms/android/runtime/src/main/AndroidManifest.xml',import.meta.url),'utf8');expect(manifest).toContain('${applicationId}.podjs.shell');expect(manifest).toContain('android:grantUriPermissions="false"');
});

test('readonly shell command subset is validated and retained',()=>{
 const dir=mkdtempSync(join(tmpdir(),'pod-readonly-'));try{writeFileSync(join(dir,'main.ts'),'');
 const config={entry:'main.ts',appId:'example.app',name:'Shell',versionName:'1',versionCode:1,capabilities:[],resources:{fontSource:'system'},shellCommands:['library.books']};
 const resolve=(shellReadOnlyCommands:unknown)=>{writeFileSync(join(dir,'pod.config.json'),JSON.stringify({...config,shellReadOnlyCommands}));return resolvePodProject(dir,POD_TARGETS,{fontRegular:'',fontBold:'',outputRoot:dir});};
 expect(resolve(['library.books']).config.shellReadOnlyCommands).toEqual(['library.books']);for(const value of [['other.command'],['library.books','library.books'],{},[5]])expect(()=>resolve(value)).toThrow();
 }finally{rmSync(dir,{recursive:true,force:true});}
});
