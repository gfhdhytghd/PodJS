import { expect, test } from "bun:test";
import { createResources } from "../src/resources.ts";
import { createStartupTrace } from "@podjs/framework/startup";

test("production resource constructors use default clock/cache with persistent redacted KV logs", async () => {
  const values = new Map<string, string>();
  const root = globalThis as typeof globalThis & {pod?: unknown};
  const previous = root.pod;
  root.pod = {
    takeEvents: () => undefined,
    emit: () => {},
    capabilities: () => JSON.stringify(["data.kv"]),
    kvGet: (key: string) => values.get(key),
    kvSet: (key: string, value: string) => { values.set(key, value); return 0; },
    kvDelete: (key: string) => values.delete(key) ? 0 : 1,
    kvKeys: () => JSON.stringify([...values.keys()]),
  };
  try {
    const resources = createResources(); // no injected clock, cache ports or image transport
    resources.logger.i("test", "token=synthetic-test-value");
    const trace = createStartupTrace({sink: message => resources.logger.i("startup", message)});
    trace.mark("content-ready");
    await resources.logger.flush();
    const log = await resources.logger.readLogs();
    expect(log).toContain("token=<redacted>");
    expect(log).not.toContain("synthetic-test-value");
    expect(log).toContain("content-ready");
    expect(resources.loader.stats()).toEqual({active: 0, queued: 0, cached: 0});
    expect(resources.cache.limitBytes).toBe(4 * 1024 * 1024);
    resources.loader.dispose();
  } finally { root.pod = previous; }
});
