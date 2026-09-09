import { expect, test } from "bun:test";
import { POD_TARGETS } from "../src/targets.ts";
import { platformMethods } from "../src/platform-contract.ts";
import { syncState, syncFiles, syncMessages, notifications, background } from "../src/platform-services.ts";
import { __pumpPodEvents } from "../src/watch.ts";
test("notification inbox acknowledges only successful subscriber delivery", async () => {
  const previous = (globalThis as { pod?: unknown }).pod;
  const commands: string[] = []; let batch: string | undefined;
  (globalThis as { pod?: unknown }).pod = { takeEvents: () => {const value=batch;batch=undefined;return value;}, capabilities: () => '["notification.local"]', emit: (s: string) => commands.push(s) };
  const event = {t:"notification.open",value:{eventId:"notification-ready-test",notificationId:"test",payload:17}};
  const pump = () => {batch=JSON.stringify([event]);__pumpPodEvents();};
  let unsubscribe = () => {};
  try {
    pump(); await Bun.sleep(1); expect(commands).toEqual([]);
    let calls=0;let finish!:()=>void;
    unsubscribe=notifications.onOpen(async () => {calls++;await new Promise<void>(resolve=>{finish=resolve;});});
    pump();await Bun.sleep(1);pump();await Bun.sleep(1);
    expect(calls).toBe(1);expect(commands).toEqual([]);
    finish();await Bun.sleep(1);
    expect(JSON.parse(commands[0]!)).toEqual({t:"notification.ack",eventId:event.value.eventId});
    pump();await Bun.sleep(1);expect(calls).toBe(1);expect(commands.length).toBe(2);
    unsubscribe();commands.length=0;event.value.eventId="notification-retry-test";
    let attempts=0;
    unsubscribe=notifications.onOpen(()=>{if(++attempts===1)throw Error("retry");});
    pump();await Bun.sleep(1);expect(commands).toEqual([]);
    pump();await Bun.sleep(1);expect(attempts).toBe(2);expect(commands.length).toBe(1);
  } finally {unsubscribe();(globalThis as {pod?:unknown}).pod=previous;}
});
test("unimplemented native capabilities are not advertised by any target", () => {
  for (const target of Object.values(POD_TARGETS)) {
    for (const capability of Object.values(platformMethods)) {
      if (["background.scheduled", "notification.local", "companion.sync.state", "companion.sync.message", "companion.sync.file"].includes(capability) && ["android-watch", "wearos-watch"].includes(target.id)) {
        expect(target.capabilities).toContain(capability);
      } else expect(target.capabilities).not.toContain(capability);
    }
  }
});
test("new APIs fail closed without a supporting host", async () => {
  const previous = (globalThis as { pod?: unknown }).pod;
  const commands: string[] = [];
  (globalThis as { pod?: unknown }).pod = { takeEvents: () => undefined, capabilities: () => "[]", emit: (s: string) => commands.push(s) };
  try {
    const operations = [syncState.get("x"), syncFiles.status("transfer"), syncFiles.save("transfer", "received.bin"), syncMessages.send("phone", { messageId: "m1", payload: null, ttlMs: 1000, priority: "normal" }), notifications.schedule({ id: "n", title: "Hello", body: "World" }), notifications.registerRemote(), background.register({ id: "b", handler: "refresh", earliestAt: 0 })];
    for (const operation of operations) await expect(operation.result).rejects.toMatchObject({ code: "unsupported" });
    expect(commands).toEqual([]);
    expect(() => syncState.subscribe(() => {})).toThrow("companion.sync.state");
  } finally { (globalThis as { pod?: unknown }).pod = previous; }
});
