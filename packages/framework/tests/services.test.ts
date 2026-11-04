import { afterEach, beforeEach, describe, expect, test } from "bun:test";

const events: unknown[] = [];
const emitted: Record<string, unknown>[] = [];

(globalThis as { pod?: unknown }).pod = {
  takeEvents: () => events.length ? JSON.stringify(events.splice(0)) : undefined,
  emit: (line: string) => emitted.push(JSON.parse(line) as Record<string, unknown>),
  capabilities: () => JSON.stringify(["data.sqlite", "input.text"]),
};

const api = await import("../src/watch.ts");
const { secureStorage, battery, debugPerformance, application, sharing, displayPower, silencePlayback, HostServiceError, hostOperation, sqlite, browser, browserAuth, mediaVolume, speech, timeInput, textInput, textLayout, fileChunks, uri, rfcomm, tcp, byteStreams, webSockets, discovery, onNetworkChange } = await import("../src/services.ts");
const host = (globalThis as unknown as { pod: { capabilities: () => string } }).pod;

beforeEach(() => {
  host.capabilities = () => JSON.stringify(["data.sqlite", "input.text"]);
});
afterEach(() => { events.length = 0; emitted.length = 0; });

describe("host services", () => {
 test('diagnostic controls stay private and real status callbacks retain settlement/order with slow labels',async()=>{
  const realClock=Date.now;let now=0;const seen:string[]=[];
  const remove=api.__onHostEvent(event=>seen.push(String(event.t)));
  host.capabilities=()=>JSON.stringify(['system.browser.auth.status']);
  try{
   Date.now=()=>now;
   const operation=browserAuth.authorize({url:'https://example.com',cookieOrigin:'https://example.com',requiredCookieNames:['sid'],qrExtractionScript:'null',onStatus:()=>{now+=75;}});
   const request=emitted.at(-1)!;
   events.push({t:'diagnostic.jsStack',enabled:true},{t:'browser.auth.status',id:request.id,value:{type:'pageState',loading:true,private:'PRIVATE_VALUE'}});
   api.__pumpPodEvents();
   expect(seen).toEqual(['browser.auth.status']);
   const records=emitted.filter(x=>x.t==='diagnostic.serviceCallback');
   expect(records).toEqual([{t:'diagnostic.serviceCallback',service:'browser.authorizeWithStatus',callback:'status',elapsedMs:75}]);
   expect(JSON.stringify(records)).not.toContain('PRIVATE');
   events.push({t:'service.result',id:request.id,ok:true,value:{cookie:'PRIVATE_COOKIE',origin:'https://example.com'}});api.__pumpPodEvents();
   await expect(operation.result).resolves.toEqual({cookie:'PRIVATE_COOKIE',origin:'https://example.com'});
   expect(seen).toEqual(['browser.auth.status','service.result']);
  }finally{Date.now=realClock;events.push({t:'diagnostic.jsStack',enabled:false});api.__pumpPodEvents();remove();}
 });
 test('secure leases require data.secure and expose nonblocking ownership without secrets',async()=>{
  await expect(secureStorage.acquire('account')).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
  host.capabilities=()=>JSON.stringify(['data.secure']);
  for(const acquired of [true,false]){const task=secureStorage.acquire('account'),request=emitted.at(-1)!;expect(request).toMatchObject({method:'secure.acquire',args:{key:'account'}});events.push({t:'service.result',id:request.id,ok:true,value:{acquired}});api.__pumpPodEvents();expect(await task).toBe(acquired);}
  const task=secureStorage.release('account'),request=emitted.at(-1)!;expect(request).toMatchObject({method:'secure.release',args:{key:'account'}});events.push({t:'service.result',id:request.id,ok:true,value:{ok:true}});api.__pumpPodEvents();await task;
 });
 test('secure compare-and-set uses a distinct capability-gated atomic host operation',async()=>{
  await expect(secureStorage.compareAndSet('intent','old',undefined)).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
  host.capabilities=()=>JSON.stringify(['data.secure']);
  for(const updated of [false,true]){const result=secureStorage.compareAndSet('intent','old',undefined),request=emitted.at(-1)!;expect(request).toMatchObject({method:'secure.compareAndSet',args:{key:'intent',expected:'old',value:null}});events.push({t:'service.result',id:request.id,ok:true,value:{updated}});api.__pumpPodEvents();expect(await result).toBe(updated);}
  const unsupported=secureStorage.compareAndSet('intent',undefined,'next'),request=emitted.at(-1)!;events.push({t:'service.result',id:request.id,ok:false,code:'unknown_method',message:'unknown_method'});api.__pumpPodEvents();await expect(unsupported).rejects.toThrow();
 });
 test('afterPaint requests commits and cancellation remains owned by the caller',async()=>{
  host.capabilities=()=>JSON.stringify(['runtime.timer']);const {afterPaint}=await import('../src/render.ts');
  const op=afterPaint(),request=emitted.at(-1)!;expect(request).toMatchObject({method:'runtime.afterPaint',args:{}});
  events.push({t:'service.result',id:request.id,ok:true,value:{committed:true}});api.__pumpPodEvents();await expect(op.result).resolves.toEqual({committed:true});
  const cancelled=afterPaint();cancelled.cancel();await expect(cancelled.result).rejects.toMatchObject({code:'cancelled'});expect(emitted.at(-1)?.t).toBe('service.cancel');
 });

 test('secure batch is capability gated and submits one transaction without logging values',async()=>{
  await expect(secureStorage.update({key:'private'})).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
  host.capabilities=()=>JSON.stringify(['data.secure']);const result=secureStorage.update({key:'private',removed:null});const request=emitted.at(-1)!;expect(request).toMatchObject({method:'secure.update',args:{values:{key:'private',removed:null}}});
  events.push({t:'service.result',id:request.id,ok:true,value:{ok:true}});api.__pumpPodEvents();await result;expect(emitted).toHaveLength(1);
 });
  test('battery capacity is capability gated and preserves unavailable null',async()=>{
    await expect(battery.percent()).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(['device.battery']);
    for(const value of [0,76,100,null]){const result=battery.percent(),request=emitted.at(-1)!;expect(request).toMatchObject({method:'battery.percent',args:{}});events.push({t:'service.result',id:request.id,ok:true,value});api.__pumpPodEvents();await expect(result).resolves.toBe(value);}
  });

  test("inline text changes are scoped to the opened session and stop on close",async()=>{
    const changes:string[]=[];let submits=0;
    const pending=textInput.openInline("旧词",{x:18,y:44,width:197,height:56},value=>changes.push(value),()=>submits++);
    const open=emitted.at(-1)!;
    expect(open).toMatchObject({method:"input.inline.open",args:{value:"旧词",x:18,y:44,width:197,height:56}});
    events.push({t:"input.inline.change",session:open.id,value:"先到的更新"},{t:"service.result",id:open.id,ok:true,value:open.id});api.__pumpPodEvents();
    const session=await pending;
    expect(changes).toEqual(["先到的更新"]);
    events.push({t:"input.inline.change",session:Number(open.id)+1,value:"错误"},{t:"input.inline.change",session:open.id,value:"新词"},{t:"input.inline.submit",session:open.id});api.__pumpPodEvents();
    expect(changes).toEqual(["先到的更新","新词"]);expect(submits).toBe(1);
    const setting=session.setValue("恢复旧词"),set=emitted.at(-1)!;
    expect(set).toMatchObject({method:"input.inline.set",args:{session:open.id,value:"恢复旧词"}});
    events.push({t:"service.result",id:set.id,ok:true,value:null});api.__pumpPodEvents();await setting;
    const closed=session.close(),close=emitted.at(-1)!;
    expect(close).toMatchObject({method:"input.inline.close",args:{session:open.id}});
    events.push({t:"input.inline.change",session:open.id,value:"过期"},{t:"service.result",id:close.id,ok:true,value:null});api.__pumpPodEvents();await closed;
    expect(changes).toEqual(["先到的更新","新词"]);
    await session.close();expect(emitted.at(-1)).toBe(close);
  });
  test("inline editor requests multiline text while retaining a second field",async()=>{
    const pending=textInput.openInline("第一行\n第二行",{x:18,y:92,width:197,height:95},()=>{},undefined,{hint:"正文",fontAsset:"fonts/watch-sans.ttf",fontWeight:400,includeFontPadding:false,multiline:true,keepExisting:true,selectionStart:3,textAlign:"center",horizontalPaddingDp:0,verticalPaddingDp:0,autoFocus:false,document:{id:"test-note",role:"body",viewportHeight:135,unfocusedCaretColor:"#ff6026"}});
    const open=emitted.at(-1)!;
    expect(open).toMatchObject({method:"input.inline.open",args:{value:"第一行\n第二行",multiline:true,keepExisting:true,hint:"正文",fontAsset:"fonts/watch-sans.ttf",fontWeight:400,includeFontPadding:false,selectionStart:3,textAlign:"center",horizontalPaddingDp:0,verticalPaddingDp:0,autoFocus:false,document:{id:"test-note",role:"body",viewportHeight:135,unfocusedCaretColor:"#ff6026"}}});
    events.push({t:"service.result",id:open.id,ok:true,value:open.id});api.__pumpPodEvents();
    const session=await pending;
    const closed=session.close(),close=emitted.at(-1)!;
    events.push({t:"service.result",id:close.id,ok:true,value:null});api.__pumpPodEvents();await closed;
  });
  test("build information is lifecycle gated and reports the actual host debug flag",async()=>{
    await expect(application.buildInfo()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["host.lifecycle"]);
    for(const debugBuild of [true,false]){
      const result=application.buildInfo(),request=emitted.at(-1)!;
      expect(request).toMatchObject({method:"app.buildInfo",args:{}});
      events.push({t:"service.result",id:request.id,ok:true,value:{debugBuild}});api.__pumpPodEvents();
      await expect(result).resolves.toEqual({debugBuild});
    }
  });
  test("native debug performance forwards ownership tokens and propagates release rejection",async()=>{
    await expect(debugPerformance.start("perf_large_list")).rejects.toMatchObject({code:"unsupported"});
    host.capabilities=()=>JSON.stringify(["host.lifecycle"]);
    const start=debugPerformance.start("perf_large_list"),open=emitted.at(-1)!;
    expect(open).toMatchObject({method:"debug.performance.start",args:{scenario:"perf_large_list"}});
    events.push({t:"service.result",id:open.id,ok:true,value:{sessionId:7,scenario:"perf_large_list",frames:0}});api.__pumpPodEvents();expect((await start).sessionId).toBe(7);
    const read=debugPerformance.read(7),request=emitted.at(-1)!;expect(request.args).toEqual({sessionId:7});
    events.push({t:"service.result",id:request.id,ok:true,value:{sessionId:7,frames:12,jank:2}});api.__pumpPodEvents();expect((await read).frames).toBe(12);
    const stop=debugPerformance.stop(7),close=emitted.at(-1)!;expect(close).toMatchObject({method:"debug.performance.stop",args:{sessionId:7}});
    events.push({t:"service.result",id:close.id,ok:false,error:{code:"permission_denied",message:"debug only"}});api.__pumpPodEvents();await expect(stop).rejects.toThrow();
  });
  test("activity state has separate saved and retained channels behind lifecycle capability",async()=>{
    await expect(application.activityState.read()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["host.lifecycle"]);
    const state={saved:{agreed:true,unknown:{keep:1}},retained:{page:2}};
    const write=application.activityState.write(state);expect(emitted[0]).toMatchObject({method:"app.activityState.write",args:state});
    events.push({t:"service.result",id:emitted[0].id,ok:true,value:null});api.__pumpPodEvents();await write;
    const read=application.activityState.read();expect(emitted[1]).toMatchObject({method:"app.activityState.read",args:{}});
    events.push({t:"service.result",id:emitted[1].id,ok:true,value:state});api.__pumpPodEvents();await expect(read).resolves.toEqual(state);
  });
  test("task exit is lifecycle gated and carries no target package",async()=>{
    await expect(application.finishTask()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["host.lifecycle"]);
    const result=application.finishTask();expect(emitted[0]).toMatchObject({method:"app.finishTask",args:{}});
    events.push({t:"service.result",id:emitted[0].id,ok:true,value:null});api.__pumpPodEvents();await expect(result).resolves.toBeNull();
  });
  test("network snapshot is read-only and capability gated",async()=>{
    await expect(discovery.networkSnapshot()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["net.discovery"]);const result=discovery.networkSnapshot();expect(emitted[0]).toMatchObject({method:"discovery.networkSnapshot",args:{}});
    const value={active:true,internet:true,validated:true,wifi:false,cellular:false,bluetooth:true};events.push({t:"service.result",id:emitted[0].id,ok:true,value});api.__pumpPodEvents();await expect(result).resolves.toEqual(value);
  });
  test("sharing requires capability and forwards the unmodified chooser text",async()=>{
    await expect(sharing.environment()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["system.share"]);
    const environment=sharing.environment();expect(emitted[0]).toMatchObject({method:"share.environment",args:{}});
    events.push({t:"service.result",id:emitted[0].id,ok:true,value:{sdkInt:30,wearOs:false}});api.__pumpPodEvents();await expect(environment).resolves.toEqual({sdkInt:30,wearOs:false});
    const op=sharing.text(" 标题 \n URL ","分享");expect(emitted[1]).toMatchObject({method:"share.text",args:{text:" 标题 \n URL ",chooserTitle:"分享"}});
    op.cancel();await expect(op.result).rejects.toMatchObject({code:"cancelled"});expect(emitted[2]).toMatchObject({t:"service.cancel",id:emitted[1].id});
  });
  test("network callbacks are scoped, work before subscription acknowledgement, and stop on disposal",async()=>{
    host.capabilities=()=>JSON.stringify(["net.discovery"]);const received:unknown[]=[];const stop=onNetworkChange(event=>received.push(event)),request=emitted.at(-1)!;const subscriptionId=(request.args as {subscriptionId:string}).subscriptionId;
    events.push({t:"network.changed",subscriptionId:"other",value:{source:"wifi",reason:"lost"}},{t:"network.changed",subscriptionId,value:{source:"default",reason:"available"}});api.__pumpPodEvents();expect(received).toEqual([{source:"default",reason:"available"}]);
    events.push({t:"service.result",id:request.id,ok:true,value:{subscriptionId}});api.__pumpPodEvents();await Promise.resolve();stop();const release=emitted.at(-1)!;expect(release).toMatchObject({method:"discovery.networkUnsubscribe",args:{subscriptionId}});
    events.push({t:"network.changed",subscriptionId,value:{source:"wifi",reason:"lost"}},{t:"service.result",id:release.id,ok:true,value:{released:true}});api.__pumpPodEvents();await Promise.resolve();expect(received).toHaveLength(1);
  });
  test("disposing a pending network subscription cancels registration and releases its known id",async()=>{
    host.capabilities=()=>JSON.stringify(["net.discovery"]);let delivered=0,errors=0;const stop=onNetworkChange(()=>delivered++,()=>errors++),request=emitted.at(-1)!;const subscriptionId=(request.args as {subscriptionId:string}).subscriptionId;stop();
    expect(emitted.at(-2)).toMatchObject({t:"service.cancel",id:request.id});const release=emitted.at(-1)!;expect(release).toMatchObject({method:"discovery.networkUnsubscribe",args:{subscriptionId}});
    events.push({t:"service.result",id:request.id,ok:true,value:{subscriptionId}},{t:"service.result",id:release.id,ok:true,value:{released:true}},{t:"network.changed",subscriptionId,value:{source:"default",reason:"lost"}});api.__pumpPodEvents();await Promise.resolve();expect(delivered).toBe(0);expect(errors).toBe(0);
  });
  test("unavailable network observation reports failure without a native request",async()=>{
    let failure:unknown;const stop=onNetworkChange(()=>{throw new Error("unexpected network event");},error=>{failure=error;});await Promise.resolve();expect(failure).toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);stop();expect(emitted).toHaveLength(0);
  });

  test("BLE discovery exposes separate permissions and a cancellable characteristic read",async()=>{
    await expect(discovery.status()).rejects.toMatchObject({code:"unsupported"});
    host.capabilities=()=>JSON.stringify(["net.discovery"]);
    const permission=discovery.requestPermission(),permissionRequest=emitted.at(-1)!;expect(permissionRequest).toMatchObject({method:"discovery.requestPermission",args:{}});
    events.push({t:"service.result",id:permissionRequest.id,ok:true,value:"required"});api.__pumpPodEvents();await expect(permission).resolves.toBe("required");
    const operation=discovery.bleReadCharacteristic({advertisedServiceUuid:"advertised",manufacturerId:65535,manufacturerDataBase64:"AAAA",serviceUuid:"service",characteristicUuid:"read"}),request=emitted.at(-1)!;
    expect(request).toMatchObject({method:"discovery.bleReadCharacteristic",args:{bondedOnly:true,scanTimeoutMs:8000,readTimeoutMs:12000}});
    operation.cancel();await expect(operation.result).rejects.toMatchObject({code:"cancelled"});expect(emitted.at(-1)).toMatchObject({t:"service.cancel",id:request.id});
  });

  test("mDNS is capability gated and cancellation reaches the host",async()=>{
    await expect(discovery.mdns({serviceType:"_test._tcp.",expectedPort:49177}).result).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["net.discovery"]);
    const operation=discovery.mdns({serviceType:"_test._tcp.",namePrefix:"Test-",expectedPort:49177}),request=emitted.at(-1)!;
    expect(request).toMatchObject({method:"discovery.mdns",args:{serviceType:"_test._tcp.",namePrefix:"Test-",expectedPort:49177,windowMs:1500}});
    operation.cancel();await expect(operation.result).rejects.toMatchObject({code:"cancelled"});expect(emitted.at(-1)).toMatchObject({t:"service.cancel",id:request.id});
  });

  test("committed SQLite change counts use the existing transaction capability",async()=>{
    const statements=[{sql:"UPDATE rows SET value=1 WHERE id=2"}];
    const promise=sqlite.transactionChanges(statements),request=emitted.at(-1)!;
    expect(request).toMatchObject({method:"sql.transaction",args:{statements,reportChanges:true}});
    events.push({t:"service.result",id:request.id,ok:true,value:[0]});api.__pumpPodEvents();await expect(promise).resolves.toEqual([0]);
  });
  test("URI parsing distinguishes invalid syntax from host transport failure",async()=>{
    await expect(uri.parse("bad URI")).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["data.uri"]);
    const promise=uri.parse("bad URI"),request=emitted.at(-1)!;
    const result={valid:false,scheme:null,host:null,rawPath:null,rawQuery:null};
    expect(request).toMatchObject({method:"uri.parse",args:{value:"bad URI"}});
    events.push({t:"service.result",id:request.id,ok:true,value:result});api.__pumpPodEvents();await expect(promise).resolves.toEqual(result);
  });
  test("Android URI syntax is explicit while the default preserves Java parsing",async()=>{
    host.capabilities=()=>JSON.stringify(["data.uri"]);
    const promise=uri.parse("http://例子.测试/rss","android"),request=emitted.at(-1)!;
    expect(request).toMatchObject({method:"uri.parse",args:{value:"http://例子.测试/rss",syntax:"android"}});
    const result={valid:true,scheme:"http",host:"例子.测试",rawPath:"/rss",rawQuery:null};
    events.push({t:"service.result",id:request.id,ok:true,value:result});api.__pumpPodEvents();await expect(promise).resolves.toEqual(result);
  });
  test("RFCOMM APIs are capability gated and preserve stream limits and cancellation",async()=>{
    await expect(rfcomm.status()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["net.rfcomm","net.stream"]);
    const permission=rfcomm.requestPermission(),permissionRequest=emitted.at(-1)!;expect(permissionRequest).toMatchObject({method:"rfcomm.requestPermission",args:{}});
    events.push({t:"service.result",id:permissionRequest.id,ok:true,value:"granted"});api.__pumpPodEvents();await expect(permission.result).resolves.toBe("granted");
    const read=byteStreams.read("stream-1",23,400),request=emitted.at(-1)!;expect(request).toMatchObject({method:"stream.read",args:{id:"stream-1",maxBytes:23,timeoutMs:400}});
    read.cancel();await expect(read.result).rejects.toMatchObject({code:"cancelled"});expect(emitted.at(-1)).toMatchObject({t:"service.cancel",id:request.id});
    const write=byteStreams.write("stream-1",new Uint8Array([1,2]),900),sent=emitted.at(-1)!;expect(sent).toMatchObject({method:"stream.write",args:{id:"stream-1",dataBase64:"AQI=",timeoutMs:900}});
    events.push({t:"service.result",id:sent.id,ok:true,value:{written:2}});api.__pumpPodEvents();await expect(write.result).resolves.toEqual({written:2});
  });
  test("TCP listeners require their own capability and cancellable accept is dispatched separately",async()=>{
    await expect(tcp.listen().result).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["net.tcp","net.stream"]);
    const listen=tcp.listen(0,"127.0.0.1"),request=emitted.at(-1)!;expect(request).toMatchObject({method:"tcp.listen",args:{port:0,address:"127.0.0.1"}});
    events.push({t:"service.result",id:request.id,ok:true,value:{id:"tcp-listener-1",port:1234}});api.__pumpPodEvents();await expect(listen.result).resolves.toEqual({id:"tcp-listener-1",port:1234});
    const accept=tcp.accept("tcp-listener-1",1000),pending=emitted.at(-1)!;accept.cancel();await expect(accept.result).rejects.toMatchObject({code:"cancelled"});expect(emitted.at(-1)).toMatchObject({t:"service.cancel",id:pending.id});
  });
  test("WebSocket negotiation requires its own capability and remains cancellable",async()=>{
    await expect(webSockets.connect("ws://127.0.0.1:9999/sync").result).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
    host.capabilities=()=>JSON.stringify(["net.websocket","net.stream"]);
    const op=webSockets.connect("ws://127.0.0.1:9999/sync",{headers:{"X-WatchRSS-Protocol":"2"},bindLocalWifi:true,initialTextHandshake:true,timeoutMs:1000}),request=emitted.at(-1)!;
    expect(request).toMatchObject({method:"websocket.connect",args:{url:"ws://127.0.0.1:9999/sync",headers:{"X-WatchRSS-Protocol":"2"},bindLocalWifi:true,initialTextHandshake:true,timeoutMs:1000}});
    events.push({t:"service.result",id:request.id,ok:true,value:{id:"stream-1"}});api.__pumpPodEvents();await expect(op.result).resolves.toEqual({id:"stream-1"});
    const read=webSockets.readText("stream-1",50),pending=emitted.at(-1)!;read.cancel();await expect(read.result).rejects.toMatchObject({code:"cancelled"});expect(emitted.at(-1)).toMatchObject({t:"service.cancel",id:pending.id});
  });
  test("file codecs and text readers preserve limits, fallback policy and stream handles",async()=>{
    const calls=[
      {call:()=>fileChunks.htmlText("html",32),method:"file.htmlText",args:{path:"html",maxOutputBytes:32},value:{path:"temporary",size:32,sha256:"h"}},
      {call:()=>fileChunks.gunzip("compressed",32,true),method:"file.gunzip",args:{path:"compressed",maxOutputBytes:32,allowPlain:true},value:{path:"temporary",size:32,sha256:"h"}},
      {call:()=>fileChunks.gzip("plain",32),method:"file.gzip",args:{path:"plain",maxOutputBytes:32},value:{path:"temporary",size:32,sha256:"h"}},
      {call:()=>fileChunks.gzipBytes("YQ=="),method:"file.gzipBytes",args:{dataBase64:"YQ=="},value:{dataBase64:"eA=="}},
      {call:()=>fileChunks.openText("temporary"),method:"file.openText",args:{path:"temporary"},value:{id:"text-1"}},
      {call:()=>fileChunks.readTextChunk("text-1",7),method:"file.readTextChunk",args:{id:"text-1",maxChars:7},value:{text:"汉😀",eof:false}},
      {call:()=>fileChunks.closeText("text-1"),method:"file.closeText",args:{id:"text-1"},value:{closed:true}},
    ];
    for(const row of calls)await expect(row.call()).rejects.toMatchObject({code:"unsupported"});
    expect(emitted).toHaveLength(0);host.capabilities=()=>JSON.stringify(["data.files.chunks"]);
    for(const row of calls){const promise=row.call(),request=emitted.at(-1)!;expect(request).toMatchObject({method:row.method,args:row.args});events.push({t:"service.result",id:request.id,ok:true,value:row.value});api.__pumpPodEvents();await expect(promise).resolves.toEqual(row.value);}
  });
  test("staged file APIs require file capability and preserve commit integrity fields",async()=>{
    const hash="a".repeat(64);
    const calls=[
      {call:()=>fileChunks.list("managed-cache/images"),method:"file.list",args:{path:"managed-cache/images"},value:{entries:[{path:"managed-cache/images/a",file:true,directory:false,size:8,modified:1,changeToken:"inode:1:ctime:2"}]}},
      {call:()=>fileChunks.touch("managed-cache/images/a.img"),method:"file.touch",args:{path:"managed-cache/images/a.img"},value:{modified:1}},
      {call:()=>fileChunks.createTemp("article_content"),method:"file.createTemp",args:{directory:"article_content"},value:{path:"article_content/.pod-write-1.part"}},
      {call:()=>fileChunks.append("stage","AQ==",7),method:"file.write",args:{path:"stage",dataBase64:"AQ==",append:true,expectedOffset:7},value:{size:8}},
      {call:()=>fileChunks.hash("stage"),method:"file.hash",args:{path:"stage"},value:{size:8,sha256:hash}},
      {call:()=>fileChunks.commit("stage","target",8,hash),method:"file.commit",args:{path:"stage",target:"target",expectedSize:8,expectedSha256:hash},value:{path:"target",size:8,sha256:hash,reused:false}},
    ];
    for(const row of calls)await expect(row.call()).rejects.toMatchObject({code:"unsupported"});
    expect(emitted).toHaveLength(0);host.capabilities=()=>JSON.stringify(["data.files.chunks"]);
    for(const row of calls){
      const promise=row.call(),request=emitted.at(-1)!;
      expect(request).toMatchObject({method:row.method,args:row.args});
      events.push({t:"service.result",id:request.id,ok:true,value:row.value});api.__pumpPodEvents();
      await expect(promise).resolves.toEqual(row.value);
    }
  });

  test("shared media volume requires audio capability and preserves host physical indices", async () => {
    await expect(mediaVolume.state()).rejects.toMatchObject({code:"unsupported"});
    host.capabilities=()=>JSON.stringify(["media.audio"]);
    for (const value of [-1, 2.5, NaN, Infinity]) expect(()=>mediaVolume.setIndex(value)).toThrow("nonnegative integer");
    const read=mediaVolume.state();
    expect(emitted[0]).toMatchObject({method:"audio.volumeState",args:{}});
    const snapshot={ok:true,min:2,max:15,current:4,fixed:false};
    events.push({t:"service.result",id:emitted[0].id,ok:true,value:snapshot});api.__pumpPodEvents();
    await expect(read).resolves.toEqual(snapshot);
    const write=mediaVolume.setIndex(7);
    expect(emitted[1]).toMatchObject({method:"audio.setVolumeIndex",args:{index:7}});
    events.push({t:"service.result",id:emitted[1].id,ok:true,value:{...snapshot,current:6}});api.__pumpPodEvents();
    await expect(write).resolves.toMatchObject({current:6});
  });
  test("local speech forwards offline voice preferences and preserves the selected voice for utterances", async () => {
    await expect(speech.initializeLocal()).rejects.toMatchObject({code:"unsupported"});
    host.capabilities=()=>JSON.stringify(["media.tts"]);
    expect(()=>speech.initializeLocal([])).toThrow("nonempty language");
    expect(()=>speech.initializeLocal([""])).toThrow("nonempty language");
    const pending=speech.initializeLocal();
    expect(emitted[0]).toMatchObject({method:"tts.init",args:{preferredOfflineLanguages:["zh-CN","zh","en"]}});
    events.push({t:"service.result",id:emitted[0].id,ok:true,value:{ok:true,initialized:true,state:"ready",language:"en-US",voice:"offline-test"}});api.__pumpPodEvents();
    await expect(pending).resolves.toMatchObject({language:"en-US",voice:"offline-test"});
    const utterance=speech.speak("test","",1.15);
    expect(emitted[1]).toMatchObject({method:"tts.speak",args:{text:"test",language:"",rate:1.15}});
    utterance.cancel();await expect(utterance.result).rejects.toMatchObject({code:"cancelled"});
  });
  test("location is unavailable without an explicit host capability and cancellation drops late coordinates", async () => {
    await expect(hostOperation("location.current", {}).result).rejects.toMatchObject({ code: "unsupported" });
    host.capabilities = () => JSON.stringify(["device.location"]);
    const operation = hostOperation("location.current", {});
    expect(emitted[0]).toMatchObject({ method: "location.current", args: {} });
    const id = emitted[0].id;
    operation.cancel();
    await expect(operation.result).rejects.toMatchObject({ code: "cancelled" });
    events.push({ t: "service.result", id, ok: true, value: { latitude: 39, longitude: 116, timestamp: 1 } });
    api.__pumpPodEvents();
    expect(emitted).toHaveLength(2);
  });
  test("time input requires its capability, validates minutes and preserves dismissal", async () => {
    await expect(timeInput.prompt(420).result).rejects.toMatchObject({ code: "unsupported" });
    host.capabilities = () => JSON.stringify(["input.time"]);
    for (const value of [-1, 1440, 2.5, NaN, Infinity]) expect(() => timeInput.prompt(value)).toThrow("integer minutes");
    expect(emitted).toEqual([]);
    const prompt = timeInput.prompt(1439);
    expect(emitted[0]).toMatchObject({ method: "input.time", args: { minutes: 1439 } });
    events.push({ t: "service.result", id: emitted[0].id, ok: true, value: null });
    api.__pumpPodEvents();
    await expect(prompt.result).resolves.toBeNull();
    const next = timeInput.prompt(0);
    next.cancel();
    await expect(next.result).rejects.toMatchObject({ code: "cancelled" });
  });
  test("toasts require their own advertised capability and emit the native operation", async () => {
    await expect(hostOperation("ui.toast", {message:"提示"}).result).rejects.toMatchObject({code:"unsupported"});
    host.capabilities=()=>JSON.stringify(["system.toast"]);
    const operation=hostOperation("ui.toast", {message:"提示"});
    expect(emitted[0]).toMatchObject({method:"ui.toast",args:{message:"提示"}});
    operation.cancel();await expect(operation.result).rejects.toMatchObject({code:"cancelled"});
  });
  test("opens only HTTP(S) URLs through the declared browser capability", async () => {
    host.capabilities = () => JSON.stringify(["system.browser"]);
    const operation = browser.open("https://example.com/about");
    expect(emitted[0]).toMatchObject({ method: "browser.open", args: { url: "https://example.com/about" } });
    operation.cancel();
    await expect(operation.result).rejects.toMatchObject({ code: "cancelled" });
  });

  test("rejects non-web browser URLs before emitting a request", async () => {
    expect(() => browser.open("file:///tmp/about.html")).toThrow("HTTP or HTTPS");
    expect(() => browser.open("javascript:alert(1)")).toThrow("HTTP or HTTPS");
    expect(emitted).toEqual([]);
  });

  test("rejects a service when its capability is absent", async () => {
    host.capabilities = () => JSON.stringify([]);
    const operation = hostOperation("sql.query", { sql: "select 1" });
    await expect(operation.result).rejects.toMatchObject({ code: "unsupported" });
    expect(emitted).toEqual([]);
  });

  test("matches responses by request id rather than completion order", async () => {
    const first = sqlite.query("select ?", [1]);
    const second = sqlite.query("select ?", [2]);
    const ids = emitted.map(event => event.id as number);
    expect(ids).toHaveLength(2);
    events.push({ t: "service.result", id: ids[1], ok: true, value: [{ value: 2 }] });
    events.push({ t: "service.result", id: ids[0], ok: true, value: [{ value: 1 }] });
    api.__pumpPodEvents();
    await expect(first).resolves.toEqual([{ value: 1 }]);
    await expect(second).resolves.toEqual([{ value: 2 }]);
  });

  test("cancellation rejects immediately and ignores a late host reply", async () => {
    const operation = hostOperation<string>("input.text", { title: "name" });
    const request = emitted[0];
    operation.cancel();
    expect(emitted[1]).toMatchObject({ t: "service.cancel", id: request.id });
    await expect(operation.result).rejects.toMatchObject({ code: "cancelled" });
    events.push({ t: "service.result", id: request.id, ok: true, value: "late" });
    api.__pumpPodEvents();
    expect(emitted).toHaveLength(2);
  });

  test("surfaces host error code and message", async () => {
    const result = sqlite.execute("bad");
    const id = emitted[0].id;
    events.push({ t: "service.result", id, ok: false, code: "invalid_argument", message: "bad SQL" });
    api.__pumpPodEvents();
    await expect(result).rejects.toEqual(new HostServiceError("invalid_argument", "bad SQL"));
  });
});

