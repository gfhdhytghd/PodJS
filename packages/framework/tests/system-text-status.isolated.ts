import {afterEach, beforeEach, expect, mock, test} from "bun:test";

interface Request {t: string; id: number; method: string; args: Record<string, any>}
const events: unknown[] = [];
const emitted: Request[] = [];
const pending = new Map<number, Request>();
const images: Array<[number, number]> = [];
const uploads: number[] = [];
const freed: number[] = [];
const releases = new Set<() => void>();
let nodeId = 100, textureId = 700;
let upload: () => number;
let setImage: (node: number, texture: number) => void;

(globalThis as any).pod = {
  takeEvents: () => events.length ? JSON.stringify(events.splice(0)) : undefined,
  emit(line: string) {
    const event = JSON.parse(line) as Request;
    emitted.push(event);
    if (event.t === "service.request") pending.set(event.id, event);
  },
  capabilities: () => JSON.stringify(["text.raster", "data.files.chunks"]),
};
const pod = (globalThis as any).pod;
mock.module("@pocketjs/framework/components", () => ({View() {}, Image() {}}));
mock.module("@pocketjs/framework/host", () => ({getOps: () => ({
  setImage(node: number, texture: number) { images.push([node, texture]); setImage(node, texture); },
  uploadTexture() { const result = upload(); uploads.push(result); return result; },
  freeTexture(texture: number) { freed.push(texture); },
})}));
mock.module("@pocketjs/framework/gesture", () => ({cancelActiveTouches() {}}));
mock.module("@pocketjs/framework/input", () => ({dispatchAccessibilityAction() {}}));
mock.module("@pocketjs/framework/services", () => ({registerServicePump() {}}));

// Only the native rendering boundary is replaced. Requests and replies go
// through the real watch event fanout and services.ts promise registry.
const {__pumpPodEvents} = await import("../src/watch.ts");
const {bindSystemTextLine, systemTextResourceCounts, systemTextResourceStatus} = await import("../src/text.tsx");
type Snapshot = ReturnType<typeof systemTextResourceStatus>;
type Totals = Snapshot["totals"];
const tick = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };
async function reply(request: Request, value: unknown) {
  expect(pending.delete(request.id)).toBe(true);
  events.push({t: "service.result", id: request.id, ok: true, value});
  __pumpPodEvents(); await tick();
}
async function reject(request: Request, message = "private-native-error") {
  expect(pending.delete(request.id)).toBe(true);
  events.push({t: "service.result", id: request.id, ok: false, code: "native_failure", message});
  __pumpPodEvents(); await tick();
}
const request = (method: string) => {
  const matches = [...pending.values()].filter(value => value.method === method);
  expect(matches).toHaveLength(1);
  return matches[0]!;
};
const options = (text: string) => ({text, widthPx: 1, heightPx: 1, baselinePx: 1, fontSizePx: 1});
function bind(text: string, ready: (value: any) => void = () => {}, extra = {}) {
  const node = ++nodeId;
  const release = bindSystemTextLine({...options(text), ...extra}, {id: node} as any, ready);
  const stop = () => { if (releases.delete(stop)) release(); };
  releases.add(stop);
  return {node, stop, rawRelease: release};
}
const raster = (operation: Request, path = `private-text/${operation.id}.rgba`) => ({
  path, width: 1, height: 1, carets: Array.from({length: operation.args.text.length + 1}, (_, index) => index),
});
async function pixels() { await reply(request("file.read"), {dataBase64: "AAAAAA==", size: 4}); }
async function removeFile(fail = false) {
  const operation = request("file.delete");
  if (fail) await reject(operation, "private-cleanup-error");
  else await reply(operation, {deleted: true});
}
const current = () => {
  const snapshot = systemTextResourceStatus();
  expect(snapshot.entries).toHaveLength(1);
  return snapshot.entries[0]!;
};
function totalsSince(before: Totals) {
  const now = systemTextResourceStatus().totals;
  return {
    succeeded: now.succeeded - before.succeeded,
    failed: now.failed - before.failed,
    delivered: now.delivered - before.delivered,
    failuresByStage: Object.fromEntries(Object.entries(now.failuresByStage).map(([stage, count]) =>
      [stage, count - before.failuresByStage[stage as keyof typeof before.failuresByStage]])),
  };
}
const noFailures = {raster: 0, read: 0, upload: 0, "set-image": 0, "ready-callback": 0};
function expectTotals(before: Totals, succeeded: number, failed: number, delivered: number, stages = noFailures) {
  expect(totalsSince(before)).toEqual({succeeded, failed, delivered, failuresByStage: stages});
}
function freezeTree(value: unknown) {
  if (!value || typeof value !== "object") return;
  expect(Object.isFrozen(value)).toBe(true);
  for (const child of Object.values(value)) freezeTree(child);
}
beforeEach(() => {
  expect(systemTextResourceCounts()).toEqual({active: 0, queued: 0, cached: 0});
  expect(pending.size).toBe(0);
  upload = () => ++textureId;
  setImage = () => {};
  delete pod.uploadTextureFile;
});
afterEach(async () => {
  // Release through the public API and drain host work; never reset module state.
  setImage = () => {};
  for (const stop of [...releases]) stop();
  for (let step = 0; pending.size && step < 50; step++) {
    const operation = pending.values().next().value!;
    if (operation.method === "text.raster") await reply(operation, raster(operation));
    else if (operation.method === "file.read") await reply(operation, {dataBase64: "AAAAAA==", size: 4});
    else { expect(operation.method).toBe("file.delete"); await reply(operation, {deleted: true}); }
  }
  await tick();
  expect(systemTextResourceCounts()).toEqual({active: 0, queued: 0, cached: 0});
  expect(systemTextResourceStatus()).toMatchObject({pending: 0, ready: 0, failed: 0, entries: []});
  expect(pending.size).toBe(0);
  delete pod.uploadTextureFile;
});

