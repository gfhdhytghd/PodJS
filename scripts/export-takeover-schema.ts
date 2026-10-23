// Explicit app input; no database or user-data discovery.
import {resolve} from 'node:path';
const app = process.argv[2];
if (!app) throw new Error('Usage: bun scripts/export-takeover-schema.ts APP [--check]');
const {SCHEMA} = await import(resolve(app, 'src/core/schema.ts'));
const {BOOK_READING_SCHEMA} = await import(resolve(app, 'src/books/reading-schema.ts'));
const memo = await Bun.file(resolve(app, 'src/notes/repository.ts')).text();
const block = memo.slice(memo.indexOf('const MEMO_SCHEMA'), memo.indexOf('export async function initializeMemoSchema'));
// These derived guest caches include a fresh random epoch. They are initialized
// by the guest's versioned migration, after deterministic native readback proof.
const statements = [...SCHEMA.filter((sql:string)=>!BOOK_READING_SCHEMA.includes(sql)), ...[...block.matchAll(/sql: `([^`]+)`/g)].map(x => x[1])];
const path = 'platforms/android/runtime/src/main/resources/dev/podjs/runtime/watchrss-schema.json';
const content = JSON.stringify(statements, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (await Bun.file(path).text() !== content) throw new Error('WatchRSS schema resource drift');
} else await Bun.write(path, content);
