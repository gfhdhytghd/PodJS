import {test,expect} from 'bun:test';
import {generateKeyPairSync} from 'node:crypto';
import {validateP256PublicKey} from '../packages/framework/src/p256-public-key';
import {p256} from '../packages/framework/src/vendor/noble-p256.js';
const P256_SPKI_PREFIX=new Uint8Array(generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({format:'der',type:'spki'})).slice(0,26);
const accepts=(f:()=>void)=>{try{f();return true}catch{return false}};
const reference=(b:Uint8Array)=>{if(b.length!==91||P256_SPKI_PREFIX.some((v,i)=>b[i]!==v)||b[26]!==4)throw Error('encoding');p256.ProjectivePoint.fromHex(b.slice(26)).assertValidity();};
test('fixed SPKI public validation matches frozen noble on generated and mutated keys',()=>{
 for(let i=0;i<32;i++){
  const bytes=new Uint8Array(generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({format:'der',type:'spki'}));expect(accepts(()=>validateP256PublicKey(bytes))).toBe(true);
  for(let j=0;j<bytes.length;j++){const changed=bytes.slice();changed[j]^=1;expect(accepts(()=>validateP256PublicKey(changed))).toBe(accepts(()=>reference(changed)));}
 }
});
test('fixed SPKI rejects invalid DER, infinity, out-of-field and off-curve points',()=>{
 const valid=new Uint8Array(generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({format:'der',type:'spki'}));
 const zero=valid.slice();zero.fill(0,27);const overflow=valid.slice();overflow.fill(255,27,59);
 for(const b of [new Uint8Array(),valid.slice(1),new Uint8Array(92),zero,overflow])expect(accepts(()=>validateP256PublicKey(b))).toBe(false);
});