test("native file upload skips Base64 reads and retains normal final-reference release", async () => {
  const before = systemTextResourceStatus().totals, freeCount = freed.length;
  const calls: unknown[][] = [];
  pod.uploadTextureFile = (...args: unknown[]) => { calls.push(args); return 0; };
  const binding = bind("native-file"), operation = request("text.raster");
  await reply(operation, raster(operation, "text/line-123.rgba"));
  expect(calls).toEqual([["text/line-123.rgba", 1, 1, 3]]);
  expect([...pending.values()].filter(value => value.method === "file.read" || value.method === "file.delete")).toEqual([]);
  expect(uploads).not.toContain(0);
  expect(images).toContainEqual([binding.node, 0]);
  expect(current()).toMatchObject({status: "ready", stage: "ready", uploaded: true});
  expectTotals(before, 1, 0, 1);
  binding.stop();
  expect(freed.slice(freeCount)).toEqual([0]);
});

test("synchronous cancellation during native file upload frees its returned handle without delivery", async () => {
  const before = systemTextResourceStatus().totals, freeCount = freed.length;
  let binding!: ReturnType<typeof bind>;
  pod.uploadTextureFile = () => { binding.stop(); return 0; };
  binding = bind("native-cancel");
  const operation = request("text.raster");
  await reply(operation, raster(operation, "text/line-456.rgba"));
  expect(images.filter(([node, texture]) => node === binding.node && texture >= 0)).toEqual([]);
  expect([...pending.values()].filter(value => value.method === "file.read" || value.method === "file.delete")).toEqual([]);
  expect(freed.slice(freeCount)).toEqual([0]);
  expect(systemTextResourceCounts()).toEqual({active: 0, queued: 0, cached: 0});
  expectTotals(before, 0, 0, 0);
});

test("shared resource becomes successful only after every current listener is delivered", async () => {
  const before = systemTextResourceStatus().totals;
  const observed: Snapshot[] = [];
  const first = bind("shared", () => observed.push(systemTextResourceStatus()));
  const operation = request("text.raster");
  const second = bind("shared", () => observed.push(systemTextResourceStatus()));
  expect(current()).toMatchObject({status: "pending", stage: "raster", refs: 2, uploaded: false,
    readyListeners: 0, pendingListeners: 2, failedListeners: 0, failureStages: []});
  const id = current().id;
  await reply(operation, raster(operation));
  expect(current()).toMatchObject({stage: "read", status: "pending"});
  let duringUpload: Snapshot | undefined;
  upload = () => { duringUpload = systemTextResourceStatus(); return ++textureId; };
  await pixels();
  expect(duringUpload!.entries[0]).toMatchObject({stage: "upload", uploaded: false});
  expect(observed).toHaveLength(2);
  expect(observed[0]!.entries[0]).toMatchObject({stage: "delivery", uploaded: true, pendingListeners: 2, readyListeners: 0});
  expect(observed[1]!.entries[0]).toMatchObject({stage: "delivery", pendingListeners: 1, readyListeners: 1});
  expect(observed.map(snapshot => snapshot.totals.succeeded)).toEqual([before.succeeded, before.succeeded]);
  expect(current()).toMatchObject({id, status: "ready", stage: "ready", refs: 2, uploaded: true,
    readyListeners: 2, pendingListeners: 0, failedListeners: 0});
  // active counts drain slots, not pending render entries.
  expect(systemTextResourceStatus()).toMatchObject({active: 1, queued: 0, cached: 1, pending: 0, ready: 1, failed: 0});
  await removeFile();
  expectTotals(before, 1, 0, 2);
  const requestCount = emitted.length, uploadCount = uploads.length, freeCount = freed.length;
  const cached = bind("shared");
  expect(emitted.length).toBe(requestCount);
  expect(uploads.length).toBe(uploadCount);
  expect(current()).toMatchObject({id, refs: 3, readyListeners: 3, status: "ready"});
  expectTotals(before, 1, 0, 3);
  first.stop(); second.stop();
  expect(freed.length).toBe(freeCount);
  cached.stop();
  expect(freed.length).toBe(freeCount + 1);
  expectTotals(before, 1, 0, 3);
});

