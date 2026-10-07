import { expect, test } from 'bun:test';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bindPocketServicePump } from '../testing/bun-runtime.ts';

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
