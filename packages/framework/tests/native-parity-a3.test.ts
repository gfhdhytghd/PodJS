import {expect,test} from 'bun:test';
import {createRequestAllowlist} from '../src/request-policy.ts';
import {BrowserQrWait} from '../src/browser-qr-wait.ts';
import {kotlinTrim} from '../src/text-compat.ts';
test('request policy snapshots keys, preserves replies, and contains no app action names',()=>{
 const keys=['save'],reply={denied:true};const filter=createRequestAllowlist((r:{key:string})=>r.key,keys,()=>reply);
 keys.push('other');expect(filter({key:'save'})).toBeUndefined();expect(filter({key:'other'})).toBe(reply);
});
test('QR wait defaults off; configured wait persists after loading and stops for QR/verification/terminal',()=>{
 expect(new BrowserQrWait().status).toEqual({visible:false,message:''});
 const wait=new BrowserQrWait({message:'Acquire QR'});
 expect(wait.status.visible).toBe(true);expect(wait.update({type:'pageState',loading:false}).visible).toBe(true);
 expect(wait.update({type:'qr',kind:'login',mime:'image/png',imageBase64:'AAAA'}).visible).toBe(false);
 expect(wait.update({type:'qrCleared'}).visible).toBe(true);
 expect(wait.update({type:'verifying',active:true}).visible).toBe(false);
 expect(wait.update({type:'qrCleared'}).visible).toBe(false);
 expect(wait.update({type:'verifying',active:false}).visible).toBe(true);
 expect(wait.update({type:'qr',kind:'secondVerify',mime:'image/jpeg',imageBase64:'AAAA'}).visible).toBe(false);
 expect(wait.finish().visible).toBe(false);expect(wait.update({type:'qrCleared'}).visible).toBe(false);
 const cancelled=new BrowserQrWait({message:'Wait'});expect(cancelled.update({type:'cancelled'}).visible).toBe(false);
 expect(cancelled.update({type:'pageState',loading:true}).visible).toBe(false);
});
test('Kotlin trim excludes BOM while including JVM separator controls and preserving interior whitespace',()=>{
 expect(kotlinTrim('\u001c\u00a0x\u001f')).toBe('x');expect(kotlinTrim('\ufeffx\ufeff')).toBe('\ufeffx\ufeff');expect(kotlinTrim(' a\u001cb ')).toBe('a\u001cb');
});
