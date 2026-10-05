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

test("authorization status fails closed on old hosts and preserves legacy wire/result", async () => {
  const {browserAuth}=await import("../src/services.ts");
  const previous=(globalThis as any).pod; const commands:any[]=[];let batch:string|undefined;
  (globalThis as any).pod={capabilities:()=>JSON.stringify(["system.browser.auth"]),emit:(s:string)=>commands.push(JSON.parse(s)),takeEvents:()=>{const b=batch;batch=undefined;return b;}};
  const args={url:"https://www.douyin.com/chat",cookieOrigin:"https://www.douyin.com",requiredCookieNames:["sessionid"]};
  try {
    await expect(browserAuth.authorize({...args,onStatus:()=>{}}).result).rejects.toMatchObject({code:"unsupported"});
    expect(commands).toEqual([]);
    const op=browserAuth.authorize(args);
    expect(commands[0]).toMatchObject({method:"browser.authorize",args});
    const value={cookie:"sessionid=test",origin:args.cookieOrigin};
    batch=JSON.stringify([{t:"service.result",id:commands[0].id,ok:true,value}]);__pumpPodEvents();
    expect(await op.result).toEqual(value);
    for(const target of Object.values(POD_TARGETS))expect(target.capabilities.includes("system.browser.auth.status")).toBe(["android-watch","wearos-watch"].includes(target.id));
  } finally {(globalThis as any).pod=previous;}
});

test("authorization statuses are request-scoped, validated and stop at cancellation or settlement", async () => {
  const {browserAuth}=await import("../src/services.ts");
  const previous=(globalThis as any).pod;const commands:any[]=[];let batch:string|undefined;
  (globalThis as any).pod={capabilities:()=>JSON.stringify(["system.browser.auth.status"]),emit:(s:string)=>commands.push(JSON.parse(s)),takeEvents:()=>{const b=batch;batch=undefined;return b;}};
  const events:any[]=[]; const args={url:"https://www.douyin.com/chat",cookieOrigin:"https://www.douyin.com",requiredCookieNames:[]};
  const pump=(values:any[])=>{batch=JSON.stringify(values);__pumpPodEvents();};
  try {
    const op=browserAuth.authorize({...args,onStatus:v=>events.push(v)});const id=commands.at(-1).id;
    expect(commands.at(-1)).toMatchObject({method:"browser.authorizeWithStatus",args});
    expect(commands.at(-1).args).not.toHaveProperty("onStatus");
    const qr={type:"qr",kind:"login",mime:"image/png",imageBase64:"cXI="};
    const second={...qr,kind:"secondVerify",mime:"image/jpeg"};
    const status=(value:any,eventId=id)=>({t:"browser.auth.status",id:eventId,value});
    pump([status(qr,id+100),status({...qr,mime:"text/plain"}),status({...qr,imageBase64:"bad!"}),status({...qr,imageBase64:"A".repeat(256*1024+4)}),status({type:"pageState",loading:"yes"})]);
    expect(events).toEqual([]);
    const sequence=[{type:"pageState",loading:false,loginPanelVisible:true},qr,{type:"verifying",active:true},{type:"qrCleared"},{type:"verifying",active:false},second];
    pump(sequence.map(v=>status(v)));expect(events).toEqual(sequence);
    const other=browserAuth.authorize({...args,onStatus:()=>{throw Error("observer");}});const otherId=commands.at(-1).id;
    pump([status(qr,otherId),{t:"service.result",id:otherId,ok:false,code:"busy",message:"busy"}]);
    await expect(other.result).rejects.toMatchObject({code:"busy"});
    op.cancel();op.cancel();await expect(op.result).rejects.toMatchObject({code:"cancelled"});
    expect(events.at(-1)).toEqual({type:"cancelled"});const count=events.length;
    pump([status(second),status({type:"cancelled"}),{t:"service.result",id,ok:true,value:{cookie:"late"}}]);expect(events.length).toBe(count);
    for(const ok of [true,false]){
      const done=browserAuth.authorize({...args,onStatus:v=>events.push(v)});const doneId=commands.at(-1).id;
      pump([...(ok?[]:[status({type:"cancelled"},doneId)]),{t:"service.result",id:doneId,ok,value:{cookie:"test",origin:args.cookieOrigin},code:"cancelled",message:"cancelled"},status(qr,doneId)]);
      if(ok)expect(await done.result).toMatchObject({cookie:"test"});else await expect(done.result).rejects.toMatchObject({code:"cancelled"});
      const endCount=events.length;done.cancel();pump([status(qr,doneId)]);expect(events.length).toBe(endCount);
    }
  } finally {(globalThis as any).pod=previous;}
});

test("native Douyin snapshot prioritizes JPEG, reveals uc-second-verify and clears vanished QR", async () => {
  const {DOUYIN_AUTH_STATUS_SCRIPT}=await import("../src/services.ts");
  let verifying=false,panel=true,images:string[]=[];
  const document={readyState:"complete",getElementById:()=>verifying?{tagName:"DIV"}:null,
    querySelector:()=>panel?{}:null,querySelectorAll:(selector:string)=>selector==="div"?[]:images.map(src=>({getAttribute:()=>src}))};
  const snapshot=()=>new Function("document",`return ${DOUYIN_AUTH_STATUS_SCRIPT}`)(document);
  images=["data:image/png;base64,cG5n"];expect(snapshot().qr).toEqual({kind:"login",mime:"image/png",imageBase64:"cG5n"});
  images.push("data:image/jpeg;base64,anBlZw==");expect(snapshot().qr.kind).toBe("secondVerify");
  verifying=true;expect(snapshot()).toMatchObject({qr:null,verifying:true,loginPanelVisible:true});
  verifying=false;expect(snapshot().qr.kind).toBe("secondVerify");
  images=[];panel=false;expect(snapshot()).toEqual({qr:null,verifying:false,loading:false,loginPanelVisible:false});
});