test("status snapshots are deeply frozen independent records without resource contents or paths", async () => {
  const secret = "private-user-text-DO-NOT-EXPOSE";
  const binding = bind(secret, undefined, {fontRevision: "private-font-revision", fontPath: "/private/font.ttf"});
  const before = systemTextResourceStatus(), repeated = systemTextResourceStatus();
  freezeTree(before);
  expect(repeated).not.toBe(before);
  expect(repeated.entries).not.toBe(before.entries);
  expect(repeated.entries[0]).not.toBe(before.entries[0]);
  expect(repeated.totals).not.toBe(before.totals);
  expect(repeated.totals.failuresByStage).not.toBe(before.totals.failuresByStage);
  expect(Object.keys(before.entries[0]!).sort()).toEqual([
    "id", "status", "stage", "refs", "uploaded", "readyListeners", "pendingListeners", "failedListeners", "failureStages",
  ].sort());
  const id = before.entries[0]!.id;
  expect(typeof id).toBe("number");
  expect(Reflect.set(before.totals, "failed", 999_999)).toBe(false);
  expect(Reflect.set(before.totals.failuresByStage, "raster", 999_999)).toBe(false);
  expect(Reflect.set(before.entries[0]!, "refs", 999_999)).toBe(false);
  expect(() => (before.entries as any[]).push({})).toThrow();
  expect(() => (before.entries[0]!.failureStages as string[]).push("raster")).toThrow();
  const operation = request("text.raster");
  await reply(operation, raster(operation, "/private/generated-text.rgba"));
  await reject(request("file.read"), "private-exception-with-text-and-path");
  const failed = systemTextResourceStatus();
  freezeTree(failed);
  expect(before.entries[0]).toMatchObject({id, status: "pending", stage: "raster", refs: 1, failureStages: []});
  expect(failed.entries[0]).toMatchObject({id, status: "failed", stage: "failed", failureStages: ["read"]});
  const serialized = JSON.stringify([before, failed]);
  for (const forbidden of [secret, "private-font-revision", "/private/", "private-exception", "options", "carets"]) {
    expect(serialized).not.toContain(forbidden);
  }
  await removeFile(); binding.stop();
  const retry = bind(secret);
  expect(current().id).not.toBe(id);
  retry.stop();
});

for (const fault of ["raster-rejection", "raster-size", "raster-carets", "raster-null", "read-rejection", "read-empty", "read-size", "read-malformed", "upload-negative", "upload-throw"] as const) {
  test(`records exactly the ${fault} failure at its native boundary`, async () => {
    const before = systemTextResourceStatus().totals;
    const delivered: unknown[] = [];
    const binding = bind(fault, value => delivered.push(value));
    const operation = request("text.raster");
    const stage = fault.startsWith("raster") ? "raster" : fault.startsWith("read") ? "read" : "upload";
    if (fault === "raster-rejection") await reject(operation);
    else if (fault === "raster-null") await reply(operation, null);
    else if (fault === "raster-size") await reply(operation, {...raster(operation), width: 3});
    else if (fault === "raster-carets") await reply(operation, {...raster(operation), carets: []});
    else {
      await reply(operation, raster(operation));
      if (fault === "read-rejection") await reject(request("file.read"));
      else if (fault === "read-empty") await reply(request("file.read"), {dataBase64: "", size: 4});
      else if (fault === "read-size") await reply(request("file.read"), {dataBase64: "AAAAAA==", size: 8});
      else if (fault === "read-malformed") await reply(request("file.read"), {dataBase64: null, size: 4});
      else {
        upload = () => { if (fault === "upload-throw") throw new Error("private-upload-error"); return -1; };
        await pixels();
      }
    }
    // A failed resource never attempted listener delivery. Listener failure
    // counts describe setImage/ready failures, not the upstream load failure.
    expect(current()).toMatchObject({status: "failed", stage: "failed", refs: 1, uploaded: false,
      readyListeners: 0, pendingListeners: 1, failedListeners: 0, failureStages: [stage]});
    expect(systemTextResourceStatus()).toMatchObject({pending: 0, ready: 0, failed: 1});
    expect(delivered).toEqual([]);
    expect(images.filter(([node, texture]) => node === binding.node && texture >= 0)).toEqual([]);
    expectTotals(before, 0, 1, 0, {...noFailures, [stage]: 1});
    if ([...pending.values()].some(value => value.method === "file.delete")) await removeFile(true);
    // Best-effort cleanup rejection is outside text production/delivery failures.
    expectTotals(before, 0, 1, 0, {...noFailures, [stage]: 1});
    const requestsBefore = emitted.length;
    const another = bind(fault);
    expect(emitted.length).toBe(requestsBefore);
    expect(current()).toMatchObject({status: "failed", refs: 2});
    expectTotals(before, 0, 1, 0, {...noFailures, [stage]: 1});
    another.stop(); binding.stop();
  });
}

