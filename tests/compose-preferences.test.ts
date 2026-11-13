import {test,expect} from 'bun:test';
import {mkdtempSync,mkdirSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';

test('Compose inventory gate rejects a newly introduced writer and a changed writer',()=>{
 const root=mkdtempSync(join(tmpdir(),'compose-contract-'));
 try{
  const writer=join(root,'app/src/main/new/Writer.kt');mkdirSync(resolve(writer,'..'),{recursive:true});
  writeFileSync(writer,'context.getSharedPreferences("new_store",0)');
  const script=resolve('scripts/verify-compose-preferences.py');
  const program=`import importlib.util,sys,hashlib\nfrom pathlib import Path\ns=importlib.util.spec_from_file_location('contract',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)\nr=Path(sys.argv[2]);p=r/'app/src/main/new/Writer.kt'\nc={'sourceFiles':{},'keys':[]}\nassert any('new preference writer' in x for x in m.verify(r,c))\nc['sourceFiles'][str(p.relative_to(r))]=hashlib.sha256(p.read_bytes()).hexdigest()\nassert not m.verify(r,c)\np.write_text(p.read_text()+'; preferences.edit().putString("new_key","value")')\nassert any('unreviewed preference writer change' in x for x in m.verify(r,c))\n`;
  const result=Bun.spawnSync(['python','-B','-c',program,script,root]);
  expect(result.exitCode).toBe(0);
 }finally{rmSync(root,{recursive:true,force:true});}
});

test.skipIf(!process.env.COMPOSE_SOURCE_ROOT)('frozen Compose writers all match the reviewed key catalogue',()=>{
 const result=Bun.spawnSync(['python','-B','scripts/verify-compose-preferences.py','--source',process.env.COMPOSE_SOURCE_ROOT!]);
 expect(result.stdout.toString()+result.stderr.toString()).toContain('"pass": true');expect(result.exitCode).toBe(0);
});
