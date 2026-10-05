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

test("WebView services require their own capabilities, including on older browser hosts", async () => {
  const { browser } = await import("../src/services.ts");
  const previous = (globalThis as {pod?: unknown}).pod;
  const commands: string[] = [];
  (globalThis as {pod?: unknown}).pod = { capabilities: () => '["system.browser","system.browser.auth"]', emit: (s: string) => commands.push(s) };
  try {
    await expect(browser.availability().result).rejects.toMatchObject({code: "unsupported"});
    await expect(browser.clearSiteData({origins: ["https://douyin.com"]}).result).rejects.toMatchObject({code: "unsupported"});
    expect(commands).toEqual([]);
  } finally { (globalThis as {pod?: unknown}).pod = previous; }
});

test("WebView services round-trip status and per-origin partial results without claiming global clearance", async () => {
  const { browser } = await import("../src/services.ts");
  const previous = (globalThis as {pod?: unknown}).pod;
  const commands: Record<string, any>[] = []; let batch: string | undefined;
  (globalThis as {pod?: unknown}).pod = {
    capabilities: () => '["system.browser.availability","system.browser.siteData"]',
    emit: (s: string) => commands.push(JSON.parse(s)),
    takeEvents: () => { const value = batch; batch = undefined; return value; },
  };
  const respond = (value: unknown, ok = true) => {
    batch = JSON.stringify([{t: "service.result", id: commands.at(-1)!.id, ok, ...(ok ? {value} : {code: "invalid_argument", message: "Only bare HTTPS origins are allowed"})}]);
    __pumpPodEvents();
  };
  try {
    for (const status of ["available", "no_provider", "initialization_failed"] as const) {
      const op = browser.availability();
      expect(commands.at(-1)).toMatchObject({method: "browser.availability", args: {}});
      respond({status}); expect(await op.result).toEqual({status});
    }
    const origins = ["https://www.douyin.com", "https://douyin.com"];
    const op = browser.clearSiteData({origins});
    expect(commands.at(-1)).toMatchObject({method: "browser.clearSiteData", args: {origins}});
    const result = origins.map(origin => ({origin, cookies: {status: "remaining" as const, remainingNames: ["session"]}, webStorage: {status: "deletion_requested" as const}}));
    respond(result); expect(await op.result).toEqual(result);
    const invalid = browser.clearSiteData({origins: ["http://douyin.com"]});
    respond(null, false); await expect(invalid.result).rejects.toMatchObject({code: "invalid_argument"});
    const cancelled = browser.availability(); cancelled.cancel();
    await expect(cancelled.result).rejects.toMatchObject({code: "cancelled"});
    expect(commands.at(-1)!.t).toBe("service.cancel");
  } finally { (globalThis as {pod?: unknown}).pod = previous; }
});

test("embedded browser fails closed on old hosts while the legacy request remains unchanged", async () => {
  const {browser} = await import("../src/services.ts");
  const previous = (globalThis as {pod?: unknown}).pod;
  const commands: any[] = []; let batch: string | undefined;
  (globalThis as {pod?: unknown}).pod = {
    capabilities: () => '["system.browser","system.browser.auth"]',
    emit: (s: string) => commands.push(JSON.parse(s)),
    takeEvents: () => {const b=batch;batch=undefined;return b;},
  };
  try {
    await expect(browser.open("https://example.org", {mode:"inApp"}).result).rejects.toMatchObject({code:"unsupported"});
    expect(commands).toEqual([]);
    const old = browser.open("https://example.org");
    expect(commands[0]).toMatchObject({method:"browser.open",args:{url:"https://example.org"}});
    batch=JSON.stringify([{t:"service.result",id:commands[0].id,ok:true,value:null}]);__pumpPodEvents();
    await old.result;
  } finally {(globalThis as {pod?: unknown}).pod=previous;}
});

test("embedded browser forwards local paths, scale and reader theme and surfaces preflight failure", async () => {
  const {browser} = await import("../src/services.ts");
  const previous = (globalThis as {pod?: unknown}).pod;
  const commands: any[] = []; let batch: string | undefined;
  (globalThis as {pod?: unknown}).pod = {
    capabilities: () => '["system.browser.inApp"]', emit: (s: string) => commands.push(JSON.parse(s)),
    takeEvents: () => {const b=batch;batch=undefined;return b;},
  };
  try {
    for (const scaleMode of ["standard","expanded","shrunk"] as const) {
      const options={mode:"inApp" as const,scaleMode,theme:{dark:true,backgroundColor:"#101010",textZoom:120,fontFamily:"serif" as const}};
      const op=browser.open(" /data/user/0/test/files/local page.html ", options);
      expect(commands.at(-1)).toMatchObject({method:"browser.openInApp",args:{url:"/data/user/0/test/files/local page.html",...options}});
      batch=JSON.stringify([{t:"service.result",id:commands.at(-1).id,ok:true,value:null}]);__pumpPodEvents();await op.result;
    }
    for (const code of ["offline","webview_unavailable","busy"]) {
      const op=browser.open("https://example.org",{mode:"inApp"});
      batch=JSON.stringify([{t:"service.result",id:commands.at(-1).id,ok:false,code,message:code}]);__pumpPodEvents();
      await expect(op.result).rejects.toMatchObject({code});
    }
    const op=browser.open("file:///data/local/page.html",{mode:"inApp"}); op.cancel();
    await expect(op.result).rejects.toMatchObject({code:"cancelled"});
    expect(commands.at(-1).t).toBe("service.cancel");
  } finally {(globalThis as {pod?: unknown}).pod=previous;}
});

