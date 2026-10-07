import { afterEach, describe, expect, mock, test } from "bun:test";

let nextNode = 1;
const calls: unknown[][] = [];
const cancelled: string[] = [];
const imageFiles:string[]=[],removedFiles:string[]=[];
const hostMethods:string[]=[];
let deferredDecode:{result:Promise<any>;cancel():void}|undefined;
const pendingDownloads = new Map<string, { resolve(value: { path: string }): void; reject(error:Error):void }>();
const stored=new Map<string,number>();
const rgba = new Uint8Array([255, 0, 0, 255, 0, 255, 0, 255]);
const pod=(globalThis as any).pod??((globalThis as any).pod={});

mock.module("@pocketjs/framework/components", () => ({
  View: (props: unknown) => props,
  Image: (props: { ref?: (node: { id: number }) => void }) => {
    const node = { id: nextNode++ };
    props.ref?.(node);
    return node;
  },
}));

const ops = {
  uploadTexture(bytes: Uint8Array, width: number, height: number, psm: number) {
    calls.push(["uploadTexture", [...bytes], width, height, psm]);
    return 41;
  },
  setImage(id: number, handle: number) { calls.push(["setImage", id, handle]); },
  freeTexture(handle: number) { calls.push(["freeTexture", handle]); },
};
const realHost = await import("@pocketjs/framework/host");
mock.module("@pocketjs/framework/host", () => ({ ...realHost, getOps: () => ops }));

const realServices = await import("../src/services.ts");
mock.module("../src/services.ts", () => ({
  ...realServices,
  base64ToBytes: (value: string) => new Uint8Array(Buffer.from(value, "base64")),
  hostOperation: (method: string, args: Record<string, unknown>) => {
    hostMethods.push(method);
    if (method === "image.decode") {if(deferredDecode)return deferredDecode;imageFiles.push(String(args.path));return { result: Promise.resolve({ path: "images/a.rgba", width: 2, height: 1, sourceWidth:3, sourceHeight:5 }), cancel() {} };}
    if (method === "file.read") return { result: Promise.resolve({ dataBase64: Buffer.from(rgba).toString("base64"), offset: 0, size: rgba.length, eof: true }), cancel() {} };
    if (method === "file.delete") {removedFiles.push(String(args.path));return { result: Promise.resolve({ deleted: true }), cancel() {} };}
    throw new Error(`unexpected ${method}`);
  },
}));

const {createImageLoader}=await import("../src/image.tsx");
const leases:string[]=[];
const provider={acquire(source:import("../src/image.tsx").ImageSource) {
  let resolve!:(value:{path:string;release():void})=>void, reject!:(error:Error)=>void;
  const result=new Promise<{path:string;release():void}>((yes,no)=>{resolve=yes;reject=no;});
  pendingDownloads.set(source.url,{resolve:value=>resolve({...value,release(){leases.push(source.url);}}),reject});
  return {result,cancel(){cancelled.push(source.url);reject(new Error("cancelled"));}};
}};
const loader=createImageLoader({sourceProvider:provider});
const remoteImageResourceCounts=()=>loader.stats(),releaseRetainedRemoteImages=()=>loader.releaseRetained();
function bindRemoteImage(props:{url:string;filePath?:string;width:number;height:number;headers?:Record<string,string>;namespace?:string;onError?:(e:unknown)=>void},node:any,onSize?:(w:number,h:number)=>void){
  return loader.bind({...props,source:props,loader},node,onSize);
}
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const roots: Array<() => void> = [];
function mount(url: string): void {
  roots.push(bindRemoteImage({ url, width: 2, height: 1 }, { id: nextNode++ } as never));
}

afterEach(async () => {
  while (roots.length) roots.pop()!();
  releaseRetainedRemoteImages();
  await tick();
  deferredDecode=undefined;
  calls.length = 0;
  cancelled.length = 0;
  pendingDownloads.clear();imageFiles.length=0;removedFiles.length=0;hostMethods.length=0;stored.clear();delete pod.uploadTextureFile;
});

