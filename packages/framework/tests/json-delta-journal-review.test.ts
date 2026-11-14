import {test,expect} from 'bun:test';
import {createHash} from 'node:crypto';
import {JsonDeltaJournal,type JsonJournalFiles} from '../src/json-delta-journal';
const digest=(s:string)=>createHash('sha256').update(s).digest('hex');
function memory(initial={rows:{seed:'x'.repeat(800)}}){
 const files=new Map<string,string>([['state',JSON.stringify(initial)]]);
 const io:JsonJournalFiles={read:async p=>files.get(p),yieldTurn:async()=>{},replace:async(p,s)=>{files.set(p,s);},append:async(p,s,n)=>{expect(Buffer.byteLength(files.get(p)??'')).toBe(n);files.set(p,(files.get(p)??'')+s);}};
 const make=(port=io,maximum=450)=>new JsonDeltaJournal(port,'state',['rows'],v=>v,v=>v,maximum);
 return {files,io,make,initial};
}
test('compacting a revert never certifies stale frames after base publication',async()=>{
 for(const hostFingerprint of [false,true]){
  const f=memory(),io={...f.io,...(hostFingerprint?{fingerprint:async(_p:string,raw:string)=>'host:'+digest(raw)}:{})};
  let stop=false;const port={...io,replace:async(p:string,s:string)=>{if(stop&&p==='state.delta')throw Error('killed after publication');await io.replace(p,s);}};
  const j=f.make(port);await j.load();await j.commit({rows:{changed:'old'}});stop=true;
  await expect(j.commit(f.initial)).rejects.toThrow('killed after publication');stop=false;
  expect(await f.make(io).load()).toEqual(f.initial);
 }
});
test('digest IO failure rejects a shared load without truncating valid frames, then retries',async()=>{
 const f=memory(),writer=f.make();await writer.load();await writer.commit({rows:{changed:'durable'}});const original=f.files.get('state.delta');let fail=true;
 const io={...f.io,sha256:async(bytes:Uint8Array)=>{const text=Buffer.from(bytes).toString();if(fail&&text.startsWith('[')){fail=false;throw Error('hash IO unavailable');}return digest(text);}};
 const j=f.make(io),a=j.load(),b=j.load();expect(a).toBe(b);await expect(a).rejects.toThrow('hash IO unavailable');expect(f.files.get('state.delta')).toBe(original);expect(await j.load()).toEqual({rows:{changed:'durable'}});
});
test('a torn append on the same instance reopens and repairs before the next commit',async()=>{
 const f=memory();let torn=true;const io={...f.io,append:async(p:string,s:string,n:number)=>{if(torn){torn=false;f.files.set(p,s.slice(0,40));throw Error('torn append');}await f.io.append(p,s,n);}};
 const j=f.make(io);await j.load();await expect(j.commit({rows:{a:'first'}})).rejects.toThrow('torn append');await j.commit({rows:{b:'retry'}});expect(await f.make().load()).toEqual({rows:{b:'retry'}});
});
test('queued commits serialize sequence and recover after an uncertain complete append',async()=>{
 const f=memory();let uncertain=true,inFlight=0,max=0;const io={...f.io,append:async(p:string,s:string,n:number)=>{max=Math.max(max,++inFlight);try{await Promise.resolve();await f.io.append(p,s,n);if(uncertain){uncertain=false;throw Error('reply lost');}}finally{inFlight--;}}};
 const j=f.make(io,100000),first=j.commit({rows:{a:'one'}}),second=j.commit({rows:{b:'two'}});await expect(first).rejects.toThrow('reply lost');await second;expect(max).toBe(1);expect(await f.make(f.io,100000).load()).toEqual({rows:{b:'two'}});
});
test('stream iterator closes on a corrupt frame and retries a read failure without publishing repair',async()=>{
 const f=memory();const j=f.make();await j.load();await j.commit({rows:{a:'durable'}});const original=f.files.get('state.delta')!;let closed=0,fail=true,repairs=0;
 const io={...f.io,async *chunks(){try{yield original;if(fail){fail=false;throw Error('chunk IO');}yield '{bad';}finally{closed++;}},repair:async(p:string,n:number)=>{repairs++;f.files.set(p,Buffer.from(f.files.get(p)!).subarray(0,n).toString());}};
 const reader=f.make(io);await expect(reader.load()).rejects.toThrow('chunk IO');expect(repairs).toBe(0);expect(await reader.load()).toEqual({rows:{a:'durable'}});expect(closed).toBe(2);expect(repairs).toBe(1);
});