test("embedded browser rejects invalid options and executable URLs before emitting", async () => {
  const {browser} = await import("../src/services.ts");
  for (const url of ["javascript:alert(1)","data:text/html,hello","content://test","", "/bad\u0000path"]) {
    expect(()=>browser.open(url,{mode:"inApp"})).toThrow();
  }
  for (const options of [null, {}, {mode:"external"}, {mode:"inApp",scaleMode:"giant"}, {mode:"inApp",theme:{dark:"true"}}, {mode:"inApp",theme:{dark:true,textZoom:NaN}}, {mode:"inApp",theme:{dark:true,textZoom:20}}, {mode:"inApp",theme:{dark:true,fontFamily:"bad"}}, {mode:"inApp",theme:{dark:true,backgroundColor:"red"}}, {mode:"inApp",unexpected:true}]) {
    expect(()=>browser.open("https://example.org",options as any)).toThrow();
  }
  for (const target of Object.values(POD_TARGETS)) expect(target.capabilities.includes("system.browser.inApp")).toBe(["android-watch","wearos-watch"].includes(target.id));
});

test("multi-session video is separately advertised and fails closed on legacy video hosts", async () => {
  const {video,videoSessions}=await import("../src/services.ts");
  const previous=(globalThis as {pod?:unknown}).pod;
  const commands:any[]=[];
  (globalThis as {pod?:unknown}).pod={
    takeEvents:()=>undefined,
    capabilities:()=> '["media.video"]',
    emit:(line:string)=>commands.push(JSON.parse(line)),
  };
  try {
    const bounds={x:0,y:0,width:240,height:240};
    await expect(videoSessions.prepare({sessionId:"next",url:"next.mp4",bounds,prewarm:true}).result).rejects.toMatchObject({code:"unsupported"});
    await expect(videoSessions.capabilities()).rejects.toMatchObject({code:"unsupported"});
    expect(commands).toEqual([]);
    const legacy=video.prepare({url:"legacy.mp4",bounds});
    expect(commands[0]).toMatchObject({method:"video.prepare",args:{url:"legacy.mp4",bounds}});
    legacy.cancel();await expect(legacy.result).rejects.toMatchObject({code:"cancelled"});
  } finally {(globalThis as {pod?:unknown}).pod=previous;}
  for(const id of ["android-watch","wearos-watch"] as const)expect(POD_TARGETS[id].capabilities).toContain("media.video.sessions");
  for(const id of ["watchos-watch","harmonyos-watch"] as const)expect(POD_TARGETS[id].capabilities).not.toContain("media.video.sessions");
});

test("multi-session video preserves ids and atomically describes prewarm promotion and vertical drag", async () => {
  const {videoSessions}=await import("../src/services.ts");
  const previous=(globalThis as {pod?:unknown}).pod;
  const commands:any[]=[];let batch:string|undefined;
  (globalThis as {pod?:unknown}).pod={
    takeEvents:()=>{const value=batch;batch=undefined;return value;},
    capabilities:()=> '["media.video.sessions"]',
    emit:(line:string)=>commands.push(JSON.parse(line)),
  };
  const reply=(value:unknown)=>{batch=JSON.stringify([{t:"service.result",id:commands.at(-1).id,ok:true,value}]);__pumpPodEvents();};
  try {
    const capabilities=videoSessions.capabilities();
    expect(commands.at(-1)).toMatchObject({method:"video.session.capabilities",args:{}});
    const capabilityValue={capability:"media.video.sessions",maxSessions:2,decoderLimit:2,decoderInUse:0,targetBufferBytesPerSession:6291456,targetBufferBytesTotal:12582912,prewarmMode:"hidden_first_frame",surfacePromotion:true,verticalTransition:true,surfaceComposition:"android_texture_view",backgroundPlayback:false};
    reply(capabilityValue);await expect(capabilities).resolves.toEqual(capabilityValue);

    const bounds={x:0,y:0,width:240,height:240,translateY:0,roundClip:{cx:120,cy:120,radius:120}};
    const prepare=videoSessions.prepare({sessionId:"next",url:"douyin/next.mp4",bounds,prewarm:true,autoplay:false,muted:false,loop:true});
    expect(commands.at(-1)).toMatchObject({method:"video.session.prepare",args:{sessionId:"next",prewarm:true,bounds}});
    const prewarmed={ok:true,sessionId:"next",state:"prewarmed",positionMs:0,durationMs:1000,width:16,height:16,muted:true,volume:1,prewarming:true,presented:false,hasRenderedFirstFrame:true,targetBufferBytes:6291456};
    reply(prewarmed);await expect(prepare.result).resolves.toEqual(prewarmed);

    const drag=videoSessions.transition({fromSessionId:"current",toSessionId:"next",progress:.375,direction:"up",bounds});
    expect(commands.at(-1)).toMatchObject({method:"video.session.transition",args:{fromSessionId:"current",toSessionId:"next",progress:.375,direction:"up",bounds}});
    const dragged={fromSessionId:"current",toSessionId:"next",progress:.375,direction:"up",fromOffsetYPx:-90,toOffsetYPx:150,composited:true};
    reply(dragged);await expect(drag).resolves.toEqual(dragged);

    const promote=videoSessions.promote("next",{replaceSessionId:"current",bounds,autoplay:true});
    expect(commands.at(-1)).toMatchObject({method:"video.session.promote",args:{sessionId:"next",replaceSessionId:"current",bounds,autoplay:true}});
    reply({...prewarmed,state:"playing",muted:false,prewarming:false,presented:true});
    await expect(promote).resolves.toMatchObject({sessionId:"next",state:"playing",hasRenderedFirstFrame:true,presented:true});
  } finally {(globalThis as {pod?:unknown}).pod=previous;}
});
