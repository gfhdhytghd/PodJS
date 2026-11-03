import { expect, test } from 'bun:test';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bindPocketModules, bindPocketServicePump } from '../testing/bun-runtime.ts';

// Reproduce a nested file dependency regardless of the current install layout.
// Both modules contain the actual PocketJS pump implementation.
const nested = mkdtempSync(join(tmpdir(), 'podjs-bun-runtime-'));
const dependency = join(nested, 'node_modules/@pocketjs/framework');
mkdirSync(dependency, { recursive: true });
writeFileSync(join(dependency, 'package.json'), JSON.stringify({
  name: '@pocketjs/framework', type: 'module', exports: { './services': './services.ts' },
}));
copyFileSync(Bun.resolveSync('@pocketjs/framework/services', import.meta.dir), join(dependency, 'services.ts'));

try {
  await bindPocketServicePump(import.meta.dir, nested);
  const app = await import('@pocketjs/framework/services');
  const consumer = await import(Bun.resolveSync('@pocketjs/framework/services', nested));
  test('nested file dependency registers in the mounted runtime service pump', () => {
    let deliveries = 0;
    const remove = consumer.registerServicePump(() => deliveries++);
    try {
      app.runServicePumps();
      expect(deliveries).toBe(1);
      remove();
      app.runServicePumps();
      expect(deliveries).toBe(1);
    } finally { remove(); }
  });
} finally { rmSync(nested, { recursive: true, force: true }); }

test('a file-linked consumer shares the mounted pump through its real source dependency', async () => {
  const root = mkdtempSync(join(tmpdir(), 'podjs-linked-runtime-'));
  try {
    for (const owner of ['installed', 'source']) {
      const dependency = join(root, owner, 'node_modules/@pocketjs/framework');
      mkdirSync(dependency, { recursive: true });
      writeFileSync(join(dependency, 'package.json'), JSON.stringify({
        name: '@pocketjs/framework', type: 'module', exports: { './services': './services.ts' },
      }));
      copyFileSync(Bun.resolveSync('@pocketjs/framework/services', import.meta.dir), join(dependency, 'services.ts'));
    }
    const source = join(root, 'source/consumer.ts'), installed = join(root, 'installed/consumer.ts');
    writeFileSync(source, "export {registerServicePump} from '@pocketjs/framework/services';");
    symlinkSync(source, installed);
    await bindPocketServicePump(import.meta.dir, installed);
    const app = await import('@pocketjs/framework/services');
    const consumer = await import(installed);
    let deliveries = 0;
    const remove = consumer.registerServicePump(() => deliveries++);
    try { app.runServicePumps(); expect(deliveries).toBe(1); }
    finally { remove(); }
    app.runServicePumps(); expect(deliveries).toBe(1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('selected native boundaries keep fixture mocks across a file-linked consumer', async () => {
  const root = mkdtempSync(join(tmpdir(), 'podjs-linked-boundary-'));
  try {
    for (const owner of ['app', 'installed', 'source']) {
      for (const name of ['selected-boundary', 'other-boundary']) {
        const dependency = join(root, owner, 'node_modules', name);
        mkdirSync(dependency, { recursive: true });
        writeFileSync(join(dependency, 'package.json'), JSON.stringify({ name, type: 'module', exports: './index.ts' }));
        writeFileSync(join(dependency, 'index.ts'), `export const owner = ${JSON.stringify(owner)};`);
      }
    }
    const appImporter = join(root, 'app');
    const { mock } = await import('bun:test');
    mock.module(Bun.resolveSync('selected-boundary', appImporter), () => ({ owner: 'fixture mock' }));
    const source = join(root, 'source/consumer.ts'), installed = join(root, 'installed/consumer.ts');
    writeFileSync(source, "export {owner as selected} from 'selected-boundary'; export {owner as other} from 'other-boundary';");
    symlinkSync(source, installed);
    await bindPocketModules(appImporter, installed, ['selected-boundary']);
    const consumer = await import(installed);
    expect(consumer.selected).toBe('fixture mock');
    expect(consumer.other).toBe('source');
  } finally { rmSync(root, { recursive: true, force: true }); }
});