test("speech initialization, rate and completion state use the host TTS protocol", async()=>{
  host.capabilities=()=>JSON.stringify(["media.tts"]);
  const init=speech.initialize();
  expect(emitted.at(-1)).toMatchObject({method:"tts.init",args:{language:"zh-CN"}});
  events.push({t:"service.result",id:emitted.at(-1)!.id,ok:true,value:{initialized:true,state:"ready"}});api.__pumpPodEvents();await init;
  const utterance=speech.speak("文章","zh-CN",1.4);
  expect(emitted.at(-1)).toMatchObject({method:"tts.speak",args:{text:"文章",language:"zh-CN",rate:1.4}});
  events.push({t:"service.result",id:emitted.at(-1)!.id,ok:true,value:{state:"speaking"}});api.__pumpPodEvents();await utterance.result;
  const state=speech.state();
  expect(emitted.at(-1)).toMatchObject({method:"tts.state"});
  events.push({t:"service.result",id:emitted.at(-1)!.id,ok:true,value:{state:"ready"}});api.__pumpPodEvents();expect((await state).state).toBe("ready");
});

test("speech progress validates range events, keeps utterance identity and disposes listeners",async()=>{
  host.capabilities=()=>JSON.stringify(["media.tts"]);
  const seen:unknown[]=[];const remove=speech.onProgress(event=>seen.push(event));
  events.push({t:"speech.progress",type:"range",utteranceId:"one",start:2,end:4,elapsedMs:120});
  events.push({t:"speech.progress",type:"range",utteranceId:"one",start:1.5,end:4});
  events.push({t:"speech.progress",type:"start",utteranceId:"two",elapsedMs:0});api.__pumpPodEvents();
  expect(seen).toEqual([{type:"range",utteranceId:"one",start:2,end:4,elapsedMs:120},{type:"start",utteranceId:"two",start:undefined,end:undefined,elapsedMs:0}]);
  remove();events.push({t:"speech.progress",type:"done",utteranceId:"two",elapsedMs:300});api.__pumpPodEvents();expect(seen).toHaveLength(2);
  const pending=speech.speak("range test","",1,"client-utterance");expect(emitted[0]).toMatchObject({method:"tts.speak",args:{utteranceId:"client-utterance"}});pending.cancel();await expect(pending.result).rejects.toMatchObject({code:"cancelled"});
});