test('SIGKILL at each atomic compaction publication boundary preserves the committed state',async()=>{
 const {mkdtempSync,writeFileSync,readFileSync,rmSync}=await import('node:fs');const {tmpdir}=await import('node:os');const {join}=await import('node:path');
 for(const prefix of ['', '\n'])for(const host of ['', 'host'])for(const phase of ['before-base','after-base','before-delta','after-delta']){
  const root=mkdtempSync(join(tmpdir(),'journal-kill-'));let child:ReturnType<typeof Bun.spawn>|undefined;
  try{
   const initial={rows:{seed:'x'.repeat(800)}},raw=prefix+JSON.stringify(initial),id=(host?'host:':'')+digest(raw),edits=[{field:'rows',key:'seed',remove:true},{field:'rows',key:'changed',value:'old'}];
   writeFileSync(join(root,'state'),raw);writeFileSync(join(root,'state.delta'),JSON.stringify({base:id,sequence:1,edits,digest:digest(JSON.stringify([id,1,edits]))})+'\n');
   child=Bun.spawn([process.execPath,join(import.meta.dir,'fixtures/json-journal-crash.ts'),root,phase,host],{stdout:'pipe',stderr:'pipe'});
   const reader=(child.stdout as ReadableStream<Uint8Array>).getReader();const notice=await Promise.race([reader.read(),new Promise<never>((_,reject)=>{const timer=setTimeout(()=>reject(Error('child checkpoint timeout')),5000);timer.unref();})]);expect(Buffer.from(notice.value!).toString()).toBe('checkpoint\n');child.kill('SIGKILL');await child.exited;reader.releaseLock();
   const io={read:async(p:string)=>readFileSync(join(root,p),'utf8'),replace:async(p:string,s:string)=>{writeFileSync(join(root,p),s);},append:async()=>{throw Error('no append');},yieldTurn:async()=>{},...(host?{fingerprint:async(_p:string,s:string)=>'host:'+digest(s)}:{})};
   const fresh=new JsonDeltaJournal(io,'state',['rows']);expect(await fresh.load()).toEqual(phase==='before-base'?{rows:{changed:'old'}}:initial);
  }finally{if(child&&child.exitCode===null){child.kill('SIGKILL');await child.exited;}rmSync(root,{recursive:true,force:true});}
 }
});

test('independent 259042d oracle agrees across scalar chunk boundaries and corrupt frame variants',async()=>{
 const {JsonDeltaJournal:Legacy}=await import('./fixtures/json-journal-legacy');
 for(let seed=0;seed<40;seed++){
  const raw=seed%7===0?'{malformed':JSON.stringify({rows:JSON.parse('{"__proto__":"data","汉😀":"base"}'),cursor:'0'}),base=digest(raw),lines:string[]=[];
  for(let i=1;i<=5;i++){
   const edits:any[]=[{field:'rows',key:'汉😀'+i,value:'\n文😀'.repeat(seed+1)},{field:'cursor',value:String(i)}];
   if(seed%5===0&&i===3)edits.push({field:9,value:'invalid'});
   lines.push(JSON.stringify({base,sequence:i,edits,digest:digest(JSON.stringify([base,i,edits]))}));
  }
  const good=lines.join('\n')+'\n';
  const variants=[good,good.slice(0,-1),good.slice(0,-19),good+'{torn',good.replace('文','改'),good.replace('"sequence":3','"sequence":4'),good.replace('"base":"'+base+'"','"base":"other"'),good.replace(lines[2],''),'\n'+good,lines[0]+'\n'+JSON.stringify({base,sequence:2,edits:[],digest:'bad'})];
  for(const tail of variants){
   const a=new Map([['state',raw],['state.delta',tail]]),b=new Map(a);
   const port=(files:Map<string,string>):JsonJournalFiles=>({read:async p=>files.get(p),replace:async(p,s)=>{files.set(p,s);},append:async()=>{throw Error('read only');},yieldTurn:async()=>{}});
   const io={...port(b),async *chunks(p:string){let part='';for(const scalar of b.get(p)??''){part+=scalar;if(part.length>=1+seed%9){yield part;part='';}}if(part)yield part;}};
   expect(await new JsonDeltaJournal(io,'state',['rows'],v=>v,v=>v,1e9).load()).toEqual(await new Legacy(port(a),'state',['rows'],v=>v,v=>v,1e9).load());expect(b.get('state.delta')).toBe(a.get('state.delta'));
  }
 }
});