for (const stage of ["set-image", "ready-callback"] as const) {
  test(`${stage} failures do not prevent other listeners or strand cached subscriptions`, async () => {
    const before = systemTextResourceStatus().totals;
    let badNode = -1, badReadyCalls = 0, goodReadyCalls = 0;
    setImage = (node, texture) => { if (stage === "set-image" && node === badNode && texture >= 0) throw new Error("private-set-image-error"); };
    const bad = bind(stage, () => { badReadyCalls++; if (stage === "ready-callback") throw new Error("private-ready-error"); });
    badNode = bad.node;
    const operation = request("text.raster");
    const good = bind(stage, () => { goodReadyCalls++; });
    await reply(operation, raster(operation)); await pixels(); await removeFile();
    expect(badReadyCalls).toBe(stage === "set-image" ? 0 : 1);
    expect(goodReadyCalls).toBe(1);
    expect(current()).toMatchObject({status: "failed", uploaded: true, refs: 2,
      readyListeners: 1, pendingListeners: 0, failedListeners: 1, failureStages: [stage]});
    expectTotals(before, 0, 1, 1, {...noFailures, [stage]: 1});
    bad.stop();
    expect(current()).toMatchObject({status: "ready", stage: "ready", refs: 1, failedListeners: 0});
    expectTotals(before, 1, 1, 1, {...noFailures, [stage]: 1});
    const requestCount = emitted.length, uploadCount = uploads.length;
    let cachedBad: ReturnType<typeof bind> | undefined;
    expect(() => {
      badNode = nodeId + 1;
      cachedBad = bind(stage, () => { if (stage === "ready-callback") throw new Error("private-cached-ready-error"); });
    }).not.toThrow();
    expect(emitted.length).toBe(requestCount);
    expect(uploads.length).toBe(uploadCount);
    expect(current()).toMatchObject({status: "failed", uploaded: true, refs: 2, readyListeners: 1, failedListeners: 1});
    expectTotals(before, 1, 2, 1, {...noFailures, [stage]: 2});
    cachedBad!.stop();
    expect(current()).toMatchObject({status: "ready", refs: 1, failedListeners: 0});
    const cachedGood = bind(stage);
    expect(current()).toMatchObject({status: "ready", refs: 2, readyListeners: 2});
    expectTotals(before, 1, 2, 2, {...noFailures, [stage]: 2});
    cachedGood.stop(); good.stop();
  });
}

test("queued cancellation and late raster results drain without recording failures or deliveries", async () => {
  const before = systemTextResourceStatus().totals;
  const start = emitted.length, uploaded = uploads.length;
  const one = bind("cancel-raster-one"), first = request("text.raster");
  const two = bind("cancel-raster-two");
  const second = [...pending.values()].find(value => value.method === "text.raster" && value.id !== first.id)!;
  const queued = bind("never-rasterize");
  expect(systemTextResourceStatus()).toMatchObject({active: 2, queued: 1, cached: 3, pending: 3, ready: 0, failed: 0});
  expect(systemTextResourceStatus().entries.filter(entry => entry.stage === "queued")).toHaveLength(1);
  queued.stop(); one.stop();
  expect(systemTextResourceStatus()).toMatchObject({active: 2, queued: 0, cached: 1, pending: 1});
  // Malformed metadata on an abandoned raster result must not become a failure.
  await reply(first, {path: "private-text/cancelled.rgba", width: 3, height: 0, carets: []});
  expect([...pending.values()].filter(value => value.method === "file.read")).toEqual([]);
  await removeFile();
  two.stop(); await reject(second);
  expect(systemTextResourceCounts()).toEqual({active: 0, queued: 0, cached: 0});
  expect(uploads.length).toBe(uploaded);
  expect(emitted.slice(start).filter(value => value.t === "service.cancel")).toEqual([]);
  expect(emitted.slice(start).filter(value => value.method === "text.raster" && value.args.text === "never-rasterize")).toEqual([]);
  expectTotals(before, 0, 0, 0);
});