test("paragraph layout capability gates pixel inputs and delivers UTF-16 offsets",async()=>{
  await expect(textLayout.layout({text:"甲😀乙",widthPx:410,fontSizePx:28}).result).rejects.toMatchObject({code:"unsupported"});
  host.capabilities=()=>JSON.stringify(["text.layout"]);
  const operation=textLayout.layout({text:"甲😀乙",widthPx:410,fontSizePx:28,lineBreak:"paragraph"});
  const request=emitted.at(-1)!;expect(request.method).toBe("text.layout");expect(request.args).toEqual({text:"甲😀乙",widthPx:410,fontSizePx:28,lineBreak:"paragraph"});
  const value={lines:[{start:0,end:4,visibleEnd:4,topPx:0,bottomPx:32,baselinePx:25}]};
  events.push({t:"service.result",id:request.id,ok:true,value});api.__pumpPodEvents();expect(await operation.result).toEqual(value);
});
test("paragraph layout batch sends bounded ordered options through text layout capability",async()=>{
  host.capabilities=()=>JSON.stringify(["text.layout"]);
  const paragraphs=[{text:"甲",widthPx:200,fontSizePx:28},{text:"乙",widthPx:200,fontSizePx:28}];
  const operation=textLayout.layoutBatch(paragraphs);
  const request=emitted.at(-1)!;expect(request).toMatchObject({method:"text.layoutBatch",args:{paragraphs}});
  const value={results:[{lines:[{start:0,end:1,visibleEnd:1,topPx:0,bottomPx:32,baselinePx:25}]},{error:"invalid width"}]};
  events.push({t:"service.result",id:request.id,ok:true,value});api.__pumpPodEvents();expect(await operation.result).toEqual(value);
});

