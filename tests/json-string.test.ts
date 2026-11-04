import {expect,test} from 'bun:test';
import {quoteAndroidJsonString} from '../packages/framework/src/json-string.ts';
import {referenceQuote} from './fixtures/android-quote-reference.ts';
test('Android quoting is byte-for-byte equal to the reference for controls, Unicode, slash escapes and malformed UTF16',()=>{
 const cases=['','"\\/\b\f\n\r\t','中🙂\u2028\u2029', '\ud800','\udc00','x\ud800x\udc00','🙂\udc00','\ud800🙂',Array.from({length:32},(_,i)=>String.fromCharCode(i)).join(''),'large🙂\n/\\"中'.repeat(30000)];
 let n=31;for(let i=0;i<100;i++){let value='';for(let j=0;j<80;j++){n=(Math.imul(n,1664525)+1013904223)>>>0;value+=String.fromCharCode(n&65535);}cases.push(value);}
 for(const value of cases)expect(quoteAndroidJsonString(value)).toBe(referenceQuote(value));
});

test('Android quoted byte lengths include slash escapes, Unicode and Java lone-surrogate replacement',async()=>{
 const {androidJsonQuotedByteLength}=await import('../packages/framework/src/json-string.ts');
 for(const [input,expected] of [['',2],['/',4],['🙂',6],['中文',8],['\ud800',3],['\u0001',8]] as const)expect(androidJsonQuotedByteLength(input)).toBe(expected);
 const value='abc/中🙂'.repeat(100000);expect(androidJsonQuotedByteLength(value)).toBe(Buffer.byteLength(referenceQuote(value)));
});

test('optional native quote sizing is bounded, preserves surrogate pairs and falls back without a host',async()=>{
 const {androidJsonQuotedByteLength}=await import('../packages/framework/src/json-string.ts');
 const root=globalThis as any,old=root.pod,slices:string[]=[];
 const referenceSize=(value:string)=>{const q=referenceQuote(value);let n=0;for(let i=0;i<q.length;i++){const c=q.charCodeAt(i);if(c>=0xd800&&c<=0xdbff){const l=q.charCodeAt(i+1);if(l>=0xdc00&&l<=0xdfff){n+=4;i++;}else n++;}else if(c>=0xdc00&&c<=0xdfff)n++;else n+=c<128?1:c<2048?2:3;}return n;};
 try{
  const text='a'.repeat(32767)+'🙂中\ud800/\n'.repeat(20000);
  root.pod={androidJsonQuotedByteLength:(part:string)=>{slices.push(part);return referenceSize(part);}};
  expect(androidJsonQuotedByteLength(text)).toBe(referenceSize(text));expect(slices.join('')).toBe(text);expect(Math.max(...slices.map(v=>v.length))).toBeLessThanOrEqual(32768);
  root.pod={androidJsonQuotedByteLength:()=>null};expect(androidJsonQuotedByteLength(text)).toBe(referenceSize(text));
  root.pod={androidJsonQuotedByteLength:()=>NaN};expect(()=>androidJsonQuotedByteLength('a')).toThrow('Invalid native JSON');
 }finally{root.pod=old;}
});


test('native well-formed checks avoid the surrogate regex for large bodies and preserve the legacy fallback',()=>{
 const prototype=String.prototype as string&{isWellFormed?:()=>boolean};
 const descriptor=Object.getOwnPropertyDescriptor(String.prototype,'isWellFormed'),testRegex=RegExp.prototype.test;
 const text='正文/🙂'.repeat(100000),expected=referenceQuote(text);
 try{
  // The installed QuickJS and current Bun both provide the native scalar scan.
  expect(typeof prototype.isWellFormed).toBe('function');
  RegExp.prototype.test=function(){throw Error('surrogate regex must stay unused');};
  expect(quoteAndroidJsonString(text)).toBe(expected);expect(quoteAndroidJsonString('x\ud800/')).toBe(referenceQuote('x\ud800/'));
  RegExp.prototype.test=testRegex;Object.defineProperty(String.prototype,'isWellFormed',{value:undefined,configurable:true});
  for(const value of ['plain/中🙂','x\ud800/','\udc00🙂'])expect(quoteAndroidJsonString(value)).toBe(referenceQuote(value));
 }finally{RegExp.prototype.test=testRegex;if(descriptor)Object.defineProperty(String.prototype,'isWellFormed',descriptor);else delete prototype.isWellFormed;}
});
