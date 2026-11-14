import {readFileSync,writeFileSync,renameSync,openSync,fsyncSync,closeSync,appendFileSync,statSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {JsonDeltaJournal} from '../../src/json-delta-journal';
const [root,checkpoint,host]=process.argv.slice(2),hash=(s:string)=>createHash('sha256').update(s).digest('hex');
const halt=async()=>{process.stdout.write('checkpoint\n');await new Promise(()=>{setInterval(()=>{},1000);});};
const io={yieldTurn:async()=>{},read:async(p:string)=>{try{return readFileSync(join(root,p),'utf8');}catch(e:any){if(e.code==='ENOENT')return;throw e;}},
 ...(host==='host'?{fingerprint:async(_p:string,raw:string)=>'host:'+hash(raw)}:{}),
 replace:async(p:string,s:string)=>{const phase=p==='state'?'base':'delta';if(checkpoint==='before-'+phase)await halt();const temp=join(root,p+'.tmp');writeFileSync(temp,s);const fd=openSync(temp,'r');fsyncSync(fd);closeSync(fd);renameSync(temp,join(root,p));if(checkpoint==='after-'+phase)await halt();},
 append:async(p:string,s:string,n:number)=>{let length=0;try{length=statSync(join(root,p)).size;}catch{}if(length!==n)throw Error('offset');appendFileSync(join(root,p),s);},
};
await new JsonDeltaJournal(io,'state',['rows'],v=>v,v=>v,450).commit({rows:{seed:'x'.repeat(800)}});
throw Error('checkpoint not reached');
