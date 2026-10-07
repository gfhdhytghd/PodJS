import {expect, test} from 'bun:test';
import {mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {manifestWorkManagerTakeover, validateWorkManagerTakeover, type WorkManagerTakeoverConfig} from '../packages/cli/src/workmanager-takeover.ts';
import {resolvePodProject} from '../packages/cli/src/project.ts';
import {POD_TARGETS} from '../packages/framework/src/targets.ts';

const config: WorkManagerTakeoverConfig={nativeAppId:'com.example.old',workerClasses:['com.example.old.SyncWorker'],uniqueWorkNames:['old-sync'],tags:[]};
test('legacy configuration survives project resolution without opening signing credentials', () => {
  const root=mkdtempSync(join(tmpdir(),'pod-legacy-work-'));
  writeFileSync(join(root,'main.tsx'),'export default 1');
  writeFileSync(join(root,'font.ttf'),'font');
  writeFileSync(join(root,'pod.config.json'),JSON.stringify({entry:'main.tsx',appId:'com.example.old',name:'test',versionName:'1',versionCode:2,capabilities:[],android:{workManagerTakeover:config,signing:{keystoreProperties:'/must-not-read/credentials'}}}));
  expect(resolvePodProject(root,POD_TARGETS,{fontRegular:'font.ttf',fontBold:'font.ttf',outputRoot:root}).config.android?.workManagerTakeover).toEqual(config);
  const parsed=validateWorkManagerTakeover(config);
  config.tags.push('legacy-tag');
  expect(parsed.tags).toEqual([]);
  config.tags.pop();
});
test('rejects malformed identities, unbounded lists and PodJS-owned work', () => {
  for(const invalid of [null,[],{}, {...config,nativeAppId:'bad/id'}, {...config,nativeAppId:'a.B$'}, {...config,workerClasses:[]}, {...config,workerClasses:['dev.podjs.runtime.PodCloudWorker']}, {...config,uniqueWorkNames:['podjs-cloud-sync-6h']}, {...config,tags:['podjs:notification']}, {...config,workerClasses:['a.B','a.B']}, {...config,tags:['bad\nvalue']}, {...config,tags:Array.from({length:65},(_,i)=>`tag${i}`)}, {...config,uniqueWorkNames:[],tags:[]}, {...config,unknown:true}])
    expect(()=>validateWorkManagerTakeover(invalid)).toThrow('workManagerTakeover');
});
test('only the package replacing the native app carries the takeover manifest entry', () => {
  expect(manifestWorkManagerTakeover('com.example.old',config)).toEqual(config);
  expect(manifestWorkManagerTakeover('com.example.old.podjs',config)).toBeUndefined();
  expect(manifestWorkManagerTakeover('com.example.old',undefined)).toBeUndefined();
});
