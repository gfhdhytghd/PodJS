import { mount } from "@pocketjs/framework/solid";
import { Text } from "@pocketjs/framework/components";
import { syncState, syncMessages, syncFiles } from "../../../packages/framework/src/platform-services.ts";
import { hostOperation } from "../../../packages/framework/src/services.ts";
import { kv } from "../../../packages/framework/src/watch.ts";

kv.set("sync-boot-result", "pending");
syncState.subscribe(entry => {
  if (entry.key === "normal-ui-probe") kv.set("sync-ui-received", JSON.stringify(entry.value));
});
async function probe() {
  await syncState.set("boot-probe", { source: "installed-guest", value: 42, bootedAt: Date.now() }).result;
  const result = await syncState.get("boot-probe").result;
  const message = await syncMessages.send("boot-peer", { messageId: "boot-" + Date.now(), payload: { source: "installed-guest" }, ttlMs: 60000, priority: "normal" }).result;
  await hostOperation("file.write", { path: "sync-boot.txt", dataBase64: "aGVsbG8=", atomicReplace: true }).result;
  const offer = await syncFiles.offer("boot-peer", "sync-boot.txt", "text/plain").result;
  const offered = await syncFiles.status(offer.transferId).result;
  await syncFiles.cancel(offer.transferId).result;
  const cancelled = await syncFiles.status(offer.transferId).result;
  kv.set("sync-boot-result", JSON.stringify({ ...result, message, offered, cancelled }));
}
probe().catch(error => kv.set("sync-boot-result", "error:" + String(error)));
mount(() => <Text>Sync package test</Text>);