test("text environment has a separate capability and preserves ordered SP requests",async()=>{
  host.capabilities=()=>JSON.stringify(["text.layout","text.raster"]);
  await expect(textLayout.environment({spValues:[21,26]})).rejects.toMatchObject({code:"unsupported"});
  expect(emitted).toHaveLength(0);
  host.capabilities=()=>JSON.stringify(["text.environment"]);
  const spValues=[21,0,12.5,21,26] as const;
  const result=textLayout.environment({spValues});
  const request=emitted.at(-1)!;
  expect(request).toMatchObject({method:"text.environment",args:{spValues:[21,0,12.5,21,26]}});
  const value={density:2,fontScale:1.3,spValuesPx:[51.6,0,32.5,51.6,60.4]};
  events.push({t:"service.result",id:request.id,ok:true,value});api.__pumpPodEvents();
  await expect(result).resolves.toEqual(value);
  await expect(textLayout.layout({text:"x",widthPx:100,fontSizePx:21}).result).rejects.toMatchObject({code:"unsupported"});
});

test("text environment queries are fresh and default to no SP values",async()=>{
  host.capabilities=()=>JSON.stringify(["text.environment"]);
  for(const value of [{density:2,fontScale:1,spValuesPx:[]},{density:2.5,fontScale:1.5,spValuesPx:[]}]){
    const result=textLayout.environment();
    const request=emitted.at(-1)!;expect(request).toMatchObject({method:"text.environment",args:{}});
    events.push({t:"service.result",id:request.id,ok:true,value});api.__pumpPodEvents();
    await expect(result).resolves.toEqual(value);
  }
  expect(emitted).toHaveLength(2);expect(emitted[0].id).not.toBe(emitted[1].id);
  const result=textLayout.environment({spValues:[8193]});
  events.push({t:"service.result",id:emitted.at(-1)!.id,ok:false,code:"invalid_argument",message:"SP value outside range"});api.__pumpPodEvents();
  await expect(result).rejects.toMatchObject({code:"invalid_argument"});
});

