import {test,expect} from 'bun:test';
import {createHash} from 'node:crypto';
import {JsonDeltaJournal} from '../src/json-delta-journal';
const hash=async(b:Uint8Array)=>createHash('sha256').update(b).digest('hex');
const digest=(s:string)=>createHash('sha256').update(s).digest('hex');
function fixture(count=80,body=4096){
 const base=JSON.stringify({rows:{initial:'base'},cursor:'0'}),id=digest(base);let delta='';
 for(let i=1;i<=count;i++){const edits=[{field:'rows',key:String(i),value:'文'.repeat(body)},{field:'cursor',value:String(i)}];delta+=JSON.stringify({base:id,sequence:i,edits,digest:digest(JSON.stringify([id,i,edits]))})+'\n';}
 return new Map([['state',base],['state.delta',delta]]);
}
function port(files:Map<string,string>){let reads=0,yields=0;return {get reads(){return reads;},get yields(){return yields;},sha256:hash,
 read:async(p:string)=>{reads++;return files.get(p);},
 async *chunks(p:string){const text=files.get(p)??'';for(let i=0;i<text.length;i+=1024)yield text.slice(i,i+1024);},
 replace:async(p:string,s:string)=>{files.set(p,s);},
 append:async(p:string,s:string,n:number)=>{expect(Buffer.byteLength(files.get(p)??'')).toBe(n);files.set(p,(files.get(p)??'')+s);},
 yieldTurn:async()=>{yields++;await new Promise(r=>setTimeout(r,0));},
 };}
test('concurrent loads share one promise and bounded replay never reads the complete delta',async()=>{
 const files=fixture(),io=port(files),j=new JsonDeltaJournal(io,'state',['rows']);const a=j.load(),b=j.load();expect(a).toBe(b);expect(await a).toEqual(await b);expect(io.reads).toBe(1);expect(io.yields).toBeGreaterThan(3);
});
test('read-only replay compacts at frame checkpoint and retains all verified values',async()=>{
 const files=fixture(2048,1),j=new JsonDeltaJournal(port(files),'state',['rows']);const v=await j.load();expect(v.cursor).toBe('2048');expect(files.get('state.delta')).toBe('');expect(await new JsonDeltaJournal(port(files),'state',['rows']).load()).toEqual(v);
});
test('read-only replay compacts at byte checkpoint',async()=>{
 const files=fixture(10,200),j=new JsonDeltaJournal(port(files),'state',['rows'],v=>v,v=>v,2000);const v=await j.load();expect(files.get('state.delta')).toBe('');expect(JSON.parse(files.get('state')!)).toEqual(v);
});
// Legacy replay oracle frozen from 9aac10e, including unterminated-frame repair.
async function legacy(files:Map<string,string>){const raw=files.get('state')??'{}',base=digest(raw);let value:any={};try{const v=JSON.parse(raw);if(v&&typeof v==='object'&&!Array.isArray(v))value=v;}catch{}let sequence=0,valid='';const tail=files.get('state.delta')??'';
 for(const line of tail.split('\n')){if(!line)break;try{const f=JSON.parse(line);if(f.base!==base||f.sequence!==sequence+1||!Array.isArray(f.edits)||f.edits.length>100000||digest(JSON.stringify([f.base,f.sequence,f.edits]))!==f.digest)break;
 for(const e of f.edits)if(!e||typeof e.field!=='string'||e.field.length>1024||e.key!==undefined&&(typeof e.key!=='string'||e.key.length>2048))throw Error('invalid');
 for(const e of f.edits){const target=e.key===undefined?value:(Object.hasOwn(value,e.field)&&value[e.field]&&typeof value[e.field]==='object'?value[e.field]:(value[e.field]={}));if(e.remove)delete target[e.key??e.field];else Object.defineProperty(target,e.key??e.field,{value:e.value,writable:true,enumerable:true,configurable:true});}sequence++;valid+=line+'\n';}catch{break;}}
 if(Buffer.byteLength(valid)!==Buffer.byteLength(tail))files.set('state.delta',valid);return value;
}
test('streamed and legacy replay agree for corruption, truncation, blank lines, unicode and large input',async()=>{
 const original=fixture(96,2048),tail=original.get('state.delta')!;
 const cases=[tail,tail+'{bad',tail.slice(0,-25),tail.slice(0,-1),tail+'\nignored',tail.replace('文','改'),'\n'+tail];
 for(const text of cases){const a=new Map(original),b=new Map(original);a.set('state.delta',text);b.set('state.delta',text);const expected=await legacy(a),actual=await new JsonDeltaJournal(port(b),'state',['rows']).load();expect(actual).toEqual(expected);expect(b.get('state.delta')).toBe(a.get('state.delta'));}
 const large=fixture(512,11500);large.set('state',JSON.stringify({rows:{initial:'x'.repeat(15*1024*1024)},cursor:'0'}));const id=digest(large.get('state')!);large.set('state.delta',large.get('state.delta')!.split('\n').filter(Boolean).map(line=>{const f=JSON.parse(line);f.base=id;f.digest=digest(JSON.stringify([id,f.sequence,f.edits]));return JSON.stringify(f);}).join('\n')+'\n');
 const a=new Map(large),b=new Map(large);expect(Buffer.byteLength(a.get('state.delta')!)).toBeGreaterThan(17*1024*1024-1024*1024);expect(await new JsonDeltaJournal(port(b),'state',['rows']).load()).toEqual(await legacy(a));
});