describe("RemoteImage", () => {
  test("shares one bounded load and frees its RGBA8888 texture after the last view leaves", async () => {
    mount("https://img.example/cover.png");
    mount("https://img.example/cover.png");
    expect(remoteImageResourceCounts()).toEqual({ active: 1, queued: 0, cached: 1 });
    await tick();
    pendingDownloads.get("https://img.example/cover.png")!.resolve({ path: "images/a.download" });
    await tick(); await tick();
    expect(calls.filter(call => call[0] === "uploadTexture")).toEqual([
      ["uploadTexture", [...rgba], 2, 1, 3],
    ]);
    expect(calls.filter(call => call[0] === "setImage" && call[2] === 41)).toHaveLength(2);
    roots.pop()!();
    expect(calls.filter(call => call[0] === "freeTexture")).toHaveLength(0);
    roots.pop()!();
    // The finished texture is retained for scroll-back reuse until evicted or released.
    expect(calls.filter(call => call[0] === "freeTexture")).toHaveLength(0);
    expect(remoteImageResourceCounts().cached).toBe(1);
    mount("https://img.example/cover.png");
    expect(calls.filter(call => call[0] === "uploadTexture")).toHaveLength(1);
    roots.pop()!();
    releaseRetainedRemoteImages();
    expect(calls).toContainEqual(["freeTexture", 41]);
    expect(remoteImageResourceCounts().cached).toBe(0);
  });

  test("caps concurrent work and cancels pending work when an offscreen view is disposed", async () => {
    for (let i = 0; i < 4; i++) mount(`https://img.example/${i}.png`);
    expect(remoteImageResourceCounts()).toEqual({ active: 3, queued: 1, cached: 4 });
    roots.pop()!();
    expect(remoteImageResourceCounts()).toEqual({ active: 3, queued: 0, cached: 3 });
    await tick();expect(pendingDownloads.size).toBe(3);
    roots.pop()!(); // Third provider request is active.
    roots.pop()!();await tick();
    expect(cancelled).toContain("https://img.example/1.png");
  });
});

test("native decoded-file upload bypasses file.read and preserves retained-texture release",async()=>{
  pod.uploadTextureFile=(path:string,width:number,height:number,psm:number)=>{calls.push(["uploadTextureFile",path,width,height,psm]);return 53;};
  const release=bindRemoteImage({url:"assets/direct.png",filePath:"notes/assets/direct.png",width:2,height:1},{id:nextNode++} as never);
  await tick();await tick();
  expect(calls).toContainEqual(["uploadTextureFile","images/a.rgba",2,1,3]);
  expect(calls.some(call=>call[0]==="uploadTexture")).toBe(false);
  expect(hostMethods).not.toContain("file.read");
  expect(removedFiles).not.toContain("images/a.rgba");
  expect(calls).toContainEqual(["setImage",expect.any(Number),53]);
  release();releaseRetainedRemoteImages();
  expect(calls).toContainEqual(["freeTexture",53]);
});

test("cancellation during native decoded-file upload frees the returned handle without delivery",async()=>{
  let release=()=>{};
  pod.uploadTextureFile=()=>{release();return 54;};
  release=bindRemoteImage({url:"assets/cancel.png",filePath:"notes/assets/cancel.png",width:2,height:1},{id:nextNode++} as never);
  await tick();await tick();
  expect(calls).toContainEqual(["freeTexture",54]);
  expect(calls.some(call=>call[0]==="setImage"&&call[2]===54)).toBe(false);
  expect(hostMethods).not.toContain("file.read");
  expect(remoteImageResourceCounts()).toEqual({active:0,queued:0,cached:0});
});