test("system text shares one texture and releases it after the final reader",async()=>{
  host.capabilities=()=>JSON.stringify(["text.raster","data.files.chunks"]);
  const {installHost,getHost}=await import("@pocketjs/framework/host");
  const {bindSystemTextLine,systemTextResourceCounts}=await import("../src/text.tsx");
  let previous:ReturnType<typeof getHost>|undefined;try{previous=getHost();}catch{}
  const uploads:unknown[]=[],freed:number[]=[],images:unknown[]=[];
  installHost({kind:"injected",target:"test",strict:true,ops:{uploadTexture:(...args:unknown[])=>{uploads.push(args);return 9;},freeTexture:(value:number)=>freed.push(value),setImage:(...args:unknown[])=>images.push(args)} as any});
  const settle=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};
  const options={text:"a",widthPx:1,heightPx:1,baselinePx:1,fontSizePx:1};let callbacks=0;
  const first=bindSystemTextLine(options,{id:901} as any,()=>callbacks++),second=bindSystemTextLine(options,{id:902} as any,()=>callbacks++);
  try{
    expect(emitted.filter(e=>e.method==="text.raster")).toHaveLength(1);
    const raster=emitted.find(e=>e.method==="text.raster")!;
    events.push({t:"service.result",id:raster.id,ok:true,value:{path:"text/test.rgba",width:1,height:1,contentWidth:1,contentHeight:1,carets:[0,1]}});api.__pumpPodEvents();await settle();
    const read=emitted.find(e=>e.method==="file.read")!;expect(read).toBeDefined();
    events.push({t:"service.result",id:read.id,ok:true,value:{dataBase64:"AQIDBA==",size:4,offset:0,eof:true}});api.__pumpPodEvents();await settle();
    expect(uploads).toHaveLength(1);expect(callbacks).toBe(2);expect(images).toContainEqual([901,9]);expect(images).toContainEqual([902,9]);
    first();expect(freed).toEqual([]);second();expect(freed).toEqual([9]);expect(systemTextResourceCounts().cached).toBe(0);
    for(const request of emitted.filter(e=>e.method==="file.delete"))events.push({t:"service.result",id:request.id,ok:true,value:{deleted:true}});api.__pumpPodEvents();await settle();
  }finally{if(previous)installHost(previous);}
});


test("display power and silence leases are gated and deliver independent owner state",async()=>{
 await expect(displayPower.state()).rejects.toMatchObject({code:"unsupported"});await expect(silencePlayback.state()).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
 host.capabilities=()=>JSON.stringify(["display.power","media.audio"]);
 const display={interactive:true,resumed:true,applied:true,windowKeepScreenOn:true,owners:["transfer"]};let changes=0;const stop=displayPower.onChange(value=>{expect(value).toEqual(display);changes++;});
 for(const row of [
  {call:()=>displayPower.keepAwake("transfer",true),method:"display.keepAwake",args:{owner:"transfer",active:true},value:display},
  {call:()=>silencePlayback.setEnabled(false),method:"audio.silenceEnabled",args:{enabled:false},value:{enabled:false,playing:false,owners:[]}},
  {call:()=>silencePlayback.lease("ip",true),method:"audio.silenceLease",args:{owner:"ip",active:true},value:{enabled:false,playing:false,owners:["ip"]}},
 ]){const promise=row.call(),request=emitted.at(-1)!;expect(request).toMatchObject({method:row.method,args:row.args});events.push({t:"service.result",id:request.id,ok:true,value:row.value});api.__pumpPodEvents();await expect(promise).resolves.toEqual(row.value);}
 events.push({t:"display.state",value:display});api.__pumpPodEvents();expect(changes).toBe(1);stop();events.push({t:"display.state",value:display});api.__pumpPodEvents();expect(changes).toBe(1);
});

test("local font options are identical across text measurement, layout and raster transport",async()=>{
 const {textRaster}=await import("../src/services.ts");host.capabilities=()=>JSON.stringify(["text.layout","text.raster"]);
 const font={fontPath:"reader_assets/fonts/custom.ttc",fontFaceIndex:2,variationSettings:"'wght' 650",fontWeight:650,italic:true,letterSpacingEm:.04,fontSizePx:28,text:"Font"};
 const calls=[{op:()=>textLayout.measure(font),method:"text.measure",value:{widthPx:51}}, {op:()=>textLayout.layout({...font,widthPx:200}),method:"text.layout",value:{lines:[]}}, {op:()=>textRaster.line({...font,widthPx:200,heightPx:40,baselinePx:30}),method:"text.raster",value:{path:"text/result.rgba",width:256,height:64,contentWidth:200,contentHeight:40,carets:[]}}];
 for(const item of calls){const operation=item.op(),request=emitted.at(-1)!;expect(request.method).toBe(item.method);expect(request.args).toMatchObject(font);events.push({t:"service.result",id:request.id,ok:true,value:item.value});api.__pumpPodEvents();await expect(operation.result).resolves.toEqual(item.value);}
});

test("packaged font assets preserve collection, variation and styling across text host requests",async()=>{
 const {textRaster}=await import("../src/services.ts");host.capabilities=()=>JSON.stringify(["text.layout","text.raster"]);
 const font={fontAsset:"fonts/body.ttc",fontFaceIndex:2,variationSettings:"'wght' 650",fontWeight:650,italic:true,letterSpacingEm:.04,fontSizePx:28,text:"Font 中文"};
 const before=JSON.stringify(font);
 const calls=[{op:()=>textLayout.measure(font),method:"text.measure",value:{widthPx:51}}, {op:()=>textLayout.layout({...font,widthPx:200}),method:"text.layout",value:{lines:[]}}, {op:()=>textRaster.line({...font,widthPx:200,heightPx:40,baselinePx:30}),method:"text.raster",value:{path:"text/result.rgba",width:256,height:64,contentWidth:200,contentHeight:40,carets:[]}}];
 for(const item of calls){const operation=item.op(),request=emitted.at(-1)!;expect(request.method).toBe(item.method);expect(request.args).toMatchObject(font);expect(request.args).not.toHaveProperty("fontPath");events.push({t:"service.result",id:request.id,ok:true,value:item.value});api.__pumpPodEvents();await expect(operation.result).resolves.toEqual(item.value);}
 expect(JSON.stringify(font)).toBe(before);
});

test("image and video scene surfaces retain separate capabilities and known close identities",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");host.capabilities=()=>JSON.stringify(["data.images"]);
 await expect(mediaSurfaces.open("video",{id:"clip",path:"clip.mp4",widthPx:466,heightPx:466})).rejects.toMatchObject({code:"unsupported"});expect(emitted).toHaveLength(0);
 const open=mediaSurfaces.open("image",{id:"picture",path:"picture.png",widthPx:466,heightPx:466,brightness:.5});const request=emitted.at(-1)!;expect(request).toMatchObject({method:"image.surface.open",args:{id:"picture",brightness:.5}});
 const close=mediaSurfaces.close("image","picture"),closed=emitted.at(-1)!;expect(closed).toMatchObject({method:"image.surface.close",args:{id:"picture"}});
 events.push({t:"service.result",id:request.id,ok:true,value:{texture:7}},{t:"service.result",id:closed.id,ok:true,value:{closed:true}});api.__pumpPodEvents();await expect(open).resolves.toMatchObject({texture:7});await expect(close).resolves.toEqual({closed:true});
});

test("scene surface open and update preserve normalized brightness bias and blur edge mode without mutating caller styles",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");host.capabilities=()=>JSON.stringify(["data.images","media.video"]);
 const effects=[
  {brightnessBias:-1,blurEdgeMode:"decal"},
  {brightnessBias:-58/255,blurEdgeMode:"clamp"},
  {brightnessBias:0,blurEdgeMode:"decal"},
  {brightnessBias:58/255,blurEdgeMode:"clamp"},
  {brightnessBias:1,blurEdgeMode:"clamp"},
 ] as const;
 for(const kind of ["image","video"] as const)for(const effect of effects){
  const expectedStyle={brightness:.8,blurPx:36,...effect},style=Object.freeze({...expectedStyle});
  const expectedOpen={id:"effect-"+kind,path:kind==="image"?"picture.png":"clip.mp4",widthPx:240,heightPx:240,...expectedStyle};
  const options=Object.freeze({...expectedOpen}),value={id:options.id,texture:7,width:256,height:256,contentWidth:240,contentHeight:240,state:kind==="image"?"ready":"paused",error:null,frames:0,playerGeneration:0,positionMs:0,speed:1,loop:true,muted:true};
  const opened=mediaSurfaces.open(kind,options),openRequest=emitted.at(-1)!;
  expect(openRequest.method).toBe(kind+".surface.open");expect(openRequest.args).toEqual(expectedOpen);
  events.push({t:"service.result",id:openRequest.id,ok:true,value});api.__pumpPodEvents();await expect(opened).resolves.toEqual(value);
  expect(options).toEqual(expectedOpen);expect(style).toEqual(expectedStyle);
  const updated=mediaSurfaces.update(kind,options.id,style),updateRequest=emitted.at(-1)!;
  expect(updateRequest.method).toBe(kind+".surface.update");expect(updateRequest.args).toEqual({id:options.id,...expectedStyle});
  events.push({t:"service.result",id:updateRequest.id,ok:true,value});api.__pumpPodEvents();await expect(updated).resolves.toEqual(value);
  expect(style).toEqual(expectedStyle);expect(options).toEqual(expectedOpen);
 }
});

