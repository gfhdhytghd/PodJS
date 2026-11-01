import {expect,test} from 'bun:test';
import {HostCallsiteTrace,hostSizeBand} from '../src/host-callsite';
test('callsite summaries close identifiers, discard arguments and count only explicit receipts',()=>{
 const t=new HostCallsiteTrace([0,1,127],[0,1,2]);
 for(const bad of [-1,128,NaN,'1',1.5])t.observe({callsiteId:bad,sizeBand:0,path:'private',dataBase64:'secret'});
 for(const bad of [-1,6,NaN,'1',1.5])t.observe({callsiteId:1,sizeBand:bad});
 t.observe({callsiteId:127,sizeBand:5,path:'secret'});t.observe({callsiteId:1,sizeBand:0});t.observe({callsiteId:1,sizeBand:0});
 expect(t.summary()).toEqual({event:'host-callsite-summary',counts:[[1,2],[127,1]],bands:[2,0,0,0,0,1],lastReceiptClass:0,receipts:0});
 t.receipt(2);t.receipt(900);expect(t.summary().lastReceiptClass).toBe(2);expect(t.summary().receipts).toBe(1);
 expect(JSON.stringify(t.summary())).not.toContain('secret');
});
test('size bands use inclusive bounds, including unknown sizes',()=>{
 expect([0,65536,65537,262144,262145,1048576,1048577,8388608,8388609,-1,NaN,Infinity].map(hostSizeBand)).toEqual([0,0,1,1,2,2,3,3,4,5,5,5]);
 expect(()=>new HostCallsiteTrace([128],[0])).toThrow();
});
