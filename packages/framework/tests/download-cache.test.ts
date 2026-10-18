import {test,expect} from 'bun:test';
import {createHash} from 'node:crypto';
import {DownloadCache} from '../src/download-cache.ts';
import type {CachePorts,CacheEntry} from '../src/cache.ts';
const flush=async()=>{for(let i=0;i<40;i++)await Promise.resolve();};
function fixture(maxEntries=64) {
 const files=new Map<string,CacheEntry>();let time=0;
 const calls:{url:string;headers:Record<string,string>;path:string;cancelled:boolean;finish(size?:number):void}[]=[];
 const ports:CachePorts={digest:async s=>createHash('sha256').update(s).digest('hex'),list:async()=>({entries:[...files.values()]}),stat:async p=>({exists:files.has(p),file:files.has(p),size:files.get(p)?.size??0}),remove:async p=>{files.delete(p);},touch:async p=>{const f=files.get(p);if(f)f.modified=++time;},download:(url,path,headers)=>{
  let done!:()=>void,fail!:(e:unknown)=>void;const result=new Promise<void>((y,n)=>{done=y;fail=n;});
  const call={url,path,headers,cancelled:false,finish(size=65536){files.set(path,{path,file:true,directory:false,size,modified:++time});done();}};
  calls.push(call);return {result,cancel(){call.cancelled=true;fail(Error('cancelled'));}};
 }};
 const cache=new DownloadCache({root:'managed-cache/video',minBytes:65536,maxBytes:1048576,maxEntries,ports});
 return {cache,ports,files,calls};
}
test('whole files retain headers and stable identities across changed signed URLs and restart',async()=>{
 const f=fixture();let resolutions=0;
 const a=f.cache.download('item',async()=>{resolutions++;return {url:'https://video/a?signature=old',headers:{Referer:'https://origin/',Cookie:'fixture','User-Agent':'fixture'}};});
 await flush();expect(f.calls[0]!.headers).toEqual({Referer:'https://origin/',Cookie:'fixture','User-Agent':'fixture'});f.calls[0]!.finish();const path=await a.result;
 const restored=new DownloadCache({root:'managed-cache/video',minBytes:65536,maxBytes:1048576,maxEntries:64,ports:f.ports});
 expect(await restored.download('item',async()=>{resolutions++;return {url:'https://video/changed',headers:{}};}).result).toBe(path);
 expect(resolutions).toBe(1);expect(f.calls).toHaveLength(1);expect(path).not.toContain('item');
});
test('one worker resolves URLs at dequeue time; lookup does not queue behind downloads',async()=>{
 const f=fixture();let second=0;
 const a=f.cache.download('a',async()=>({url:'https://a',headers:{}}));
 const b=f.cache.download('b',async()=>{second++;return {url:'https://b',headers:{}};});
 await flush();expect(f.calls).toHaveLength(1);expect(second).toBe(0);expect(await f.cache.lookup('unrelated')).toBeUndefined();
 f.calls[0]!.finish();await a.result;await flush();expect(second).toBe(1);expect(f.calls).toHaveLength(2);f.calls[1]!.finish();await b.result;
});
test('undersized files never become hits; invalidate removes a completed file',async()=>{
 const f=fixture();const a=f.cache.download('a',async()=>({url:'https://a',headers:{}}));await flush();f.calls[0]!.finish(65535);expect(await a.result).toBeUndefined();expect(f.files.size).toBe(0);
 const b=f.cache.download('a',async()=>({url:'https://a',headers:{}}));await flush();f.calls[1]!.finish();expect(await b.result).toBeDefined();await f.cache.invalidate('a');expect(await f.cache.lookup('a')).toBeUndefined();
});
test('cancel active and queued jobs, then clear drains writers without republishing',async()=>{
 const f=fixture();const a=f.cache.download('a',async()=>({url:'https://a',headers:{}})),b=f.cache.download('b',async()=>({url:'https://b',headers:{}}));
 await flush();b.cancel();a.cancel();await Promise.all([a.result,b.result]);expect(f.calls).toHaveLength(1);expect(f.calls[0]!.cancelled).toBe(true);expect(f.files.size).toBe(0);
 const c=f.cache.download('c',async()=>({url:'https://c',headers:{}}));await flush();await f.cache.clear();expect(await c.result).toBeUndefined();expect(f.cache.pins().size).toBe(0);f.cache.cleared();
});
test('entry LRU limit keeps a playback lease and leaves foreign files untouched',async()=>{
 const f=fixture(2);let release=()=>{};
 for(const id of ['a','b','c']){const op=f.cache.download(id,async()=>({url:'https://'+id,headers:{}}));await flush();f.calls.at(-1)!.finish();const path=await op.result;if(id==='a')release=f.cache.pin(path!);}
 expect(await f.cache.lookup('a')).toBeDefined();expect(await f.cache.lookup('b')).toBeUndefined();expect(await f.cache.lookup('c')).toBeDefined();
 f.files.set('managed-cache/video/user.txt',{path:'managed-cache/video/user.txt',file:true,directory:false,size:9,modified:0});await f.cache.trim();expect(f.files.has('managed-cache/video/user.txt')).toBe(true);release();
});
test('a replacement window can immediately reacquire a cancelled identity',async()=>{
 const f=fixture();const first=f.cache.download('a',async()=>({url:'https://old',headers:{}}));await flush();first.cancel();
 const second=f.cache.download('a',async()=>({url:'https://fresh',headers:{}}));await first.result;await flush();
 expect(f.calls).toHaveLength(2);expect(f.calls[1]!.url).toBe('https://fresh');f.calls[1]!.finish();expect(await second.result).toBeDefined();
});
test('download failure diagnostics never expose arbitrary URL or credential messages',async()=>{
 const {downloadFailureCategory}=await import('../src/download-cache.ts');
 expect(downloadFailureCategory(Error('http_status:403 https://fixture/?secret=value'))).toBe('http_403');
 expect(downloadFailureCategory(Error('invalid_max_bytes'))).toBe('invalid_max_bytes');
 expect(downloadFailureCategory(Error('secret=fixture https://private/'))).toBe('transport_error');
});