test("intrinsic dimensions are retained for newly bound views sharing an uploaded texture",async()=>{
  const sizes:number[][]=[];
  const url="https://img.example/aspect.png";
  roots.push(bindRemoteImage({url,width:2,height:1},{id:nextNode++} as never,(w,h)=>sizes.push([w,h])));
  await tick();pendingDownloads.get(url)!.resolve({path:"images/a.download"});await tick();await tick();
  roots.push(bindRemoteImage({url,width:2,height:1},{id:nextNode++} as never,(w,h)=>sizes.push([w,h])));
  expect(sizes).toEqual([[3,5],[3,5]]);
});

test("download failure reaches all subscribers including a late subscriber",async()=>{
 const errors:unknown[]=[];const props={url:"https://example.test/broken",width:2,height:1,onError:(error:unknown)=>errors.push(error)};
 roots.push(bindRemoteImage(props,{id:nextNode++} as never));roots.push(bindRemoteImage(props,{id:nextNode++} as never));
 await tick();const failure=new Error("network unavailable");pendingDownloads.get(props.url)!.reject(failure);await tick();
 expect(errors).toEqual([failure,failure]);
 roots.push(bindRemoteImage(props,{id:nextNode++} as never));expect(errors).toEqual([failure,failure,failure]);
});

test("local note images decode without downloading and retain the owned source after release",async()=>{const path="notes/assets/kept.png";const release=bindRemoteImage({url:"assets/kept.png",filePath:path,width:2,height:1},{id:nextNode++} as never);await tick();expect(pendingDownloads.size).toBe(0);expect(imageFiles).toEqual([path]);expect(calls.some(c=>c[0]==="uploadTexture")).toBe(true);release();releaseRetainedRemoteImages();await tick();expect(removedFiles).not.toContain(path);expect(removedFiles).toContain("images/a.rgba");expect(remoteImageResourceCounts()).toEqual({active:0,queued:0,cached:0});});

for (const outcome of [-1, NaN, 1.5, undefined, "throw"] as const) {
 test(`native upload ${outcome} consumes decoder file without fallback or double delete`, async () => {
  const errors:unknown[]=[];
  pod.uploadTextureFile=()=>{if(outcome==="throw")throw new Error("rejected");return outcome;};
  roots.push(bindRemoteImage({url:"assets/fail.png",filePath:"notes/assets/fail.png",width:2,height:1,onError:e=>errors.push(e)},{id:nextNode++} as never));
  await tick();await tick();expect(errors).toHaveLength(1);
  expect(hostMethods).not.toContain("file.read");
  expect(calls.some(c=>c[0]==="uploadTexture"||c[0]==="setImage")).toBe(false);
  roots.pop()!();releaseRetainedRemoteImages();await tick();
  expect(removedFiles).not.toContain("images/a.rgba");expect(removedFiles).not.toContain("notes/assets/fail.png");
 });
}