test("scene surface open and update leave omitted brightness bias and blur edge mode absent from existing payloads",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");host.capabilities=()=>JSON.stringify(["data.images","media.video"]);
 for(const kind of ["image","video"] as const){
  const expectedStyle={fit:"fit" as const,zoom:1.5,focusX:0,focusY:.5,blurPx:0,brightness:0,saturation:.6,overlayOpacity:0,active:false};
  const style=Object.freeze({...expectedStyle});
  const expectedOpen={id:"legacy-"+kind,path:kind==="image"?"picture.png":"clip.mp4",widthPx:466,heightPx:400,...expectedStyle};
  const options=Object.freeze({...expectedOpen}),value={id:options.id,texture:8,width:512,height:512,contentWidth:466,contentHeight:400,state:kind==="image"?"ready":"paused",error:null,frames:0,playerGeneration:0,positionMs:0,speed:1,loop:true,muted:true};
  const opened=mediaSurfaces.open(kind,options),openRequest=emitted.at(-1)!;
  expect(openRequest.method).toBe(kind+".surface.open");expect(openRequest.args).toEqual(expectedOpen);
  expect(openRequest.args).not.toHaveProperty("brightnessBias");expect(openRequest.args).not.toHaveProperty("blurEdgeMode");
  events.push({t:"service.result",id:openRequest.id,ok:true,value});api.__pumpPodEvents();await expect(opened).resolves.toEqual(value);
  const updated=mediaSurfaces.update(kind,options.id,style),updateRequest=emitted.at(-1)!;
  expect(updateRequest.method).toBe(kind+".surface.update");expect(updateRequest.args).toEqual({id:options.id,...expectedStyle});
  expect(updateRequest.args).not.toHaveProperty("brightnessBias");expect(updateRequest.args).not.toHaveProperty("blurEdgeMode");
  events.push({t:"service.result",id:updateRequest.id,ok:true,value});api.__pumpPodEvents();await expect(updated).resolves.toEqual(value);
  expect(style).toEqual(expectedStyle);expect(options).toEqual(expectedOpen);
 }
});

test("scene surface sampling is explicit on open and retained in image and video state readback",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");
 host.capabilities=()=>JSON.stringify(["data.images","media.video"]);
 for(const kind of ["image","video"] as const)for(const textureSampling of ["nearest","linear"] as const){
  const options=Object.freeze({id:`${kind}-${textureSampling}`,path:kind==="image"?"picture.png":"clip.mp4",widthPx:32,heightPx:16,textureSampling});
  const opened=mediaSurfaces.open(kind,options),openRequest=emitted.at(-1)!;
  expect(openRequest.method).toBe(`${kind}.surface.open`);
  expect(openRequest.args).toEqual(options);
  const value={id:options.id,texture:17,width:32,height:16,contentWidth:32,contentHeight:16,state:"ready",error:null,frames:0,playerGeneration:0,positionMs:0,speed:1,loop:true,muted:true,textureSampling};
  events.push({t:"service.result",id:openRequest.id,ok:true,value});api.__pumpPodEvents();
  await expect(opened).resolves.toEqual(value);

  const style=Object.freeze({brightness:.7,blurPx:2,active:true});
  const updated=mediaSurfaces.update(kind,options.id,style),updateRequest=emitted.at(-1)!;
  expect(updateRequest.method).toBe(`${kind}.surface.update`);
  expect(updateRequest.args).toEqual({id:options.id,...style});
  expect(updateRequest.args).not.toHaveProperty("textureSampling");
  events.push({t:"service.result",id:updateRequest.id,ok:true,value});api.__pumpPodEvents();
  await expect(updated).resolves.toEqual(value);

  const state=mediaSurfaces.state(kind,options.id),stateRequest=emitted.at(-1)!;
  expect(stateRequest).toMatchObject({method:`${kind}.surface.state`,args:{id:options.id}});
  events.push({t:"service.result",id:stateRequest.id,ok:true,value});api.__pumpPodEvents();
  await expect(state).resolves.toEqual(value);
  expect(options.textureSampling).toBe(textureSampling);
 }
});

test("omitted scene surface sampling preserves existing payloads and older host state",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");
 host.capabilities=()=>JSON.stringify(["data.images","media.video"]);
 for(const kind of ["image","video"] as const){
  const options=Object.freeze({id:`${kind}-legacy`,path:kind==="image"?"picture.png":"clip.mp4",widthPx:32,heightPx:16});
  const opened=mediaSurfaces.open(kind,options),openRequest=emitted.at(-1)!;
  expect(openRequest.method).toBe(`${kind}.surface.open`);
  expect(openRequest.args).toEqual(options);
  expect(openRequest.args).not.toHaveProperty("textureSampling");
  const value={id:options.id,texture:19,width:32,height:16,contentWidth:32,contentHeight:16,state:"ready",error:null,frames:0,playerGeneration:0,positionMs:0,speed:1,loop:true,muted:true};
  events.push({t:"service.result",id:openRequest.id,ok:true,value});api.__pumpPodEvents();
  const result=await opened;expect(result).toEqual(value);expect(result).not.toHaveProperty("textureSampling");

  const updated=mediaSurfaces.update(kind,options.id,{}),updateRequest=emitted.at(-1)!;
  expect(updateRequest).toMatchObject({method:`${kind}.surface.update`,args:{id:options.id}});
  expect(updateRequest.args).toEqual({id:options.id});
  events.push({t:"service.result",id:updateRequest.id,ok:true,value});api.__pumpPodEvents();
  await expect(updated).resolves.toEqual(value);

  const state=mediaSurfaces.state(kind,options.id),stateRequest=emitted.at(-1)!;
  expect(stateRequest.args).toEqual({id:options.id});
  events.push({t:"service.result",id:stateRequest.id,ok:true,value});api.__pumpPodEvents();
  const snapshot=await state;expect(snapshot).toEqual(value);expect(snapshot).not.toHaveProperty("textureSampling");
 }
});

test("scene surface presentation frames travel with complete styles and preserve normalized host readback",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");
 host.capabilities=()=>JSON.stringify(["data.images","media.video"]);
 for(const kind of ["image","video"] as const){
  const frame=Object.freeze({leftPx:-12.25,topPx:450.5,widthPx:240,heightPx:222,scale:1.16,opacity:.12});
  const options=Object.freeze({id:`frame-${kind}`,path:kind==="image"?"picture.png":"clip.mp4",widthPx:466,heightPx:466,brightness:.8,blurPx:18,presentationFrame:frame});
  const before=JSON.stringify(options);
  const opened=mediaSurfaces.open(kind,options),openRequest=emitted.at(-1)!;
  expect(openRequest.method).toBe(`${kind}.surface.open`);
  expect(openRequest.args).toEqual(options);
  const normalizedFrame={leftPx:-12.25,topPx:450.5,widthPx:240,heightPx:222,scale:1.159999966621399,opacity:.11999999731779099};
  const base={id:options.id,texture:23,width:512,height:512,contentWidth:466,contentHeight:466,state:"ready",error:null,frames:0,playerGeneration:0,positionMs:0,speed:1,loop:true,muted:true};
  const openedValue={...base,presentationFrame:normalizedFrame};
  events.push({t:"service.result",id:openRequest.id,ok:true,value:openedValue});api.__pumpPodEvents();
  await expect(opened).resolves.toEqual(openedValue);
  expect(JSON.stringify(options)).toBe(before);

  const nextFrame=Object.freeze({leftPx:10.5,topPx:-20.25,widthPx:300,heightPx:180,scale:.75,opacity:.6});
  const style=Object.freeze({brightness:.9,blurPx:9,presentationFrame:nextFrame});
  const updated=mediaSurfaces.update(kind,options.id,style),updateRequest=emitted.at(-1)!;
  expect(updateRequest.method).toBe(`${kind}.surface.update`);
  expect(updateRequest.args).toEqual({id:options.id,...style});
  const updatedValue={...base,presentationFrame:{leftPx:10.5,topPx:-20.25,widthPx:300,heightPx:180,scale:.75,opacity:.6000000238418579}};
  events.push({t:"service.result",id:updateRequest.id,ok:true,value:updatedValue});api.__pumpPodEvents();
  await expect(updated).resolves.toEqual(updatedValue);
  expect(style.presentationFrame).toEqual(nextFrame);

  const read=mediaSurfaces.state(kind,options.id),stateRequest=emitted.at(-1)!;
  expect(stateRequest).toMatchObject({method:`${kind}.surface.state`,args:{id:options.id}});
  events.push({t:"service.result",id:stateRequest.id,ok:true,value:updatedValue});api.__pumpPodEvents();
  await expect(read).resolves.toEqual(updatedValue);
 }
});

