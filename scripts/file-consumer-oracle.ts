/** Production WatchRSS readers over the complete synthetic JVM migration output. */
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {gzipSync} from 'node:zlib';
import {Database} from 'bun:sqlite';
import {resolve} from 'node:path';
const app=resolve(process.env.WATCHRSS_APP_SOURCE??'/home/wilf/data/OPPO_Develop/PodJS_WatchRSS');
const root=process.argv[2];if(!root)throw Error('synthetic fixture directory required');
const values=await Bun.file(resolve(root,'podjs/podjs-kv.json')).json();
const {ReaderPresetStore,availableReaderPresets}=await import(`${app}/src/reader/preset-store.ts`);
const {parseReaderManifest}=await import(`${app}/src/sync/reader-manifest.ts`);
const reader=new ReaderPresetStore({get:(k:string)=>values[k],set:()=>{throw Error('read-only reader oracle');}});
const state=reader.load();assert.equal(availableReaderPresets(state).length,2);
const catalog=parseReaderManifest(reader.exportManifest());assert.equal(catalog.fonts.length,1);assert.equal(catalog.backgrounds.length,1);assert.equal(catalog.presets.filter((r:any)=>r.deleted).length,1);
const {ImportedTextStore,importedTextFileName}=await import(`${app}/src/reader/imported-text.ts`);
const file=(p:string)=>Bun.file(resolve(root,'podjs/files',p));
const imported=new ImportedTextStore({stat:async(p:string)=>({file:await file(p).exists(),size:file(p).size}),read:async(p:string,o:number,n:number)=>new Uint8Array(await file(p).slice(o,o+n).arrayBuffer()),decode:(b:Uint8Array)=>new TextDecoder('utf-8',{fatal:true}).decode(b)});
const marker='watchrss-watch-content:fixture.txt',h=await imported.handle(marker,7);assert.ok(h);
const chunks=[];for(let i=0;i<h.chunkCount;i++)chunks.push(await imported.chunk(h,i));
assert.equal(chunks.join(''),'<p>合成正文 😀</p>');
const {noteImageSource}=await import(`${app}/src/notes/presentation.ts`);
assert.equal(noteImageSource('assets/shared.png').filePath,'notes/assets/shared.png');
const {LocalArticleManifestStore}=await import(`${app}/src/sync/local-manifest.ts`);
const {encodeBodyJson}=await import(`${app}/src/sync/article-body.ts`);
const sqlite=new Database(resolve(root,'podjs.sqlite'));
const db={query:async(q:string,p:any[]=[])=>sqlite.query(q).all(...p),execute:async(q:string,p:any[]=[])=>{sqlite.query(q).run(...p);},transaction:async(statements:any[])=>{sqlite.transaction(()=>{for(const s of statements)sqlite.query(s.sql).run(...(s.params??[]));})();}};
// T39 adds derived sync tables at application startup after takeover. Run the
// same idempotent schema initialization before invoking current consumers.
const {initializeSchema}=await import(`${app}/src/core/schema.ts`);await initializeSchema(db);
const codec={sha256:async(b:Uint8Array)=>createHash('sha256').update(b).digest('hex'),encode:async(body:any)=>new Uint8Array(gzipSync(Buffer.from(encodeBodyJson(body),'utf8')))};
const text={parseUri:async(value:string)=>{try{const u=new URL(value);return {valid:true,scheme:u.protocol.slice(0,-1),host:u.hostname,rawPath:u.pathname,rawQuery:u.search.slice(1)||null};}catch{return {valid:false,scheme:null,host:null,rawPath:null,rawQuery:null};}},htmlText:async(value:string)=>value.replace(/<[^>]*>/g,'')};
const files={hydrate:async(value:string)=>{if(!importedTextFileName(value))return value;const handle=await imported.handle(value);assert.ok(handle);const out=[];for(let i=0;i<handle.chunkCount;i++){const part=await imported.chunk(handle,i);assert.notEqual(part,null);out.push(part);}return out.join('');},storeText:async(_key:string,value:string)=>value};
assert.equal(sqlite.query('SELECT count(*) AS n FROM article_sync_content').get().n,0);
const store=new LocalArticleManifestStore(db,codec,text,files,async()=>{});
const first=await store.snapshot();assert.ok(first.some((v:any)=>v.article.contentHtml?.includes('合成正文')||v.article.contentText?.includes('合成正文')));
for(const prepared of first.filter((v:any)=>v.manifest.bodyAvailable)){assert.match(prepared.manifest.bodyHash,/^[a-f0-9]{64}$/);assert.ok(prepared.manifest.chunkHashes.length>0);}
assert.ok(sqlite.query('SELECT count(*) AS n FROM article_sync_content').get().n>0,'derived cache must rebuild before successful manifest');
// The fixture deliberately has an opaque native syncBodyHash. Keep it on the
// first export; the existing production cache then uses the rebuilt body hash.
const second=await store.snapshot();
assert.deepEqual(second.map((v:any)=>({...v.manifest,contentHash:''})),first.map((v:any)=>({...v.manifest,contentHash:''})));
const third=await store.snapshot();assert.deepEqual(third.map((v:any)=>v.manifest),second.map((v:any)=>v.manifest));
// Production snapshot rejects concurrent semantic edits instead of advertising partial metadata.
await db.execute('DELETE FROM article_sync_content');await db.execute('DELETE FROM article_sync_encoding_cache');
let changed=false;const racing=new LocalArticleManifestStore(db,codec,text,{...files,hydrate:async(v:string)=>{if(!changed){changed=true;await db.execute("UPDATE articles SET title='concurrent edit' WHERE id=1");}return files.hydrate(v);}},async()=>{});
await assert.rejects(()=>racing.snapshot(),/changed during/);
sqlite.close();
console.log('File consumer oracle PASS: reader catalog, imported UTF-8 chunks, notes paths, derived sync cache rebuild, stable repeated manifest, concurrent edit rejected');
