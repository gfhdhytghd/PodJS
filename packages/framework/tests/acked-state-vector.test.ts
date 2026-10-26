import {describe,it,expect} from 'bun:test';
import {AckedStateVector,versionRootText,checkedStateRoots,checkedStateReceipts,type StateRoots,type StateReceipts} from '../src/acked-state-vector';
const a='a'.repeat(64),b='b'.repeat(64),c='c'.repeat(64);
function setup(saved:StateReceipts={}){let local:StateRoots={one:a,two:a},published:StateReceipts={},serial=0;const v=new AckedStateVector(['one','two'],async()=>({...local}),async()=>saved,async r=>{published={...published,...r};},()=>String(++serial));return {v,set:(r:StateRoots)=>{local=r;},published:()=>published};}
describe('bilateral applied state receipts',()=>{
 it('requires both owners and both durable receipt mirrors',async()=>{for(const known of [{},{one:{localRoot:b,remoteRoot:a}}]){const x=setup({one:{localRoot:a,remoteRoot:b}});expect((await x.v.summary({one:b},known as StateReceipts)).skipDomains).toEqual(Object.keys(known).length?['one']:[]);}const x=setup();expect((await x.v.summary({one:b},{one:{localRoot:b,remoteRoot:a}})).skipDomains).toEqual([]);});
 it('never infers peer equality from local stability',async()=>{const x=setup({one:{localRoot:a,remoteRoot:b}});expect((await x.v.summary({one:c},{one:{localRoot:b,remoteRoot:a}})).skipDomains).toEqual([]);});
 it('stages only successful domains and publishes only after session applied ACK',async()=>{const x=setup();const w=await x.v.summary({one:b,two:b},{});await expect(x.v.stage(w.windowId,{one:b},['one'])).rejects.toThrow('Unconfirmed');x.v.completed('one');expect((await x.v.stage(w.windowId,{one:b,two:b},['one'])).committedDomains).toEqual(['one']);expect(x.published()).toEqual({});await x.v.applied();expect(x.published()).toEqual({one:{localRoot:a,remoteRoot:b}});});
 it('does not sign post-summary changes as an already transmitted state',async()=>{const x=setup();const w=await x.v.summary({one:b,two:b},{});x.v.completed('one');x.v.completed('two');x.set({one:c,two:a});const r=await x.v.stage(w.windowId,{one:b,two:c},['one','two']);expect(r.committedDomains).toEqual([]);expect(r.retryDomains).toEqual(['one','two']);await x.v.applied();expect(x.published()).toEqual({});});
 it('revalidates local roots between commit and complete ACK',async()=>{const x=setup();const w=await x.v.summary({one:b,two:b},{});x.v.completed('one');x.v.completed('two');await x.v.stage(w.windowId,{one:b,two:b},['one','two']);x.set({one:c,two:a});await x.v.applied();expect(x.published()).toEqual({two:{localRoot:a,remoteRoot:b}});});
 it('abort, replacement window, lost ACK cannot publish receipts',async()=>{const x=setup();const w=await x.v.summary({one:b},{});x.v.completed('one');await x.v.stage(w.windowId,{one:b},['one']);x.v.abort();await x.v.applied();expect(x.published()).toEqual({});const n=await x.v.summary({one:b},{});await expect(x.v.stage(w.windowId,{one:b},[])).rejects.toThrow();expect(n.windowId).not.toBe(w.windowId);});
 it('reuses skipped unchanged domains without invoking an application export',async()=>{const x=setup({one:{localRoot:a,remoteRoot:b}});const w=await x.v.summary({one:b},{one:{localRoot:b,remoteRoot:a}});const r=await x.v.stage(w.windowId,{one:b},[]);expect(r.committedDomains).toEqual(['one']);await x.v.applied();expect(x.published().one).toEqual({localRoot:a,remoteRoot:b});});
 it('rejects unknown, duplicate, invalid roots and unsafe integer versions',async()=>{expect(()=>checkedStateRoots({no:a},['one'])).toThrow();expect(()=>checkedStateReceipts({one:{localRoot:a,remoteRoot:{toString:()=>b}}},['one'])).toThrow();for(const n of [-1,1.1,Number.MAX_SAFE_INTEGER+1])expect(()=>versionRootText('example.state.v1','one','0'.repeat(32),n)).toThrow();const x=setup();const w=await x.v.summary({one:b},{});x.v.completed('one');await expect(x.v.stage(w.windowId,{one:b},['one','one'])).rejects.toThrow();});
});

