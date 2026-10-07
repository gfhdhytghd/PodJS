import { expect, test } from "bun:test";
import { createFirstScreenGate, createStartupTrace } from "../src/startup.ts";
import { __pumpPodEvents } from "../src/watch.ts";

test("trace defaults construct without injection; instances deduplicate independently", async () => {
  const plain = createStartupTrace();
  plain.mark("ready");
  const result = {};
  expect(await plain.step("load", async () => result)).toBe(result);
  const messages: string[] = [];
  let now = 10;
  const trace = createStartupTrace({clock: () => now, sink: message => messages.push(message)});
  now = 15.6; trace.mark("load"); trace.mark("load");
  now = 20;
  const error = new Error("failed");
  await expect(trace.step("bad", () => {now = 25; throw error;})).rejects.toBe(error);
  expect(messages).toEqual(["load +6ms", "bad took 5ms (+15ms)"]);
  createStartupTrace({clock: () => now, sink: message => messages.push(message)}).mark("load");
  expect(messages.at(-1)).toBe("load +0ms");
});

test("production gate uses host delay, handles rejection and cleans up on explicit disposal", async () => {
  const root = globalThis as any, previous = root.pod;
  const emitted: any[] = [], events: any[] = [];
  root.pod = {
    capabilities: () => JSON.stringify(["runtime.timer"]),
    emit: (line: string) => emitted.push(JSON.parse(line)),
    takeEvents: () => events.length ? JSON.stringify(events.splice(0)) : undefined,
  };
  try {
    const gate = createFirstScreenGate(); // no dependency injection
    const waiting = gate.wait();
    expect(emitted.at(-1)).toMatchObject({method:"runtime.delay", args:{milliseconds:3000}});
    events.push({t:"service.result", id:emitted.at(-1).id, ok:false, error:{code:"timer",message:"failed"}});
    __pumpPodEvents(); await waiting;
    let settled = false;
    const second = gate.wait(12).then(() => {settled = true;});
    await Promise.resolve(); expect(settled).toBe(false); // timeout did not deliver ready
    gate.ready(); gate.ready(); await second;
    expect(emitted.filter(event => event.t === "service.cancel")).toHaveLength(0);
    gate.dispose(); gate.dispose();
    expect(emitted.filter(event => event.t === "service.cancel")).toHaveLength(1);
    await gate.wait(); // disposal creates no further timers
    const separate = createFirstScreenGate();
    let separateSettled = false;
    const pending = separate.wait(4).then(() => { separateSettled = true; });
    await Promise.resolve(); expect(separateSettled).toBe(false);
    separate.dispose(); await pending;
  } finally { root.pod = previous; }
});

test("explicit disposal suppresses queued callbacks and cancels each outstanding timer once", async () => {
  let cancelled = 0, resolved = 0, ready = 0;
  const gate = createFirstScreenGate({
    delay: () => ({result: new Promise<void>(() => {}), cancel: () => {cancelled++;}}),
    onReady: () => {ready++;}, onResolved: () => {resolved++;},
  });
  const waits = [gate.wait(1), gate.wait(2)];
  gate.ready(); gate.dispose(); gate.dispose(); gate.ready();
  await Promise.all(waits);
  expect({cancelled,resolved,ready}).toEqual({cancelled:2,resolved:0,ready:1});
});
