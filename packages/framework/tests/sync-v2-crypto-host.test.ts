import { expect, test } from "bun:test";
import { createSyncV2Crypto } from "../src/crypto";
import { hostOperation } from "../src/services";
import { __pumpPodEvents } from "../src/watch";

test("foreground Sync v2 crypto uses the capability-checked host bridge", async () => {
  const global = globalThis as { pod?: unknown };
  const previous = global.pod;
  const requests: { t: string; id: number; method: string; args: unknown }[] = [];
  let capability = true;
  let events: string | undefined;

  global.pod = {
    capabilities: () => capability ? '["crypto.basic"]' : "[]",
    emit: (request: string) => requests.push(JSON.parse(request)),
    takeEvents: () => {
      const batch = events;
      events = undefined;
      return batch;
    },
  };

  const crypto = createSyncV2Crypto(<T>(method: string, args: Record<string, unknown>) =>
    hostOperation<T>(method, args).result);
  const input = Uint8Array.of(1);
  const keyIdSha256 = "a".repeat(64);

  async function complete<T>(result: Promise<T>, method: string, args: unknown, value: unknown) {
    // Surface admission errors even when the bridge never receives a request.
    if (!requests.length) await result;
    const request = requests.shift()!;
    expect(request).toMatchObject({ t: "service.request", method, args });
    events = JSON.stringify([{ t: "service.result", id: request.id, ok: true, value }]);
    __pumpPodEvents();
    return result;
  }

  try {
    expect(await complete(crypto.verify(input, input, input), "crypto.v2.verify",
      { publicKeySec1: "AQ==", dataBase64: "AQ==", signatureBase64: "AQ==" }, { valid: true })).toBe(true);
    expect(await complete(crypto.signingKey(keyIdSha256, true), "crypto.v2.signingKey",
      { keyIdSha256, create: true }, { publicKeySec1: "AQ==" })).toEqual(input);
    expect(await complete(crypto.sign(keyIdSha256, input), "crypto.v2.sign",
      { keyIdSha256, dataBase64: "AQ==" }, { signatureBase64: "AQ==" })).toEqual(input);
    for (const method of ["crypto.v2.encrypt", "crypto.v2.decrypt", "crypto.v2.gunzip"]) {
      const value = { reachedHost: true };
      expect(await complete(hostOperation(method, {}).result, method, {}, value)).toEqual(value);
    }
    capability = false;
    await expect(crypto.verify(input, input, input)).rejects.toMatchObject({ code: "unsupported" });
    expect(requests).toEqual([]);
  } finally {
    global.pod = previous;
  }
});
