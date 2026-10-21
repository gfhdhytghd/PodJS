import {test,expect} from 'bun:test';
import {createHash,createHmac} from 'node:crypto';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import v from './fixtures/v2-vectors.json';
import {lengthPrefix,hex,unhex,decimalCode,createCrypto} from '../src/crypto';
const hash=(b:Uint8Array)=>createHash('sha256').update(b).digest();
const {userId:u,libraryEpoch:e}=v.inputs,a=v.approval;
const key=[v.inputs.K1_hex,v.inputs.K2_hex,v.inputs.K3_hex].map(unhex);
const frame=lengthPrefix('watchrss/approval/v2',u,e,a.requestId,a.requesterDeviceId,Buffer.from(a.requesterSpki_b64,'base64'),a.approverDeviceId,Buffer.from(a.approverSpki_b64,'base64'),Buffer.from(a.requesterNonce_b64,'base64'),Buffer.from(a.approverNonce_b64,'base64'));
test('framework v2 byte vectors: LP, HMAC, chain, attestation, transcript, modulo',async()=>{
 const crypto=createCrypto(async<T>(m:string,args:any)=>{expect(m).toBe('crypto.hmacSha256');return{digestBase64:createHmac('sha256',Buffer.from(args.keyBase64,'base64')).update(Buffer.from(args.dataBase64,'base64')).digest('base64')} as T;});
 expect(hex(lengthPrefix(...v.lp.fields))).toBe(v.lp.hex);
 for(let i=0;i<3;i++){expect(hex(await crypto.hmacSha256(key[i],lengthPrefix('watchrss/key-commit/v2',u,e,i+1)))).toBe((v.commit as any)[i+1]);if(i)expect(hex(await crypto.hmacSha256(key[i-1],lengthPrefix('watchrss/key-rotate/v2',u,e,i+1,(v.commit as any)[i+1])))).toBe((v.rotationProof as any)[i+1]);}
 expect(hex(await crypto.hmacSha256(key[2],lengthPrefix('watchrss/device-attest/v2',u,e,v.attest.deviceId,hash(Buffer.from(v.attest.spki_b64,'base64')))))).toBe(v.attest.mac);
 expect(hex(frame)).toBe(a.transcript_hex);expect(hex(hash(frame))).toBe(a.transcriptSha256_hex);expect(decimalCode(hash(frame))).toBe(a.code);
 expect(hex(hash(lengthPrefix('watchrss/approval-commit/v2',Buffer.from(a.requesterSpki_b64,'base64'),Buffer.from(a.requesterNonce_b64,'base64'))))).toBe(a.commitment);
 expect(hex(await crypto.hmacSha256(key[0],lengthPrefix('watchrss/key-rotate/v2',u,e,3,v.commit['3'])))).toBe(v.negative.rotationProof3_with_wrong_prev);
});
test('actual JVM P-256 verifier accepts vector raw r||s and rejects tamper/wrong curve/truncation',()=>{
 const root=resolve(import.meta.dir,'../../..'),dir=mkdtempSync(join(tmpdir(),'cv2-podjs-jvm-'));
 try{const compile=Bun.spawnSync(['javac','-d',dir,root+'/platforms/android/runtime/src/main/java/dev/podjs/runtime/PodCloudCrypto.java',root+'/tests/java/PodP256VerifyTest.java']);expect(compile.exitCode).toBe(0);
 const data=lengthPrefix('watchrss/approval-deliver/v2',hash(frame),a.payload);const run=Bun.spawnSync(['java','-cp',dir,'PodP256VerifyTest',a.approverSpki_b64,Buffer.from(data).toString('base64'),a.signature_b64]);expect({code:run.exitCode,stderr:run.stderr.toString()}).toEqual({code:0,stderr:''});
 }finally{rmSync(dir,{recursive:true,force:true});}
});
test('P256 host adapter preserves exact bytes and bounds signature shape',async()=>{let calls=0;const c=createCrypto(async<T>(method:string,args:any)=>{calls++;expect(method).toBe('crypto.verifyP256');expect(Buffer.from(args.dataBase64,'base64')).toEqual(Buffer.from(frame));return{valid:true} as T;});expect(await c.verifyP256(new Uint8Array(),frame,new Uint8Array(63))).toBe(false);expect(calls).toBe(0);expect(await c.verifyP256(Buffer.from(a.approverSpki_b64,'base64'),frame,new Uint8Array(64))).toBe(true);});

test('R4 manifest-v3 vector matches actual JVM HKDF and AES-GCM bytes',async()=>{
 const v=(await import('./fixtures/manifest-v3.json')).default,root=resolve(import.meta.dir,'../../..'),dir=mkdtempSync(join(tmpdir(),'cv2-podjs-v3-'));
 try{expect(Bun.spawnSync(['javac','-d',dir,root+'/platforms/android/runtime/src/main/java/dev/podjs/runtime/PodCloudCrypto.java',root+'/tests/java/PodManifestV3Test.java']).exitCode).toBe(0);const run=Bun.spawnSync(['java','-cp',dir,'PodManifestV3Test',v.accountKeyHex,v.saltHex,v.nonceHex,v.hkdfInfo,v.derivedKeyHex,v.aadUtf8,v.plaintextUtf8,v.ciphertextAndTagHex]);expect({code:run.exitCode,stderr:run.stderr.toString()}).toEqual({code:0,stderr:''});}finally{rmSync(dir,{recursive:true,force:true});}
});
