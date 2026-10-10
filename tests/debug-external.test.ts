import {expect,test} from 'bun:test';
import {mkdtempSync,readFileSync,readdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {validateDebugExternal,writeDebugExternal} from '../packages/cli/src/debug-external.ts';
const config={activities:[{name:'example.app.Entry',action:'example.action.OPEN'}],receiver:{name:'example.app.Receiver',actions:['example.action.NEXT']},provider:{name:'example.app.Provider',authority:'example.app.debuglog'}};
test('generated debug manifest and source include configured ABI only',()=>{const dir=mkdtempSync(join(tmpdir(),'pod-debug-'));try{writeDebugExternal(dir,config,'dev.podjs.androidwatch.MainActivity');const xml=readFileSync(join(dir,'AndroidManifest.xml'),'utf8');for(const name of ['activity-alias','example.app.Entry','example.app.Receiver','example.app.debuglog'])expect(xml).toContain(name);expect(readdirSync(join(dir,'java')).length).toBe(4);expect(xml.match(/android:exported="true"/g)?.length).toBe(xml.match(/android:exported="true" android:permission="android.permission.DUMP"/g)?.length);writeDebugExternal(dir,undefined,'dev.podjs.androidwatch.MainActivity');expect(readFileSync(join(dir,'AndroidManifest.xml'),'utf8')).not.toContain('provider');expect(readdirSync(join(dir,'java'))).toEqual([]);}finally{rmSync(dir,{recursive:true,force:true});}});
test('reject XML, code injection and ambiguous class declarations',()=>{for(const config of [{provider:{name:'bad"/>',authority:'x.y'}},{receiver:{name:'x.Y',actions:['bad<action>']}},{activities:[{name:'dev.podjs.debug.DebugEntry',action:'x.y'}]},{activities:[{name:'x.A',action:'x.y'},{name:'y.A',action:'x.z'}]}])expect(()=>validateDebugExternal(config)).toThrow();});
test('both hosts isolate generated sources and manifests from normal release',()=>{for(const target of ['android','wearos']){const source=readFileSync(join(import.meta.dir,`../platforms/${target}/app/build.gradle.kts`),'utf8');expect(source).toContain('sourceSets["debug"].manifest.srcFile');expect(source).toContain('if (providers.gradleProperty("podReleaseDebuggable").orElse("false").get() == "true")');expect(source).not.toContain('sourceSets["main"].java.srcDir');}});

test('result aliases keep a generic owner, timeout metadata and shell permission; release clears all sources',()=>{
 const dir=mkdtempSync(join(tmpdir(),'pod-result-'));try{
  writeDebugExternal(dir,{activities:[{name:'example.app.ResultEntry',action:'example.action.RESULT',result:true,timeoutMs:60000}]},'example.app.Main');
  const xml=readFileSync(join(dir,'AndroidManifest.xml'),'utf8');
  expect(xml).toContain('android:permission="android.permission.DUMP"');expect(xml).toContain('pod.debug.result" android:value="true"');expect(xml).toContain('pod.debug.timeout" android:value="60000"');
  expect(readFileSync(join(dir,'java/DebugEntry.java'),'utf8')).toContain('PodDebugExternal.ResultActivity');
  expect(readFileSync(join(dir,'java/PodDebugExternal.java'),'utf8')).toContain('owner.setResult(r.resultCode,r.resultData)');
  writeDebugExternal(dir,undefined,'example.app.Main');expect(readdirSync(join(dir,'java'))).toEqual([]);expect(readFileSync(join(dir,'AndroidManifest.xml'),'utf8')).not.toContain('activity');
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('result and deadline configuration is validated before source generation',()=>{
 for(const extra of [{result:'true'},{timeoutMs:8000},{result:true,timeoutMs:3999},{result:true,timeoutMs:600001},{result:true,timeoutMs:8000.5}])expect(()=>validateDebugExternal({activities:[{name:'example.app.Entry',action:'example.action.OPEN',...extra}]})).toThrow();
});
