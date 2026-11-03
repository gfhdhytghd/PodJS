import { mock } from 'bun:test';
import { realpathSync } from 'node:fs';

/** Bind selected runtime boundaries to the application's modules. A file-linked
 * package can resolve through both its installed path and its source checkout.
 * Apply after fixture mocks, before importing the consuming component. This
 * preserves those mocks and the real implementations behind unmocked modules.
 */
export async function bindPocketModules(appImporter: string, consumerImporter: string, specifiers: readonly string[]): Promise<void> {
  const importers = new Set([consumerImporter, realpathSync(consumerImporter)]);
  for (const specifier of specifiers) {
    const appPath = Bun.resolveSync(specifier, appImporter);
    const consumers = new Set([...importers].map(importer => Bun.resolveSync(specifier, importer)));
    consumers.delete(appPath);
    if (!consumers.size) continue;
    const runtime = await import(appPath);
    for (const consumer of consumers) mock.module(consumer, () => runtime);
  }
}

/** Bun's file dependencies may retain a nested PocketJS copy. Production's
 * compiler binds PocketJS imports to one checkout; mounted tests must share
 * that copy's service-pump registry too. This aliases the real module without
 * replacing event delivery, timer settlement, or component behavior.
 * Call before importing the consumer's watch/services modules, in an isolated
 * test process (mock.module changes module resolution for that process).
 */
export async function bindPocketServicePump(appImporter: string, consumerImporter: string): Promise<void> {
  await bindPocketModules(appImporter, consumerImporter, ['@pocketjs/framework/services']);
}
