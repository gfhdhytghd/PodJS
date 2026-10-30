import {test,expect} from 'bun:test';
import {javaHostMethods,literalHostMethods} from '../packages/cli/src/host-method-contract';
import {defineBackgroundHandler} from '../packages/framework/src/background-handler';
test('host method enumeration follows adapters and rejects computed/duplicate Java grants',()=>{
 expect([...literalHostMethods([{path:'ports.ts',text:`request<X>('file.read',{});operation('runtime.delay',{});transform('crypto.largeAesGcmDecrypt',b);transcode(b,'file.gunzip',7);host('sql.query',{});data('crypto.ecdhUnwrap',{});const ignored='file.delete';request(method,args);`}])].sort()).toEqual(['crypto.ecdhUnwrap','crypto.largeAesGcmDecrypt','file.gunzip','file.read','runtime.delay','sql.query']);
 const wrap=(body:string)=>`static final Set<String> METHODS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(${body})));`;
 expect([...javaHostMethods(wrap('"file.read","runtime.delay"'))]).toEqual(['file.read','runtime.delay']);
 for(const value of [wrap('"file.read",unsafe'),wrap('"file.read","file.read"'),'METHODS=everything;'])expect(()=>javaHostMethods(value)).toThrow();
});
test('headless codec scheduler is scoped to a handler and restored after success or failure',async()=>{
 const global=globalThis as {__podYieldCodecTurn?:()=>Promise<void>};const previous=global.__podYieldCodecTurn,owned=Object.prototype.hasOwnProperty.call(global,'__podYieldCodecTurn');
 let calls=0;const context={appId:'fixture',taskId:'fixture',deadlineMs:Date.now()+1000,payload:null,isCancelled:()=>false};
 try{delete global.__podYieldCodecTurn;
  const handler=defineBackgroundHandler(async()=>{await global.__podYieldCodecTurn!();return 'success';},{yieldTurn:async c=>{expect(c).toBe(context);calls++;}});
  expect(await handler(context)).toBe('success');expect(calls).toBe(1);expect(global.__podYieldCodecTurn).toBeUndefined();
  global.__podYieldCodecTurn=async()=>{};const outer=global.__podYieldCodecTurn;
  await expect(defineBackgroundHandler(async()=>{await global.__podYieldCodecTurn!();return 'success';},{yieldTurn:async()=>{throw Error('cancelled');}})(context)).rejects.toThrow('cancelled');
  expect(global.__podYieldCodecTurn).toBe(outer);
 }finally{if(owned)global.__podYieldCodecTurn=previous;else delete global.__podYieldCodecTurn;}
});
