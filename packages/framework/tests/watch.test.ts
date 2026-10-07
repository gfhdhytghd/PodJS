import { afterEach, describe, expect, test } from "bun:test";

const events: unknown[] = [];
const effects: string[] = [];
const values = new Map<string, string>();

(globalThis as { pod?: unknown }).pod = {
  takeEvents: () => events.length ? JSON.stringify(events.splice(0)) : undefined,
  emit: (line: string) => effects.push(line),
  displayMetrics: () => JSON.stringify({
    logicalWidth: 240, logicalHeight: 240, physicalWidth: 466, physicalHeight: 466,
    density: 1.9416667, shape: "round", safeInsets: { top: 8, right: 8, bottom: 8, left: 8 },
  }),
  capabilities: () => JSON.stringify(["input.touch", "data.kv"]),
  kvGet: (key: string) => values.get(key),
  kvSet: (key: string, value: string) => (values.set(key, value), 0),
  kvDelete: (key: string) => values.delete(key) ? 0 : 1,
  kvKeys: () => JSON.stringify([...values.keys()].sort()),
};

const api = await import("../src/watch.ts");
const frame = await import("../../../vendor/pocketjs/framework/src/frame.ts");
const services = await import("@pocketjs/framework/services");

afterEach(() => { events.length = 0; effects.length = 0; values.clear(); });

describe("watch API", () => {
  test("raw Android scroll retains source and physical delta independently of normalized axis", () => {
    const host=(globalThis as any).pod,prior=host.capabilities;
    const seen:unknown[]=[];const remove=api.onRawScrollInput(event=>seen.push(event));
    try {
      events.push({t:"input.scroll",vertical:-400,uptimeMs:100,source:8194});api.__pumpPodEvents();expect(seen).toEqual([]);
      host.capabilities=()=>JSON.stringify(["input.rotary"]);
      events.push({t:"input.scroll",vertical:-400,uptimeMs:100,source:8194},{t:"axis",axis:0,delta:125000},
        {t:"input.scroll",vertical:0,uptimeMs:100,source:8194},{t:"input.scroll",vertical:null,uptimeMs:100,source:8194},
        {t:"input.scroll",vertical:1,uptimeMs:-1,source:8194},{t:"input.scroll",vertical:1,uptimeMs:100,source:1.5});
      api.__pumpPodEvents();expect(seen).toEqual([{vertical:-400,uptimeMs:100,source:8194}]);
      remove();events.push({t:"input.scroll",vertical:1,uptimeMs:200,source:4194304});api.__pumpPodEvents();expect(seen).toHaveLength(1);
    } finally {remove();host.capabilities=prior;}
  });

  test("normalizes display metrics", () => {
    expect(api.getDisplayMetrics()).toMatchObject({ physicalWidth: 466, shape: "round" });
  });

  test("exposes only host-declared capabilities", () => {
    expect(api.capabilities()).toEqual(["input.touch", "data.kv"]);
    expect(api.hasCapability("data.kv")).toBeTrue();
    expect(api.hasCapability("net.http")).toBeFalse();
  });

  test("delivers signed rotary motion at a frame boundary", () => {
    const seen: number[] = [];
    const stop = api.onAxisDelta(api.RelativeAxis.Primary, delta => seen.push(delta));
    events.push({ t: "axis", axis: 0, delta: -1250 });
    api.__pumpPodEvents();
    stop();
    expect(seen).toEqual([-1250]);
  });

  test("preserves positive normalized motion toward the next position", () => {
    const seen: number[] = [];
    const stop = api.onAxisDelta(api.RelativeAxis.Primary, delta => seen.push(delta));
    events.push({ t: "axis", axis: 0, delta: 1250 });
    api.__pumpPodEvents();
    stop();
    expect(seen).toEqual([1250]);
  });

  test("normalizes finite axis direction without inventing a sign for zero or nonfinite input", () => {
    expect(api.normalizedRelativeAxisStep(1250)).toBe(1);
    expect(api.normalizedRelativeAxisStep(-1250)).toBe(-1);
    expect(api.normalizedRelativeAxisStep(0)).toBe(0);
    expect(api.normalizedRelativeAxisStep(Number.NaN)).toBe(0);
    expect(api.normalizedRelativeAxisStep(Number.POSITIVE_INFINITY)).toBe(0);
  });

  test("keeps the host event pump across application frame-hook reset", () => {
    const seen: number[] = [];
    const stop = api.onAxisDelta(api.RelativeAxis.Primary, delta => seen.push(delta));
    frame.resetFrameHooks();
    events.push({ t: "axis", axis: 0, delta: 12_000 });
    services.runServicePumps();
    stop();
    expect(seen).toEqual([12_000]);
  });

  test("host service-only entry delivers lifecycle without calling a UI frame", () => {
    const seen: string[] = [];
    const stop = api.onLifecycleChange(state => seen.push(state));
    events.push({ t: "lifecycle", state: "background" });
    (globalThis as unknown as { __pocketPumpServices: () => void }).__pocketPumpServices();
    stop();
    expect(seen).toEqual(["background"]);
    expect(api.lifecycle()).toBe("background");
    events.push({ t: "lifecycle", state: "active" });
    services.runServicePumps();
  });

  test("emits typed haptics and persists JSON KV values", () => {
    api.haptics.perform("success");
    api.kv.set("counter", { n: 3 });
    expect(JSON.parse(effects[0])).toEqual({ t: "haptic", kind: "success" });
    expect(api.kv.get<{ n: number }>("counter")).toEqual({ n: 3 });
    expect(api.kv.keys()).toEqual(["counter"]);
    expect(api.kv.delete("counter")).toBeTrue();
  });

  test("publishes navigation depth for native root-back handling", () => {
    api.setNavigationState({ canGoBack: true });
    expect(JSON.parse(effects.pop()!)).toEqual({ t: "navigation", canGoBack: true });
    expect(api.navigationStateSnapshot()).toEqual({ canGoBack: true });
    api.setNavigationState({ canGoBack: false });
    expect(JSON.parse(effects.pop()!)).toEqual({ t: "navigation", canGoBack: false });
  });

  test("swipe dismissal policy is independent of back callbacks and resets when omitted", () => {
    let backCalls = 0;
    const stop = api.onSystemBack(() => { backCalls++; return true; });
    try {
      api.setNavigationState({ canGoBack: false, rootSwipeEnabled: false });
      expect(JSON.parse(effects.pop()!)).toEqual({ t: "navigation", canGoBack: false, rootSwipeEnabled: false });
      events.push({ t: "back" }); api.__pumpPodEvents();
      expect(backCalls).toBe(1);
      expect(() => api.setNavigationState({ canGoBack: false, rootSwipeEnabled: "false" as any })).toThrow("must be boolean");
      expect(api.navigationStateSnapshot()).toEqual({ canGoBack: false, rootSwipeEnabled: false });
      expect(effects).toHaveLength(0);
      api.setNavigationState({ canGoBack: false });
      expect(JSON.parse(effects.pop()!)).toEqual({ t: "navigation", canGoBack: false });
      expect(api.navigationStateSnapshot()).toEqual({ canGoBack: false });
    } finally { stop(); }
  });

  test("validates UTF-8 KV key length without browser encoding globals", () => {
    const encoder = globalThis.TextEncoder;
    try {
      Object.defineProperty(globalThis, "TextEncoder", { value: undefined, configurable: true });
      expect(() => api.kv.set("😀".repeat(32), 1)).not.toThrow();
      expect(() => api.kv.set("中".repeat(43), 1)).toThrow("1..128 UTF-8 bytes");
    } finally {
      Object.defineProperty(globalThis, "TextEncoder", { value: encoder, configurable: true });
    }
  });
});

