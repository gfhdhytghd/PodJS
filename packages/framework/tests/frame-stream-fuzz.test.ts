import {test,expect} from 'bun:test';
import {StreamFrameConnection,createFrameFacade,FrameLengthError,type FrameByteStream} from '../src/frame-stream';

const facade=createFrameFacade<Uint8Array,{}>({maxFrameBytes:131072,encodeBody:b=>b,decodeBody:b=>b,encodeBodyAsync:async b=>b,decodeBodyAsync:async b=>b});
const codec={maxFrameBytes:131072,encode:async(b:Uint8Array)=>facade.encodeFrame(b),decode:async(b:Uint8Array)=>facade.decodeFrame(b)};
function random(seed:number){let x=seed>>>0;return()=>{x^=x<<13;x^=x>>>17;x^=x<<5;return x>>>0;};}
function concat(parts:Uint8Array[]){const out=new Uint8Array(parts.reduce((n,b)=>n+b.length,0));let at=0;for(const b of parts){out.set(b,at);at+=b.length;}return out;}
function input(parts:Uint8Array[]){let at=0,offset=0,reads=0,closed=0;const stream:FrameByteStream={
 read:async max=>{reads++;await Promise.resolve();if(at===parts.length)return null;const p=parts[at]!,n=Math.min(max,p.length-offset),view=p.subarray(offset,offset+n);offset+=n;if(offset===p.length){at++;offset=0;}return view;},
 write:async()=>{},close:async()=>{closed++;},
};return {stream,reads:()=>reads,closed:()=>closed};}

test('fixed seeds fuzz transport chunks, prefix/body cuts, coalesced frames and nonzero byte offsets',async()=>{
 for(let seed=1;seed<=128;seed++){
  const next=random(seed),bodies=Array.from({length:24},(_,i)=>{const n=i<7?[1,2,3,4,255,256,4096][i]!:1+next()%8192,b=new Uint8Array(n);for(let j=0;j<n;j++)b[j]=next()&255;return b;}),wire=concat(bodies.map(b=>facade.encodeFrame(b))),parts:Uint8Array[]=[];
  // Force all three prefix split positions, then random packet sizes. Every
  // transport view deliberately has a nonzero offset in a larger allocation.
  for(let at=0,part=0;at<wire.length;part++){const n=Math.min(wire.length-at,part<3?1:1+next()%16384),backing=new Uint8Array(n+11);backing.set(wire.subarray(at,at+n),7);parts.push(backing.subarray(7,7+n));at+=n;}
  const f=input(parts),conn=new StreamFrameConnection(f.stream,{...codec,chunkBytes:1+next()%16384});let readBytes=0;conn.setTransferProgress(p=>{if(p.direction==='read')readBytes+=p.bytes;});
  for(const body of bodies)expect(await conn.readFrame(5000)).toEqual(body);
  expect(readBytes).toBe(wire.length);expect(f.closed()).toBe(0);await conn.close();expect(f.closed()).toBe(1);
  const decoder=new facade.FrameDecoder(),decoded=parts.flatMap(p=>decoder.push(p));decoder.finish();expect(decoded).toEqual(bodies);
 }
});

test('64KiB body boundaries retain the next prefix through arbitrary short reads',async()=>{
 const bodies=[65535,65536,65537,131072,1].map((n,i)=>new Uint8Array(n).fill(i+1)),wire=concat(bodies.map(b=>facade.encodeFrame(b)));
 const parts=[];for(let at=0;at<wire.length;at+=65533)parts.push(wire.subarray(at,at+65533));
 const f=input(parts),conn=new StreamFrameConnection(f.stream,{...codec,chunkBytes:65536});for(const body of bodies)expect(await conn.readFrame(5000)).toEqual(body);await conn.close();
});

test('two connection wrappers cannot steal one byte stream reader header/body',async()=>{
 const body=Uint8Array.of(255,255,255,255,1,2,3),f=input([facade.encodeFrame(body)]),a=new StreamFrameConnection(f.stream,codec),b=new StreamFrameConnection(f.stream,codec);
 const first=a.readFrame(5000);const rejected=b.readFrame(5000);void first.catch(()=>{});
 await expect(rejected).rejects.toThrow('Concurrent sync frame reads');expect(await first).toEqual(body);expect(f.closed()).toBe(0);await a.close();
});

test('two connection wrappers cannot interleave one byte stream writer frames',async()=>{
 const writes:Uint8Array[]=[],stream:FrameByteStream={read:async()=>null,write:async bytes=>{writes.push(bytes.slice());await Promise.resolve();},close:async()=>{}},a=new StreamFrameConnection(stream,codec),b=new StreamFrameConnection(stream,codec),body=Uint8Array.of(7,8,9);
 const first=a.writeFrame(body);await expect(b.writeFrame(Uint8Array.of(1))).rejects.toThrow('Concurrent sync frame writes');await first;expect(concat(writes)).toEqual(facade.encodeFrame(body));
});

test('separate streams and full duplex remain independent while an invalid prefix still closes once',async()=>{
 const f=input([facade.encodeFrame(Uint8Array.of(9))]),a=new StreamFrameConnection(f.stream,codec),other=input([facade.encodeFrame(Uint8Array.of(8))]),b=new StreamFrameConnection(other.stream,codec);
 const reading=a.readFrame(5000);await a.writeFrame(Uint8Array.of(7));expect(await reading).toEqual(Uint8Array.of(9));expect(await b.readFrame(5000)).toEqual(Uint8Array.of(8));
 const invalid=input([Uint8Array.of(255,255,255,255)]),bad=new StreamFrameConnection(invalid.stream,codec);const error=await bad.readFrame(5000).catch(e=>e);expect(error).toBeInstanceOf(FrameLengthError);expect(error.message).toBe('Sync frame length out of range');expect(error.length).toBe(-1);expect(error.maxFrameBytes).toBe(codec.maxFrameBytes);expect(invalid.closed()).toBe(1);await bad.close();expect(invalid.closed()).toBe(1);
});

test('a finished frame releases stream ownership to the next wrapper in both directions',async()=>{
 const f=input([concat([facade.encodeFrame(Uint8Array.of(1)),facade.encodeFrame(Uint8Array.of(2))])]),a=new StreamFrameConnection(f.stream,codec),b=new StreamFrameConnection(f.stream,codec);
 expect(await a.readFrame(5000)).toEqual(Uint8Array.of(1));expect(await b.readFrame(5000)).toEqual(Uint8Array.of(2));await a.writeFrame(Uint8Array.of(3));await b.writeFrame(Uint8Array.of(4));await a.close();
});