test("omitting presentationFrame resets the complete style and accepts null or absent frame readback",async()=>{
 const {mediaSurfaces}=await import("../src/services.ts");
 host.capabilities=()=>JSON.stringify(["data.images","media.video"]);
 for(const kind of ["image","video"] as const)for(const readback of ["null","omitted"] as const){
  const frame={leftPx:0,topPx:0,widthPx:240,heightPx:240,scale:1,opacity:1};
  const options={id:`reset-${kind}-${readback}`,path:kind==="image"?"picture.png":"clip.mp4",widthPx:466,heightPx:466,presentationFrame:frame};
  const opened=mediaSurfaces.open(kind,options),openRequest=emitted.at(-1)!;
  const base={id:options.id,texture:29,width:512,height:512,contentWidth:466,contentHeight:466,state:"ready",error:null,frames:0,playerGeneration:0,positionMs:0,speed:1,loop:true,muted:true};
  events.push({t:"service.result",id:openRequest.id,ok:true,value:{...base,presentationFrame:frame}});api.__pumpPodEvents();await opened;

  const style=Object.freeze({fit:"fit" as const,brightness:.75});
  const reset=mediaSurfaces.update(kind,options.id,style),resetRequest=emitted.at(-1)!;
  expect(resetRequest.method).toBe(`${kind}.surface.update`);
  expect(resetRequest.args).toEqual({id:options.id,...style});
  expect(resetRequest.args).not.toHaveProperty("presentationFrame");
  const value={...base,...(readback==="null"?{presentationFrame:null}:{})};
  events.push({t:"service.result",id:resetRequest.id,ok:true,value});api.__pumpPodEvents();
  await expect(reset).resolves.toEqual(value);

  const read=mediaSurfaces.state(kind,options.id),stateRequest=emitted.at(-1)!;
  expect(stateRequest.args).toEqual({id:options.id});
  events.push({t:"service.result",id:stateRequest.id,ok:true,value});api.__pumpPodEvents();
  const snapshot=await read;expect(snapshot).toEqual(value);
  if(readback==="null")expect(snapshot).toHaveProperty("presentationFrame",null);
  else expect(snapshot).not.toHaveProperty("presentationFrame");
 }
});


test("retained prompt identity is separately gated; pending, rebinding and dismissal use scoped requests",async()=>{
 await expect(textInput.prompt('search','seed',{restorationKey:'r1:search'}).result).rejects.toMatchObject({code:'unsupported'});
 await expect(textInput.pending('r1:search').result).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
 host.capabilities=()=>JSON.stringify(['input.text','input.text.state']);
 for(const key of ['', 'bad/key', 'x'.repeat(161)])expect(()=>textInput.pending(key)).toThrow();
 const pending=textInput.pending('r1:search');expect(emitted.at(-1)).toMatchObject({method:'input.text.pending',args:{restorationKey:'r1:search'}});
 events.push({t:'service.result',id:emitted.at(-1)!.id,ok:true,value:true});api.__pumpPodEvents();await expect(pending.result).resolves.toBe(true);
 const prompt=textInput.prompt('search','seed',{restorationKey:'r1:search'});expect(emitted.at(-1)).toMatchObject({method:'input.text',args:{title:'search',value:'seed',restorationKey:'r1:search'}});
 const id=emitted.at(-1)!.id;
 const dismiss=textInput.dismiss('r1:search');expect(emitted.at(-1)).toMatchObject({method:'input.text.dismiss',args:{restorationKey:'r1:search'}});
 events.push({t:'service.result',id,ok:true,value:null},{t:'service.result',id:emitted.at(-1)!.id,ok:true,value:null});api.__pumpPodEvents();await expect(prompt.result).resolves.toBeNull();await dismiss.result;
});
test("browser state requires the new capability and preserves explicit opt-in on the wire",async()=>{
 host.capabilities=()=>JSON.stringify(['system.browser.inApp']);
 await expect(browser.open('https://example.org',{mode:'inApp',restoreActivity:true}).result).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
 host.capabilities=()=>JSON.stringify(['system.browser.inApp','system.browser.inApp.state']);
 expect(()=>browser.open('https://example.org',{mode:'inApp',restoreActivity:'yes'} as never)).toThrow();
 const opened=browser.open('https://example.org',{mode:'inApp',restoreActivity:true});expect(emitted.at(-1)).toMatchObject({method:'browser.openInApp',args:{url:'https://example.org',mode:'inApp',restoreActivity:true}});
 events.push({t:'service.result',id:emitted.at(-1)!.id,ok:true,value:null});api.__pumpPodEvents();await opened.result;
});

test("delay clamps to the host timer range so yields never send 0ms", async () => {
  const { hostDelayMilliseconds } = await import("../src/services.ts");
  expect([0, 0.2, -5, Number.NaN, 7.5, 999999].map(hostDelayMilliseconds)).toEqual([1, 1, 1, 1, 8, 120000]);
});

for(const terminal of ['success','error','cancel'] as const)test(`browser QR wait wire and callback terminate on ${terminal}, ignoring late statuses`,async()=>{
 host.capabilities=()=>JSON.stringify(['system.browser.auth','system.browser.auth.status']);
 const waits:unknown[]=[],statuses:unknown[]=[];
 const operation=browserAuth.authorize({url:'https://example.com',cookieOrigin:'https://example.com',requiredCookieNames:['sid'],qrExtractionScript:'null',qrWait:{message:'Wait for QR'},onQrWait:s=>waits.push(s),onStatus:s=>statuses.push(s)});
 const request=emitted.at(-1)!;
 expect(request).toMatchObject({method:'browser.authorizeWithStatus',args:{qrWait:{message:'Wait for QR'}}});
 expect((request.args as object)).not.toHaveProperty('onQrWait');
 const status=(value:unknown)=>{events.push({t:'browser.auth.status',id:request.id,value});api.__pumpPodEvents();};
 status({type:'pageState',loading:false});expect(waits.at(-1)).toEqual({visible:true,message:'Wait for QR'});
 status({type:'verifying',active:true});expect(waits.at(-1)).toEqual({visible:false,message:'Wait for QR'});
 status({type:'verifying',active:false});expect(waits.at(-1)).toEqual({visible:true,message:'Wait for QR'});
 if(terminal==='cancel')operation.cancel();
 else {events.push(terminal==='success'?{t:'service.result',id:request.id,ok:true,value:{cookie:'sid=x',origin:'https://example.com'}}:{t:'service.result',id:request.id,ok:false,error:{code:'webview_load_-7',message:'failure'}});api.__pumpPodEvents();}
 if(terminal==='success')await expect(operation.result).resolves.toMatchObject({origin:'https://example.com'});else await expect(operation.result).rejects.toThrow();
 expect(waits.at(-1)).toEqual({visible:false,message:'Wait for QR'});const count=statuses.length;
 status({type:'qrCleared'});expect(statuses).toHaveLength(count);expect(waits.at(-1)).toEqual({visible:false,message:'Wait for QR'});
});

const splash=await import('../src/splash.ts');
test('splash API uses normal lifecycle host services, validates owners, and propagates unsupported',async()=>{
 await expect(splash.hold()).rejects.toMatchObject({code:'unsupported'});
 host.capabilities=()=>JSON.stringify(['host.lifecycle']);
 for(const [run,method] of [[splash.hold,'splash.hold'],[splash.release,'splash.release']] as const){
  const result=run(),request=emitted.at(-1)!;expect(request).toMatchObject({method,args:{owner:'guest'}});
  events.push({t:'service.result',id:request.id,ok:true,value:null});api.__pumpPodEvents();await result;
 }
 expect(()=>splash.hold('<bad>')).toThrow('Invalid splash owner');
});

test('single SQL change receipt is committed, labelled and rejects malformed counters',async()=>{
 for(const [reply,expected]of [[[0],0],[[2],2]] as const){const pending=sqlite.executeChanges('UPDATE rows SET value=?',['v'],'fixture.update'),request=emitted.at(-1)!;expect(request).toMatchObject({method:'sql.transaction',args:{reportChanges:true,traceLabel:'fixture.update',statements:[{sql:'UPDATE rows SET value=?',params:['v']}]}});events.push({t:'service.result',id:request.id,ok:true,value:reply});api.__pumpPodEvents();expect(await pending).toBe(expected);}
 for(const value of [[],[-1],[1,2],[1.5]]){const pending=sqlite.executeChanges('UPDATE rows SET value=1'),request=emitted.at(-1)!;events.push({t:'service.result',id:request.id,ok:true,value});api.__pumpPodEvents();await expect(pending).rejects.toThrow('Invalid SQL change receipt');}
});

test('pure codec scheduler yields without a runtime.timer host capability',async()=>{
 const before=emitted.length;await (globalThis as any).__podYieldCodecTurn();expect(emitted).toHaveLength(before);
});

test("video repeat changes are capability gated and target the retained player/session",async()=>{
 const {video,videoSessions}=await import('../src/services.ts');
 await expect(video.setLoop(true)).rejects.toMatchObject({code:'unsupported'});
 await expect(videoSessions.setLoop('standby',true)).rejects.toMatchObject({code:'unsupported'});
 expect(emitted).toHaveLength(0);host.capabilities=()=>JSON.stringify(['media.video','media.video.sessions']);
 for(const loop of [true,false])for(const sessionId of [undefined,'active','standby']){
  const task=sessionId?videoSessions.setLoop(sessionId,loop):video.setLoop(loop),request=emitted.at(-1)!;
  expect(request).toMatchObject({method:sessionId?'video.session.setLoop':'video.setLoop',args:sessionId?{sessionId,loop}:{loop}});
  const value={state:'playing',positionMs:1234,completedLoops:2,loop};events.push({t:'service.result',id:request.id,ok:true,value});api.__pumpPodEvents();
  expect(await task).toMatchObject(value);
 }
});