for (const outcome of ["success", "rejection"] as const) {
  test(`release during read ignores late ${outcome} and still removes its raster file`, async () => {
    const before = systemTextResourceStatus().totals, uploaded = uploads.length;
    const binding = bind(`cancel-read-${outcome}`), operation = request("text.raster");
    await reply(operation, raster(operation));
    const read = request("file.read"); binding.stop();
    expect(systemTextResourceStatus()).toMatchObject({active: 1, cached: 0, pending: 0, entries: []});
    if (outcome === "success") await reply(read, {dataBase64: "invalid-unused-data", size: -1});
    else await reject(read);
    await removeFile();
    expect(uploads.length).toBe(uploaded);
    expectTotals(before, 0, 0, 0);
  });
}

test("a detached listener's late callback exception is not an operation failure", async () => {
  const before = systemTextResourceStatus().totals;
  let binding!: ReturnType<typeof bind>;
  binding = bind("detach-in-ready", () => { binding.stop(); throw new Error("private-detached-error"); });
  const operation = request("text.raster");
  await reply(operation, raster(operation)); await pixels(); await removeFile();
  expect(systemTextResourceStatus()).toMatchObject({active: 0, cached: 0, entries: []});
  expect(totalsSince(before).failed).toBe(0);
  expect(totalsSince(before).failuresByStage).toEqual(noFailures);
});

test("releasing a pending shared listener prevents its later delivery without affecting the survivor", async () => {
  const before = systemTextResourceStatus().totals;
  let survivingCalls = 0, abandonedCalls = 0;
  let abandoned!: ReturnType<typeof bind>;
  const survivor = bind("release-other", () => { survivingCalls++; abandoned.stop(); });
  const operation = request("text.raster");
  abandoned = bind("release-other", () => { abandonedCalls++; });
  await reply(operation, raster(operation)); await pixels(); await removeFile();
  expect(survivingCalls).toBe(1); expect(abandonedCalls).toBe(0);
  expect(current()).toMatchObject({status: "ready", refs: 1, readyListeners: 1, pendingListeners: 0, failedListeners: 0});
  expectTotals(before, 1, 0, 1);
  survivor.stop();
});

for (const outcome of ["returned-handle", "exception"] as const) {
  test(`synchronous cancellation during upload discards its ${outcome} without recording failure`, async () => {
    const before = systemTextResourceStatus().totals, freeCount = freed.length;
    const binding = bind(`cancel-upload-${outcome}`), operation = request("text.raster");
    await reply(operation, raster(operation));
    upload = () => {
      expect(current()).toMatchObject({stage: "upload", uploaded: false});
      binding.stop();
      if (outcome === "exception") throw new Error("private-cancelled-upload-error");
      return 0;
    };
    await pixels(); await removeFile();
    expect(images.filter(([node, texture]) => node === binding.node && texture >= 0)).toEqual([]);
    expect(freed.slice(freeCount)).toEqual(outcome === "returned-handle" ? [0] : []);
    expectTotals(before, 0, 0, 0);
  });
}

test("texture zero is valid and repeated release cleans up once even when detachment throws", async () => {
  const before = systemTextResourceStatus().totals, freeCount = freed.length;
  let delivered = 0;
  const binding = bind("texture-zero", () => { delivered++; }), operation = request("text.raster");
  upload = () => 0;
  await reply(operation, raster(operation)); await pixels(); await removeFile();
  expect(delivered).toBe(1);
  expect(current()).toMatchObject({uploaded: true, status: "ready", readyListeners: 1});
  expect(images).toContainEqual([binding.node, 0]);
  let detaches = 0;
  setImage = (node, texture) => {
    if (node === binding.node && texture === -1) { detaches++; throw new Error("private-detach-error"); }
  };
  expect(() => { binding.rawRelease(); binding.rawRelease(); binding.stop(); }).not.toThrow();
  expect(detaches).toBe(1);
  expect(freed.slice(freeCount)).toEqual([0]);
  expect(systemTextResourceCounts()).toEqual({active: 0, queued: 0, cached: 0});
  expectTotals(before, 1, 0, 1);
});