test("provider leases are released, keys include headers namespace and dimensions",async()=>{
  const releases:Array<()=>void>=[];
  for(const source of [{url:"key",headers:{Cookie:"a"}},{url:"key",headers:{Cookie:"b"}},{url:"key",namespace:"other"}]){
    releases.push(bindRemoteImage({...source,width:2,height:1},{id:nextNode++}));
    pendingDownloads.get("key")!.resolve({path:"input"});await tick();
  }
  releases.push(bindRemoteImage({url:"key",width:4,height:1},{id:nextNode++}));
  pendingDownloads.get("key")!.resolve({path:"input"});await tick();
  expect(loader.stats().cached).toBe(4);expect(leases.filter(x=>x==="key")).toHaveLength(4);
  releases.forEach(release=>{release();release();});loader.releaseRetained();
  expect(loader.stats().cached).toBe(0);
});
test("configurable concurrency, zero retention, dispose and host recreation",async()=>{
  const local=createImageLoader({sourceProvider:provider,maxConcurrent:1,retainedBytes:0,maxDimension:64});
  const props={loader:local,source:{url:"local",filePath:"local"},width:100,height:100};
  const a=local.bind(props,{id:nextNode++} as never);
  const b=local.bind({...props,source:{url:"next",filePath:"next"}},{id:nextNode++} as never);
  expect(local.stats()).toEqual({active:1,queued:1,cached:2});await tick();await tick();
  a();a();expect(local.stats().cached).toBe(1);local.dispose();local.dispose();b();
  expect(local.stats().cached).toBe(0);expect(()=>local.bind(props,{id:1} as never)).toThrow("disposed");
  const next=createImageLoader({sourceProvider:provider});const release=next.bind({...props,loader:next},{id:nextNode++} as never);
  await tick();release();next.dispose();expect(next.stats().cached).toBe(0);
});
test("retained LRU evicts the oldest and revive refreshes recency",async()=>{
  const local=createImageLoader({sourceProvider:provider,retainedBytes:2*512*512*4});
  const bind=(url:string)=>local.bind({loader:local,source:{url,filePath:url},width:512,height:512},{id:nextNode++} as never);
  const a=bind("a"),b=bind("b");await tick();a();b();
  const revived=bind("a");revived();const c=bind("c");await tick();c();
  expect(local.stats().cached).toBe(2);const before=imageFiles.length;const again=bind("a");await tick();
  expect(imageFiles.length).toBe(before);again();const evicted=bind("b");await tick();expect(imageFiles.length).toBe(before+1);evicted();local.dispose();
});

test("late decode and late provider success release owned files and leases exactly once",async()=>{
 let resolve!:(value:any)=>void;
 deferredDecode={result:new Promise(yes=>{resolve=yes;}),cancel(){}};
 const local=createImageLoader({sourceProvider:provider});
 const release=local.bind({loader:local,source:{url:"late",filePath:"owned-source"},width:2,height:1},{id:nextNode++} as never);
 release();release();resolve({path:"late.rgba",width:2,height:1});await tick();
 expect(removedFiles.filter(x=>x==="late.rgba")).toHaveLength(1);expect(removedFiles).not.toContain("owned-source");
 expect(calls.filter(x=>x[0]==="uploadTexture")).toHaveLength(0);local.dispose();
 let sourceResolve!:(value:any)=>void,released=0;
 const pending=createImageLoader({sourceProvider:{acquire(){return {result:new Promise<import('../src/image.tsx').ImageLease>(yes=>{sourceResolve=yes;}),cancel(){}};}}});
 const stop=pending.bind({loader:pending,source:{url:"lease"},width:2,height:1},{id:nextNode++} as never);
 stop();sourceResolve({path:"lease-input",release(){released++;}});await tick();expect(released).toBe(1);expect(pending.stats().cached).toBe(0);pending.dispose();
});
test("finished byte-upload cleanup does not delete its output again on texture eviction",async()=>{
 const release=bindRemoteImage({url:"once",filePath:"source",width:2,height:1},{id:nextNode++});await tick();
 release();releaseRetainedRemoteImages();await tick();expect(removedFiles.filter(x=>x==="images/a.rgba")).toHaveLength(1);
});

test("onSize remount receives intrinsic dimensions once without a second live-map publication",async()=>{
 const first={id:nextNode++},replacement={id:nextNode++};let endFirst=()=>{},endReplacement=()=>{},firstCalls=0,replacementCalls=0;
 const props={source:{url:"remount",filePath:"notes/owned.png"},loader,width:205,height:100,fit:"contain" as const};
 endFirst=loader.bind(props,first as never,(w,h)=>{firstCalls++;endFirst();endReplacement=loader.bind({...props,height:101},replacement as never,()=>replacementCalls++);});
 await tick();await tick();expect(firstCalls).toBe(1);expect(replacementCalls).toBe(1);
 expect(calls.filter(c=>c[0]==="uploadTexture")).toHaveLength(1);expect(calls.filter(c=>c[0]==="freeTexture")).toHaveLength(0);
 endReplacement();loader.releaseRetained();expect(calls.filter(c=>c[0]==="freeTexture")).toHaveLength(1);
});