test('live browser theme updates are separately capability gated, strictly validated and return whether a session exists',async()=>{
 await expect(browser.updateTheme({dark:true}).result).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
 host.capabilities=()=>JSON.stringify(['system.browser.inApp.theme']);
 for(const theme of [{dark:'yes'},{dark:true,textZoom:0},{dark:true,backgroundColor:'red'},{dark:false,unknown:1}])expect(()=>browser.updateTheme(theme as never)).toThrow();
 for(const updated of [true,false]){const operation=browser.updateTheme({dark:false,backgroundColor:'#ffffffff'}),request=emitted.at(-1)!;expect(request).toMatchObject({method:'browser.updateTheme',args:{theme:{dark:false,backgroundColor:'#ffffffff'}}});events.push({t:'service.result',id:request.id,ok:true,value:{updated}});api.__pumpPodEvents();expect(await operation.result).toEqual({updated});}
});

test('actual video frame capture/restore/clear are capability gated and retain exact source generation across both backends',async()=>{
 const {video,videoSessions}=await import('../src/services.ts');const options={key:'opaque-item',source:{url:'https://fixture/video',headers:{Cookie:'fixture'}},sourceGeneration:8};
 await expect(video.captureFrame(options)).rejects.toMatchObject({code:'unsupported'});await expect(video.restoreFrame(options.key,options.source)).rejects.toMatchObject({code:'unsupported'});await expect(videoSessions.captureFrame('slot',options)).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);
 host.capabilities=()=>JSON.stringify(['media.video','media.video.sessions']);
 const tasks=[()=>video.captureFrame(options),()=>videoSessions.captureFrame('slot',options),()=>video.restoreFrame(options.key,options.source),()=>video.clearFrames()];
 const expected=[['video.frame.capture',options],['video.session.captureFrame',{sessionId:'slot',...options}],['video.frame.restore',{key:options.key,source:options.source}],['video.frame.clear',{}]];
 for(let i=0;i<tasks.length;i++){const task=tasks[i]!(),request=emitted.at(-1)!;expect(request).toMatchObject({method:expected[i]![0],args:expected[i]![1]});events.push({t:'service.result',id:request.id,ok:true,value:{available:false}});api.__pumpPodEvents();expect(await task).toMatchObject({available:false});}
});

test('manual cloud enqueue and local transfer leases require lifecycle grant and carry no credentials',async()=>{
 await expect(hostOperation('cloud.enqueue',{token:'opaque'}).result).rejects.toMatchObject({code:'unsupported'});expect(emitted).toHaveLength(0);host.capabilities=()=>JSON.stringify(['host.lifecycle']);
 for(const [method,args] of [['cloud.enqueue',{token:'opaque'}],['cloud.localTransfer',{active:true,generation:1}],['cloud.workState',{}]] as const){const task=hostOperation(method,args).result,request=emitted.at(-1)!;expect(request).toMatchObject({method,args});events.push({t:'service.result',id:request.id,ok:true,value:{state:'queued'}});api.__pumpPodEvents();await task;}
});

 test('inline submit delivers its final text before submit even before open resolves; closed sessions ignore it',async()=>{
  const order:string[]=[];const pending=textInput.openInline('',{x:18,y:44,width:197,height:56},v=>order.push('change:'+v),()=>order.push('submit'));
  const open=emitted.at(-1)!;events.push({t:'input.inline.submit',session:open.id,value:'599'},{t:'service.result',id:open.id,ok:true,value:open.id});api.__pumpPodEvents();
  const session=await pending;expect(order).toEqual(['change:599','submit']);
  const closing=session.close(),close=emitted.at(-1)!;events.push({t:'input.inline.submit',session:open.id,value:'stale'},{t:'service.result',id:close.id,ok:true,value:null});api.__pumpPodEvents();await closing;expect(order).toEqual(['change:599','submit']);
 });


test('foreground playback keeps eight reserved requests when restore fills the IO window',async()=>{
 const {video,videoSessions}=await import('../src/services');host.capabilities=()=>JSON.stringify(['data.secure','media.video','media.video.sessions']);
 const io=Array.from({length:64},()=>hostOperation('secure.get',{key:'fixture'}));
 await expect(hostOperation('secure.get',{key:'extra'}).result).rejects.toMatchObject({code:'busy'});
 const controls=[hostOperation('video.retire',{}),hostOperation('video.session.promote',{sessionId:'next'})];
 expect(emitted.slice(-2).map(x=>x.method)).toEqual(['video.retire','video.session.promote']);
 for(const request of emitted.filter(x=>x.t==='service.request'))events.push({t:'service.result',id:request.id,ok:true,value:{ok:true}});
 api.__pumpPodEvents();await Promise.all([...io,...controls].map(x=>x.result));
});
test('decoded frame events carry session and generation independently of READY and unsubscribe',async()=>{
 const {onVideoFirstFrame}=await import('../src/services');const frames:unknown[]=[];const remove=onVideoFirstFrame(value=>frames.push(value));
 const value={hasRenderedFirstFrame:true,sessionId:'next',sourceGeneration:7};events.push({t:'video.firstFrame',value});api.__pumpPodEvents();expect(frames).toEqual([value]);
 remove();events.push({t:'video.firstFrame',value});api.__pumpPodEvents();expect(frames).toHaveLength(1);
});

test('native cookie completion and compact rendering options cross legacy/status auth without callback serialization',async()=>{
 host.capabilities=()=>JSON.stringify(['system.browser.auth','system.browser.auth.status']);
 for(const status of [false,true]){
  const operation=browserAuth.authorize({url:'https://example.com/login',cookieOrigin:'https://example.com',requiredCookieNames:['sid'],completeOnRequiredCookies:true,lowMemory:true,...(status?{onStatus:()=>{}}:{})});
  const request=emitted.at(-1)!;expect(request).toMatchObject({method:status?'browser.authorizeWithStatus':'browser.authorize',args:{completeOnRequiredCookies:true,lowMemory:true,requiredCookieNames:['sid']}});
  expect((request.args as any).onStatus).toBeUndefined();
  events.push({t:'service.result',id:request.id,ok:true,value:{cookie:'SYNTHETIC',origin:'https://example.com'}});api.__pumpPodEvents();
  expect(await operation.result).toMatchObject({cookie:'SYNTHETIC'});
 }
});

test('auth action metadata crosses browser and native credential-delete boundaries without caller timestamps',async()=>{
 host.capabilities=()=>JSON.stringify(['system.browser.auth.status','data.secure']);
 const auth=browserAuth.authorize({url:'https://example.com',cookieOrigin:'https://example.com',requiredCookieNames:['sid'],benchmark:{seq:101,attempt:4},onStatus:()=>{}}),request=emitted.at(-1)!;
 expect(request.args).toMatchObject({benchmark:{seq:101,attempt:4}});events.push({t:'service.result',id:request.id,ok:true,value:{cookie:'SYNTHETIC',origin:'https://example.com'}});api.__pumpPodEvents();await auth.result;
 const deleted=secureStorage.delete('fixture',{seq:102});const clear=emitted.at(-1)!;expect(clear).toMatchObject({method:'secure.delete',args:{key:'fixture',benchmark:{seq:102}}});
 expect(JSON.stringify(clear.args)).not.toContain('mono_ms');events.push({t:'service.result',id:clear.id,ok:true,value:{deleted:true}});api.__pumpPodEvents();await deleted;
});

test('silent video opt-in and explicit crown intent reach both native backends',async()=>{
 host.capabilities=()=>JSON.stringify(['media.video','media.video.sessions']);
 const {video,videoSessions}=await import('../src/services.ts');
 for(const backend of [video,videoSessions]){
  const session=backend===videoSessions;
  const options={bounds:{x:0,y:0,width:100,height:100},respectSilentMode:true};
  const op=session?videoSessions.prepare({...options,sessionId:'active'}):video.prepare(options);
  let request=emitted.at(-1)!;expect(request.args).toMatchObject({respectSilentMode:true});
  events.push({t:'service.result',id:request.id,ok:true,value:{ok:true}});api.__pumpPodEvents();await op.result;
  for(const direction of [1,-1] as const){
   const task=session?videoSessions.setVolume('active',.5,direction):video.setVolume(.5,direction);
   request=emitted.at(-1)!;expect(request.args).toMatchObject({volume:.5,crownDirection:direction});
   events.push({t:'service.result',id:request.id,ok:true,value:{ok:true}});api.__pumpPodEvents();await task;
  }
 }
});

test('numeric idle diagnostics preserve SQL arguments and cannot change settlement',async()=>{
 const {setServiceDiagnostics}=await import('../src/service-trace.ts');const completed:unknown[]=[];
 const args={sql:'SELECT ?',params:['PRIVATE_BIND']};
 try{
  setServiceDiagnostics({request:()=>7,completed:(...value)=>{completed.push(value);throw Error('observer failure');}});
  const op=hostOperation<Record<string,unknown>[]>('sql.query',args),request=emitted.at(-1)!;
  expect(request.args).toEqual({...args,diagnosticSite:7});expect(args).toEqual({sql:'SELECT ?',params:['PRIVATE_BIND']});
  events.push({t:'service.result',id:request.id,ok:true,value:[{answer:42}]});api.__pumpPodEvents();
  expect(await op.result).toEqual([{answer:42}]);expect(completed).toEqual([['sql.query',7,true]]);
  setServiceDiagnostics({request:()=>{throw Error('observer failure');},completed:()=>{throw Error('observer failure');}});
  const failed=hostOperation('sql.query',args),failure=emitted.at(-1)!;expect(failure.args).toEqual(args);
  events.push({t:'service.result',id:failure.id,ok:false,code:'original_failure',message:'original failure'});api.__pumpPodEvents();
  await expect(failed.result).rejects.toMatchObject({code:'original_failure'});
 }finally{setServiceDiagnostics(undefined);}
});