import {directoryWitness,directoryWitnessWindow} from '../src/directory-witness';
it('directory witnesses observe replacement tokens without reading file bodies',async()=>{
 let token='inode:1:ctime:1';const list=async()=>({entries:[{path:'assets/a',file:true,directory:false,size:100,modified:5,changeToken:token}]});
 const hash=async(s:string)=>new Bun.CryptoHasher('sha256').update(s).digest('hex');
 const before=await directoryWitness(['assets'],list,hash);expect(before).toBe(await directoryWitness(['assets'],list,hash));token='inode:2:ctime:2';expect(before).not.toBe(await directoryWitness(['assets'],list,hash));
 expect(await directoryWitness(['assets'],async()=>({entries:[]}),hash)).not.toBe(before);
 expect(await directoryWitness(['assets'],async()=>({entries:[{path:'assets/a',file:true,directory:false,size:100,modified:5}]}),hash)).toBeUndefined();
 expect(await directoryWitness(['assets'],async()=>({entries:[{path:'outside/a',file:true,directory:false,size:100,modified:5,changeToken:token}]}),hash)).toBeUndefined();
});

import {projectionVersionRootText,projectionProfileText,receiptIsCurrent,checkedProjectionVersions} from '../src/acked-state-vector';
it('versioned roots bind local encoder/schema/definition and hard receipt ages never slide',()=>{
 const version={encoder:1,schema:6,definition:'a'.repeat(64)},epoch='b'.repeat(32);
 expect(projectionVersionRootText('one',version,epoch,0)).toBe('watchrss.ble.domain-version.v2\none\n1\n6\n'+'a'.repeat(64)+'\n'+epoch+'\n0');
 expect(projectionProfileText('one',version)).toBe('watchrss.ble.projection.v1\none\n1\n6\n'+'a'.repeat(64));
 expect(receiptIsCurrent(1,604800000)).toBe(true);expect(receiptIsCurrent(1,604800001)).toBe(false);expect(receiptIsCurrent(2,1)).toBe(false);
 expect(()=>checkedProjectionVersions({},['one'],{one:'a'.repeat(64)})).toThrow();
});
it('publication identifies actual original-domain completion separately from an unchanged SKIP',async()=>{
 const root='a'.repeat(64),peer='b'.repeat(64);let publication:unknown;
 const v=new AckedStateVector(['one','two'],async()=>({one:root,two:root}),async()=>({one:{localRoot:root,remoteRoot:peer}}),async(_rows,context)=>{publication=context;},()=> 'window');
 const s=await v.summary({one:peer,two:peer},{one:{localRoot:peer,remoteRoot:root}});v.completed('two');await v.stage(s.windowId,{one:peer,two:peer},['two']);await v.applied();expect(publication).toEqual({completedDomains:['two'],skippedDomains:['one']});
});

it('directory witness bounds independent metadata probes, drains failures and rejects changed files',async()=>{
 let active=0,max=0,changed='',failure='';
 const names=Array.from({length:25},(_,i)=>'assets/f'+i);
 const stat=async(path:string)=>{active++;max=Math.max(max,active);try{await Promise.resolve();if(path===failure)throw Error('stat failed');return {exists:true,file:path!=='assets',directory:path==='assets',size:10,modified:1,changeToken:path===changed?'changed':'stable'};}finally{active--;}};
 const w=await directoryWitnessWindow(['assets'],async()=>({entries:names.map(path=>({path,file:true,directory:false,size:10,modified:1,changeToken:'stable'}))}),stat,async()=> 'digest');
 expect(w).toBeDefined();expect(max).toBe(8);expect(active).toBe(0);expect(await w!.valid()).toBe(true);
 changed=names[20]!;expect(await w!.valid()).toBe(false);changed='';failure=names[3]!;await expect(w!.valid()).rejects.toThrow('stat failed');expect(active).toBe(0);
});