test('historical retry authenticates each frame, closes its iterator and canonicalizes before append',async()=>{
 for(const mode of ['valid','bad-digest','hash-failure','read-failure','publish-failure','invalid-json'] as const){
  const f=memory({rows:{seed:''}}),writer=f.make(f.io,100000);await writer.load();
  await writer.commit({rows:{first:'汉😀'}});await writer.commit({rows:{first:'汉😀',problem:'/'}});await writer.commit({rows:{first:'汉😀',problem:'/',after:'last valid'}});
  const original=f.files.get('state.delta')!,legacy=original.trimEnd().split('\n');
  f.files.set('state.delta',mode==='invalid-json'?original.replace('"value":"/"','"value":?'):original.replace('"value":"/"','"value":"��"'));
  if(mode==='bad-digest')legacy[1]=legacy[1]!.replace(/"digest":"[a-f0-9]+"/,'"digest":"'+'0'.repeat(64)+'"');
  const before=new Map(f.files);let closed=0,reads=0,fail=true,failedHash=false;
  const io={...f.io,async *chunks(p:string){yield f.files.get(p)??'';},async *legacyLines(){try{for(const line of legacy){reads++;if(mode==='read-failure'&&fail)throw Error('legacy IO');yield line;}}finally{closed++;}},
   sha256:async(bytes:Uint8Array)=>{const s=Buffer.from(bytes).toString();if(mode==='hash-failure'&&fail&&s.includes('"value":"/"')){failedHash=true;throw Error('legacy digest IO');}return digest(s);},
   replace:async(p:string,s:string)=>{await f.io.replace(p,s);if(mode==='publish-failure'&&fail&&p==='state')throw Error('publish IO');}};
  const journal=f.make(io,100000);
  if(['hash-failure','read-failure','publish-failure'].includes(mode)){
   await expect(journal.load()).rejects.toThrow('IO');expect(closed).toBe(1);
   if(mode!=='publish-failure')expect(f.files).toEqual(before);
   if(mode==='hash-failure')expect(failedHash).toBe(true);
   fail=false;
  }
  const expected=mode==='bad-digest'?{rows:{first:'汉😀'}}:{rows:{first:'汉😀',problem:'/',after:'last valid'}};
  expect(await journal.load()).toEqual(expected);expect(closed).toBeGreaterThan(0);
  if(mode!=='bad-digest')expect(f.files.get('state.delta')).toBe('');
  await journal.commit({rows:{...expected.rows,next:'appended'}});
  expect(await f.make(f.io,100000).load()).toEqual({rows:{...expected.rows,next:'appended'}});
  expect(reads).toBeGreaterThan(0);
 }
});
test('primary authenticated frames never consult the optional historical decoder',async()=>{
 const f=memory(),writer=f.make(f.io,100000);await writer.load();await writer.commit({rows:{literal:'�'}});
 const before=new Map(f.files);let reads=0;
 const io={...f.io,async *legacyLines(){reads++;throw Error('must stay lazy');yield '';}};
 expect(await f.make(io,100000).load()).toEqual({rows:{literal:'�'}});expect(reads).toBe(0);expect(f.files).toEqual(before);
});
