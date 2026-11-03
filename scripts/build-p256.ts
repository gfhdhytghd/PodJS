// Rebuild the opt-in P-256 dependency from installed, pinned upstream packages.
// Usage: bun scripts/build-p256.ts /path/to/node_modules
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
const modules=process.argv[2];
if(!modules)throw Error('Usage: bun scripts/build-p256.ts /path/to/node_modules');
const root=resolve(modules);
for(const [name,version] of [['curves','1.9.7'],['hashes','1.8.0']]){
 const pkg=await Bun.file(join(root,'@noble',name,'package.json')).json();
 if(pkg.version!==version)throw Error(`Expected @noble/${name}@${version}`);
}
const temp=await mkdtemp(join(tmpdir(),'podjs-p256-'));
try{
 const entry=join(temp,'entry.ts'),encoder=resolve(import.meta.dir,'../packages/framework/src/vendor/noble-text-encoder.js'),output=resolve(import.meta.dir,'../packages/framework/src/vendor/noble-p256.js');
 await writeFile(entry,`export {p256} from ${JSON.stringify(join(root,'@noble/curves/p256.js'))};\n`);
 const child=Bun.spawn([process.execPath,join(root,'esbuild/bin/esbuild'),entry,'--bundle','--platform=browser','--format=esm','--minify',`--inject:${encoder}`,`--outfile=${output}`],{stdout:'inherit',stderr:'inherit'});
 if(await child.exited!==0)throw Error('P-256 bundle failed');
}finally{await rm(temp,{recursive:true,force:true});}
