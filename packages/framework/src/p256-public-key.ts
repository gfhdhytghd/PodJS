/** Strict DER SPKI validation for the fixed uncompressed NIST P-256 profile.
 * Public coordinates only; no IO, signing, secret scalar, or host dependency.
 * P-256 has cofactor one, so every finite curve point is in the prime subgroup.
 */
const P256_SPKI_PREFIX=Uint8Array.from([0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00]);
const prime=0xffffffff00000001000000000000000000000000ffffffffffffffffffffffffn;
const b=0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604bn;
export function validateP256PublicKey(spki:Uint8Array):void {
 if(!(spki instanceof Uint8Array)||spki.length!==91||P256_SPKI_PREFIX.some((v,i)=>spki[i]!==v)||spki[26]!==4)throw Error('invalid_p256_spki');
 let x=0n,y=0n;for(let i=27;i<59;i++)x=(x<<8n)|BigInt(spki[i]!);for(let i=59;i<91;i++)y=(y<<8n)|BigInt(spki[i]!);
 if(x>=prime||y>=prime||y*y%prime!==((x*x%prime*x-3n*x+b)%prime+prime)%prime)throw Error('invalid_p256_spki');
}
