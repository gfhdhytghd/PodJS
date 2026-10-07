import { mock } from 'bun:test';

/** Bun's file dependencies may retain a nested PocketJS copy. Production's
 * compiler binds PocketJS imports to one checkout; mounted tests must share
 * that copy's service-pump registry too. This aliases the real module without
 * replacing event delivery, timer settlement, or component behavior.
 * Call before importing the consumer's watch/services modules, in an isolated
 * test process (mock.module changes module resolution for that process).
 */
export async function bindPocketServicePump(appImporter: string, consumerImporter: string): Promise<void> {
  const specifier = '@pocketjs/framework/services';
  const appPath = Bun.resolveSync(specifier, appImporter);
  const consumerPath = Bun.resolveSync(specifier, consumerImporter);
  if (appPath === consumerPath) return;
  const services = await import(appPath);
  mock.module(consumerPath, () => services);
}
