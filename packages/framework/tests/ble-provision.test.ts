import {expect,test} from 'bun:test';
const events:unknown[]=[],emitted:Record<string,any>[]=[];let capabilities:string[]=[];
(globalThis as {pod?:unknown}).pod={takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line)),capabilities:()=>JSON.stringify(capabilities)};
const api=await import('../src/watch.ts');
const {hostOperation}=await import('../src/services.ts');
test('foreground BLE setup operations require the dedicated capability and preserve session ownership on the wire',async()=>{
 await expect(hostOperation('provision.start',{deviceId:'watch',installId:'install'}).result).rejects.toThrow();expect(emitted).toHaveLength(0);
 capabilities=['net.ble.provision'];
 for(const [method,args] of [['provision.requestPermission',{}],['provision.start',{deviceId:'watch',installId:'install',transport:'internet'}],['provision.receive',{sessionId:'current'}],['provision.respond',{sessionId:'current',sequence:0,result:{success:true}}],['provision.close',{sessionId:'current'}]] as const){
  const op=hostOperation(method,args);const request=emitted.at(-1)!;expect(request.method).toBe(method);expect(request.args).toEqual(args);events.push({t:'service.result',id:request.id,ok:true,value:{accepted:true}});api.__pumpPodEvents();await expect(op.result).resolves.toEqual({accepted:true});
 }
});
test('cancelled BLE receive ignores a late credential response',async()=>{
 const task=hostOperation('provision.receive',{sessionId:'cancelled'}),request=emitted.at(-1)!;task.cancel();await expect(task.result).rejects.toMatchObject({code:'cancelled'});events.push({t:'service.result',id:request.id,ok:true,value:{secret:'must-not-revive-cancelled-receive'}});api.__pumpPodEvents();
});
