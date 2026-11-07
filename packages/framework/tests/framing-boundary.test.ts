import {test,expect} from 'bun:test';
import {FramingBoundary,framingAllowed} from '../src/framing-boundary';
const id='a'.repeat(32),sw='b'.repeat(32);
test('both peers enable only after the final complete legacy frame',async()=>{
 let authenticated=true,closed=0;const a=new FramingBoundary(id,()=>authenticated,async()=>{closed++;}),b=new FramingBoundary(id,()=>authenticated,async()=>{closed++;});
 await a.initiate(sw,async(action,body)=>{let response:any;const write=async(v:any)=>{expect(b.enabled).toBe(false);expect(a.enabled).toBe(false);response=v;};if(action.endsWith('prepareFraming'))await b.prepare(body,write);else await b.commit(body,write);return response;});
 expect(a.enabled).toBe(true);expect(b.enabled).toBe(true);authenticated=false;expect(a.enabled).toBe(false);expect(b.enabled).toBe(false);await a.close();await b.close();expect(closed).toBe(2);
});
test.each([undefined,'','a','A'.repeat(32),'c'.repeat(32)])('rejects illegal or mismatched ACK %s',async ack=>{
 let closed=0;const boundary=new FramingBoundary(id,()=>true,async()=>{closed++;});await expect(boundary.initiate(sw,async action=>action.endsWith('prepareFraming')?{switchId:sw,ready:true}:{switchId:ack,committed:true})).rejects.toThrow('invalid_framing');expect(boundary.enabled).toBe(false);expect(closed).toBe(1);
});
test('missing final ACK closes socket and retains owner cursor',async()=>{
 const cursor={applied:'opaque-cursor'};let closed=false;const b=new FramingBoundary(id,()=>true,async()=>{closed=true;});await expect(b.initiate(sw,async action=>{if(action.endsWith('prepareFraming'))return {switchId:sw,ready:true};throw Error('timeout');})).rejects.toThrow('timeout');expect(closed).toBe(true);expect(cursor.applied).toBe('opaque-cursor');expect(b.enabled).toBe(false);
});
test('old-session prepare, duplicate commit and ACK after close cannot enable',async()=>{
 const b=new FramingBoundary(id,()=>true,async()=>{});await expect(b.prepare({sessionId:'c'.repeat(32),framing:'WRP2',switchId:sw},async()=>{})).rejects.toThrow('invalid_framing');expect(b.enabled).toBe(false);
 const c=new FramingBoundary(id,()=>true,async()=>{});await c.prepare({sessionId:id,framing:'WRP2',switchId:sw},async()=>{});await c.commit({switchId:sw},async()=>{});await expect(c.commit({switchId:sw},async()=>{})).rejects.toThrow('invalid_framing');expect(c.enabled).toBe(false);
 const d=new FramingBoundary(id,()=>true,async()=>{});await expect(d.initiate(sw,async()=>{await d.close();return {switchId:sw,ready:true};})).rejects.toThrow('invalid_framing');expect(d.enabled).toBe(false);
});
test('B08 missing sessionCommitted and IDs cannot authorize framing',()=>{
 expect(framingAllowed({authenticated:true,scopeMatched:true,ready:true,commitAck:true})).toBe(false);
 expect(framingAllowed({authenticated:true,scopeMatched:true,ready:true,commitAck:true,sessionCommitted:true,switchId:sw,ackSwitchId:sw})).toBe(true);
});
