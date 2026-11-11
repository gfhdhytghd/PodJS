import {expect,test} from 'bun:test';
const events:unknown[]=[],emitted:Record<string,any>[]=[];let capabilities:string[]=[];
(globalThis as {pod?:unknown}).pod={takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line)),capabilities:()=>JSON.stringify(capabilities)};
const api=await import('../src/watch.ts');
const {hostOperation}=await import('../src/services.ts');
test('foreground BLE setup operations require the dedicated capability and preserve session ownership on the wire',async()=>{
 await expect(hostOperation('provision.start',{deviceId:'watch',installId:'install'}).result).rejects.toThrow();expect(emitted).toHaveLength(0);
 capabilities=['net.ble.provision'];
 for(const [method,args] of [['provision.requestPermission',{}],['provision.start',{deviceId:'watch',installId:'install',transport:'internet'}],['provision.receive',{sessionId:'current'}],['provision.check',{sessionId:'current'}],['provision.sessionRequest',{sessionId:'current',path:'ownership/challenge',body:{}}],['provision.respond',{sessionId:'current',sequence:0,result:{success:true}}],['provision.close',{sessionId:'current'}]] as const){
  const op=hostOperation(method,args);const request=emitted.at(-1)!;expect(request.method).toBe(method);expect(request.args).toEqual(args);events.push({t:'service.result',id:request.id,ok:true,value:{accepted:true}});api.__pumpPodEvents();await expect(op.result).resolves.toEqual({accepted:true});
 }
});
test('cancelled BLE receive ignores a late credential response',async()=>{
 const task=hostOperation('provision.receive',{sessionId:'cancelled'}),request=emitted.at(-1)!;task.cancel();await expect(task.result).rejects.toMatchObject({code:'cancelled'});events.push({t:'service.result',id:request.id,ok:true,value:{secret:'must-not-revive-cancelled-receive'}});api.__pumpPodEvents();
});
for(const reason of ['user_back','background','user_declined','timeout','error',undefined,'unknown'] as const)test(`operation cancellation carries normalized reason ${reason}`,async()=>{
 const task=hostOperation('provision.start',{deviceId:'watch',installId:'install',transport:'internet'}),request=emitted.at(-1)!;
 (task.cancel as (reason?:string)=>void)(reason);
 expect(emitted.at(-1)).toEqual({t:'service.cancel',version:1,id:request.id,reason:reason&&reason!=='unknown'?reason:'error'});
 await expect(task.result).rejects.toMatchObject({code:'cancelled'});
 const before=emitted.length;task.cancel();expect(emitted).toHaveLength(before);
});
test('a known session retains one cleanup slot when the host operation table is full',async()=>{
 const tasks=Array.from({length:64},()=>hostOperation('provision.check',{sessionId:'owned'}));
 for(const task of tasks)void task.result.catch(()=>{});
 const before=emitted.length,close=hostOperation('provision.close',{sessionId:'owned',reason:'user_back'});
 void close.result.catch(()=>{});
 try{
  expect(emitted.length).toBe(before+1);const request=emitted.at(-1)!;
  expect(request.method).toBe('provision.close');events.push({t:'service.result',id:request.id,ok:true,value:{closed:true}});api.__pumpPodEvents();
  await expect(close.result).resolves.toEqual({closed:true});
 }finally{close.cancel();for(const task of tasks)task.cancel();}
});
