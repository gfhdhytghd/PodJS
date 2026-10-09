import {expect,test} from 'bun:test';
import {createRestorableStack,createRouteMemoryAllocator,type StackMemory} from '../src/restorable-stack.ts';
import {createHostLogFile} from '../src/log-file.ts';
import {createHash} from 'node:crypto';

test('generic stack uses caller codecs, preserves owners, bounds prefixes and allocates identities after restoration',async()=>{
 type Route={screen:string;memory?:StackMemory};type Descriptor={screen:string;id:string;ui:Record<string,unknown>};
 const ids=createRouteMemoryAllocator<Route>();
 const stack=createRestorableStack<Route,Descriptor,boolean>({namespace:'example.task',maxEntries:3,maxSavedBytes:200,maxRetainedBytes:80,captureContext:true,memory:ids.memory,
  encode:(e,m)=>({screen:e.screen,id:m.id,ui:m.saved}),decode:(raw,retained,debug)=>{
   const v=raw as Descriptor;if(!v||!['root','next','debug'].includes(v.screen)||typeof v.id!=='string'||(!debug&&v.screen==='debug'))return null;
   return {screen:v.screen,memory:{id:v.id,saved:v.ui,retained:(retained??{}) as Record<string,unknown>}};
  },retained:m=>m.retained,isRoot:e=>e.screen==='root',restoredIds:ids.observe});
 const entries:Route[]=[{screen:'root'},{screen:'next'},{screen:'debug'}];ids.memory(entries[1]!).retained.draft='edited';
 const state=stack.capture({saved:{other:7},retained:{other:8}},entries);
 expect(state.saved.other).toBe(7);expect(state.retained.other).toBe(8);
 expect(stack.restore(state,false)?.map(e=>e.screen)).toEqual(['root','next']);
 expect(stack.restore(state,true)?.[1]?.memory?.retained).toEqual({draft:'edited'});
 expect(stack.restore({saved:state.saved,retained:{}},true)?.[1]?.memory?.retained).toEqual({});
 expect(ids.memory({screen:'next'}).id).toBe('route-4');
 const bad=structuredClone(state);(bad.saved['example.task'] as any).entries[1].id=(bad.saved['example.task'] as any).entries[0].id;
 expect(stack.restore(bad,true)).toHaveLength(1);
 expect(stack.restore({saved:{'example.task':{version:2,entries:[]}},retained:{}},true)).toBeNull();
 expect(stack.restore(stack.capture({saved:{},retained:{}},[{screen:'next'}]),true)).toBeNull();
 const large:Route={screen:'next',memory:{id:'route-100',saved:{large:'汉'.repeat(200)},retained:{}}};
 expect(stack.restore(stack.capture({saved:{},retained:{}},[entries[0]!,large]),true)).toHaveLength(1);
 const seen:string[]=[];expect(await stack.resolve(entries,e=>{seen.push(e.screen);return e.screen!=='next';},()=>({screen:'fallback'}))).toEqual([entries[0]!]);expect(seen).toEqual(['root','next']);
 expect(await stack.resolve(entries,()=>false,()=>({screen:'fallback'}))).toEqual([{screen:'fallback'}]);
 expect(()=>createRestorableStack({...({} as any),namespace:'x',maxEntries:0})).toThrow(RangeError);
});

test('generic log file keeps UTF-8 and caller chunk/policy values through atomic replace and rejected commit cleanup',async()=>{
 const files=new Map<string,Buffer>();const events:any[]=[];let seq=0,fail=false,corrupt=false;
 const ports:any={
  async stat(p:string){return {exists:files.has(p),size:files.get(p)?.length??0};},
  async read(p:string,offset:number,max:number){const b=files.get(p)!;events.push(['read',offset,max]);return {offset:corrupt?offset+1:offset,size:b.length,eof:offset+max>=b.length,dataBase64:b.subarray(offset,offset+max).toString('base64')};},
  async write(p:string,s:string){files.set(p,Buffer.from(s,'base64'));},
  async append(p:string,s:string,offset:number){const b=Buffer.from(s,'base64'),old=files.get(p)!;expect(old.length).toBe(offset);expect(b.length).toBeLessThanOrEqual(4);files.set(p,Buffer.concat([old,b]));return {size:offset+b.length};},
  async createTemp(dir:string){events.push(['temp',dir]);const path=dir+'/'+ ++seq;files.set(path,Buffer.alloc(0));return {path};},
  async hash(p:string){const b=files.get(p)!;return {size:b.length,sha256:createHash('sha256').update(b).digest('hex')};},
  async commit(p:string,target:string,size:number,hash:string){events.push(['commit',target]);expect(await ports.hash(p)).toEqual({size,sha256:hash});if(fail)throw Error('commit failure');files.set(target,files.get(p)!);files.delete(p);},
  async remove(p:string){events.push(['remove',p]);files.delete(p);},
 };
 const log=createHostLogFile({path:'custom.txt',temporaryDirectory:'scratch',chunkBytes:4},ports);
 expect(await log.read()).toBeNull();expect(await log.size()).toBe(0);
 const value='甲😀\0乙\n';await log.append(value,0);expect(await log.read()).toBe(value);expect(await log.size()).toBe(Buffer.byteLength(value));
 await log.replace('new');expect(await log.read()).toBe('new');expect(events).toContainEqual(['temp','scratch']);
 fail=true;await expect(log.replace('bad')).rejects.toThrow('commit failure');expect(await log.read()).toBe('new');expect(files.size).toBe(1);
 corrupt=true;await expect(log.read()).rejects.toThrow('incomplete_log_read');
 for(const chunkBytes of [0,65537,NaN])expect(()=>createHostLogFile({path:'x',temporaryDirectory:'x',chunkBytes},ports)).toThrow(RangeError);
});