test("font metrics preserve atlas units and tolerate older hosts",()=>{
  const host=(globalThis as unknown as {pod:unknown}).pod as {fontMetrics?:(slot:number)=>string|undefined};
  expect(api.getFontMetrics(0)).toBeUndefined();
  try {
    host.fontMetrics=slot=>slot===1?JSON.stringify({cellWidth:18,cellHeight:20,baseline:15,lineHeight:21,rasterDensity:2}):undefined;
    expect(api.getFontMetrics(1)).toEqual({cellWidth:18,cellHeight:20,baseline:15,lineHeight:21,rasterDensity:2});
    expect(api.getFontMetrics(.5)).toBeUndefined();expect(api.getFontMetrics(2)).toBeUndefined();
    host.fontMetrics=()=>"{}";expect(api.getFontMetrics(1)).toBeUndefined();
  }finally{delete host.fontMetrics;}
});

test('explicit KV batch stages JSON snapshots and commits once; callback/host failures propagate',()=>{
 const host=(globalThis as any).pod,old=host.kvApply,commits:string[]=[];
 host.kvApply=(wire:string)=>{commits.push(wire);for(const row of JSON.parse(wire)){if(row.op==='set')values.set(row.key,JSON.stringify(row.value));else values.delete(row.key);}return 0;};
 try{
  values.clear();api.kv.set('counter',0);
  const result=api.kv.batch(()=>{
   const input={n:1};api.kv.set('counter',input);input.n=99;
   expect(api.kv.get<unknown>('counter')).toEqual({n:1});expect(JSON.parse(values.get('counter')!)).toBe(0);
   api.kv.batch(()=>{api.kv.set('other','汉字😀');api.kv.set('counter',{n:2});});
   expect(api.kv.keys()).toEqual(['counter','other']);expect(api.kv.delete('other')).toBe(true);expect(api.kv.delete('absent')).toBe(false);
   return 17;
  });
  api.kv.batch(()=>{api.kv.set('😀',1);api.kv.set('\ue000',2);expect(api.kv.keys()).toEqual(['counter','\ue000','😀']);api.kv.delete('😀');api.kv.delete('\ue000');});
  expect(result).toBe(17);expect(commits).toHaveLength(2);expect(api.kv.get<unknown>('counter')).toEqual({n:2});expect(api.kv.keys()).toEqual(['counter']);
  expect(()=>api.kv.batch(()=>{api.kv.set('counter',3);throw Error('caller failure');})).toThrow('caller failure');expect(commits).toHaveLength(2);expect(api.kv.get<unknown>('counter')).toEqual({n:2});
  host.kvApply=()=>1;expect(()=>api.kv.batch(()=>api.kv.set('counter',4))).toThrow('batch write failed');expect(api.kv.get<unknown>('counter')).toEqual({n:2});
  expect(()=>api.kv.batch(()=>Promise.resolve())).toThrow('must be synchronous');
  host.kvApply=undefined;api.kv.batch(()=>api.kv.set('counter',5));expect(api.kv.get<unknown>('counter')).toBe(5);
 }finally{host.kvApply=old;values.clear();}
});
