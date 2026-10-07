import {test,expect} from 'bun:test';
import {ActivityStateHub,ScrollRestoration,validScrollSnapshot} from '../src/navigation.ts';
import {FileLogger,createLogRedactor,type LogFile,type FileLoggerOptions} from '../src/logging.ts';

test('activity namespaces merge synchronously and acknowledgements do not replace new state',async()=>{
 const acks:Array<()=>void>=[];
 const hub=new ActivityStateHub(async()=>new Promise<void>(resolve=>acks.push(resolve)));
 hub.seed({saved:{one:1,two:2},retained:{one:'draft',two:'other'}});
 const a=hub.write({saved:{one:3},retained:{}},['one']);
 const b=hub.write({saved:{two:4},retained:{two:'latest'}},['two']);
 expect(hub.read()).toEqual({saved:{one:3,two:4},retained:{two:'latest'}});
 acks[1]!();await b;acks[0]!();await a;
 expect(hub.read().saved).toEqual({one:3,two:4});
});
test('late scroll anchor remains pending until explicit takeover',()=>{
 const pending={version:1 as const,offset:200,anchor:{index:2,offset:5}};
 const state=new ScrollRestoration(pending);
 expect(state.target(20,[{top:10,height:30}])).toBe(20);
 expect(state.snapshot(20)).toBe(pending);
 expect(state.target(120,[{top:10,height:30},{top:40,height:40},{top:80,height:50}])).toBe(75);
 state.cancel();expect(state.target(200)).toBeUndefined();expect(state.snapshot(-2)).toBe(0);
 const legacy=new ScrollRestoration(80);expect(legacy.target(20)).toBe(20);expect(legacy.target(100)).toBe(80);expect(legacy.target(120)).toBeUndefined();
 expect(validScrollSnapshot(pending)).toBe(true);expect(validScrollSnapshot({...pending,offset:Infinity})).toBe(false);
});
function memory(initial=''){
 let value=initial;const writes:string[]=[];
 const file:LogFile={read:async()=>value||null,size:async()=>Buffer.byteLength(value),replace:async text=>{value=text;writes.push(text);},append:async(text,offset)=>{expect(offset).toBe(Buffer.byteLength(value));value+=text;writes.push(text);}};
 return {file,writes,text:()=>value};
}
const redact=createLogRedactor({sensitivePattern:'password|secret'});
const options:FileLoggerOptions={redact,maxBytes:8,trim:lines=>lines.slice(1).join('\n')+'\n',writeFailureMessage:'logger write failed'};
test('redactor has neutral header/Bearer/url rules plus application pattern extension',()=>{
 expect(redact('Cookie: abc\npassword="two words"\nBearer xyz\nhttps://u:p@x/a?key=value')).toBe('Cookie:<redacted>\npassword=<redacted>\nBearer <redacted>\nhttps://<redacted>@x/a?<redacted>');
 expect(redact('domain_key=keep')).toBe('domain_key=keep');
 const custom=createLogRedactor({sensitivePattern:'domain_key',headerPattern:'x-private'});
 expect(custom('x-private: all private\ndomain_key=value')).toBe('x-private:<redacted>\ndomain_key=<redacted>');
});
test('production default clock/output, legacy sanitization, strict byte trim and FIFO',async()=>{
 const m=memory('1234567\n'),log=new FileLogger(m.file,options);
 await log.init();expect(m.writes).toEqual([]);
 log.i('Tag','password=hidden');await log.flush();
 expect(m.writes.length).toBe(1);expect(m.text()).toMatch(/^1234567\n\[\d{4}-\d{2}-\d{2} .*\] \[I\] \[Tag\] password=<redacted>\n$/);
 log.d('Tag','second');await log.flush();expect(m.writes.length).toBe(3);expect(m.text()).not.toContain('1234567');
 await log.clearLogs();expect(await log.readLogs()).toBeNull();
 await log.init('secret=old');expect(await log.readLogs()).toBe('secret=<redacted>');
});
test('file/output errors do not poison later writes; init/read errors still reject',async()=>{
 const m=memory();let fail=true;
 const log=new FileLogger({...m.file,append:async(text,offset)=>{if(fail){fail=false;throw Error('private');}await m.file.append(text,offset);}},options,undefined,()=>{throw Error('console');});
 log.w('T','first');log.e('T','second');await log.flush();expect(m.text()).toContain('second');
 const bad=new FileLogger({...m.file,read:async()=>{throw Error('read');}},options);
 await expect(bad.init()).rejects.toThrow('read');await expect(bad.readLogs()).rejects.toThrow('read');await bad.flush();
});
