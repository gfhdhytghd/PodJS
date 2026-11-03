import {p256} from './vendor/noble-p256.js';

/** Independent, opt-in P-256 module. No import-time IO or browser crypto globals.
 * Randomness must come from a host CSPRNG. SHA-256 returns a raw 32-byte digest.
 * Keep this module out of worker/headless entries that only require native verifyP256. */
export interface P256Ports {
 random(size:number):Promise<Uint8Array>;
 sha256(input:Uint8Array):Promise<Uint8Array>;
}
export interface P256KeyPair {
 /** DER SPKI with an uncompressed point. Each read returns a new public copy. */
 readonly publicKeySpki:Uint8Array;
 /** SHA256withECDSA; raw IEEE-P1363 64-byte r||s, low-S, hedged RFC6979 nonce. */
 sign(message:Uint8Array):Promise<Uint8Array>;
 /** Raw 32-byte ECDH X coordinate. The caller owns and must wipe this secret. */
 deriveSecret(peerSpki:Uint8Array):Uint8Array;
 /** Idempotent. All further private-key operations fail. */
 dispose():void;
}
const prefix=Uint8Array.from([0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]);
function publicPoint(spki:Uint8Array){
 if(!(spki instanceof Uint8Array)||spki.length!==91||prefix.some((v,i)=>spki[i]!==v)||spki[26]!==4)throw Error('invalid_p256_spki');
 const point=spki.slice(26);p256.ProjectivePoint.fromHex(point).assertValidity();return point;
}
export function validateP256PublicKey(spki:Uint8Array):void {publicPoint(spki);}
export function createP256(ports:P256Ports){
 return {
  async generateKeyPair():Promise<P256KeyPair>{
   let scalar:Uint8Array|undefined;
   for(let i=0;i<128;i++){
    const candidate=await ports.random(32);
    if(!(candidate instanceof Uint8Array)||candidate.length!==32){candidate?.fill(0);throw Error('invalid_p256_random');}
    if(p256.utils.isValidPrivateKey(candidate)){scalar=candidate;break;}
    candidate.fill(0);
   }
   if(!scalar)throw Error('invalid_p256_random');
   const privateKey=scalar;let disposed=false;
   const current=()=>{if(disposed)throw Error('p256_key_disposed');};
   try{
    const spki=new Uint8Array(91);spki.set(prefix);spki.set(p256.getPublicKey(privateKey,false),26);
    return {
     get publicKeySpki(){return spki.slice();},
     async sign(message){
      current();let digest:Uint8Array|undefined,entropy:Uint8Array|undefined;
      try{
       digest=await ports.sha256(message);current();
       if(!(digest instanceof Uint8Array)||digest.length!==32)throw Error('invalid_p256_digest');
       entropy=await ports.random(32);current();
       if(!(entropy instanceof Uint8Array)||entropy.length!==32)throw Error('invalid_p256_random');
       return p256.sign(digest,privateKey,{lowS:true,extraEntropy:entropy}).toCompactRawBytes();
      }finally{digest?.fill(0);entropy?.fill(0);}
     },
     deriveSecret(peerSpki){current();const shared=p256.getSharedSecret(privateKey,publicPoint(peerSpki),false);try{return shared.slice(1,33);}finally{shared.fill(0);}},
     dispose(){disposed=true;privateKey.fill(0);},
    };
   }catch(error){privateKey.fill(0);throw error;}
  },
 };
}
