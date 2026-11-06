/** Serializable streaming SHA-256. State is local trusted checkpoint data.
 * One update is bounded to 256KiB; callers yield between updates. */
export interface Sha256State {h:number[];tail:number[];length:number;}
const K=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];

export class ContentSha256 {
 private state:Sha256State;
 constructor(saved?:Sha256State){
  this.state=saved?{h:[...saved.h],tail:[...saved.tail],length:saved.length}:{h:[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19],tail:[],length:0};
  const s=this.state;if(s.h.length!==8||s.h.some(n=>!Number.isInteger(n)||n<0||n>4294967295)||s.tail.length>63||s.tail.some(n=>!Number.isInteger(n)||n<0||n>255)||!Number.isSafeInteger(s.length)||s.length<0||s.length%64!==s.tail.length)throw Error('invalid_sha_checkpoint');
 }
 snapshot():Sha256State{return {h:[...this.state.h],tail:[...this.state.tail],length:this.state.length};}
 update(input:Uint8Array){if(input.length>262144)throw Error('hash_unit_too_large');const s=this.state;let at=0;s.length+=input.length;
  if(s.tail.length){const n=Math.min(64-s.tail.length,input.length);s.tail.push(...input.subarray(0,n));at=n;if(s.tail.length===64){this.block(s.tail);s.tail=[];}}
  for(;at+64<=input.length;at+=64)this.block(input.subarray(at,at+64));s.tail.push(...input.subarray(at));return this;
 }
 finish(){const copy=new ContentSha256(this.snapshot()),bits=copy.state.length*8,pad=[...copy.state.tail,128];while(pad.length%64!==56)pad.push(0);const hi=Math.floor(bits/4294967296),lo=bits>>>0;for(let n=24;n>=0;n-=8)pad.push((hi>>>n)&255);for(let n=24;n>=0;n-=8)pad.push((lo>>>n)&255);for(let at=0;at<pad.length;at+=64)copy.block(pad.slice(at,at+64));return copy.state.h.map(n=>n.toString(16).padStart(8,'0')).join('');}
 private block(bytes:ArrayLike<number>){const h=this.state.h,rr=(x:number,n:number)=>(x>>>n)|(x<<(32-n));const w=new Array<number>(64);for(let i=0;i<16;i++){const p=i*4;w[i]=((bytes[p]<<24)|(bytes[p+1]<<16)|(bytes[p+2]<<8)|bytes[p+3])>>>0;}for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2],s0=rr(a,7)^rr(a,18)^(a>>>3),s1=rr(b,17)^rr(b,19)^(b>>>10);w[i]=(w[i-16]+s0+w[i-7]+s1)>>>0;}let[a,b,c,d,e,f,g,z]=h;for(let i=0;i<64;i++){const s1=rr(e,6)^rr(e,11)^rr(e,25),ch=(e&f)^(~e&g),t1=(z+s1+ch+K[i]+w[i])>>>0,s0=rr(a,2)^rr(a,13)^rr(a,22),maj=(a&b)^(a&c)^(b&c),t2=(s0+maj)>>>0;z=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0;}h[0]=(h[0]+a)>>>0;h[1]=(h[1]+b)>>>0;h[2]=(h[2]+c)>>>0;h[3]=(h[3]+d)>>>0;h[4]=(h[4]+e)>>>0;h[5]=(h[5]+f)>>>0;h[6]=(h[6]+g)>>>0;h[7]=(h[7]+z)>>>0;}
}
