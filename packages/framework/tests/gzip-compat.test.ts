import {test,expect} from 'bun:test';
import {gzipSync,gunzipSync} from 'node:zlib';
import {matchGzipHash,stableGzipHeader} from '../src/crypto';
const hash=async(b:Uint8Array)=>new Bun.CryptoHasher('sha256').update(b).digest('hex');
test('recover Android gzip OS 0 and 255 only with exact full digest; preserve input',async()=>{const original=new Uint8Array(gzipSync('中文/\n'.repeat(1000))),other=original.slice();original[9]=0;other[9]=255;const recovered=await matchGzipHash(original,await hash(other),hash);expect(recovered).toEqual(other);expect(original[9]).toBe(0);expect(gunzipSync(recovered!).toString()).toBe('中文/\n'.repeat(1000));expect(await matchGzipHash(original,await hash(original),hash)).toBe(original);});
test('gzip compatibility never accepts changed deflate, plain data or a wrong digest',async()=>{const a=new Uint8Array(gzipSync('before')),b=new Uint8Array(gzipSync('after'));expect(await matchGzipHash(a,await hash(b),hash)).toBeNull();expect(await matchGzipHash(new Uint8Array([1,2,3]),'bad',hash)).toBeNull();});

test('failed digest probing does not mutate a Buffer view or its shared fixture',async()=>{const b=gzipSync('shared');const before=Buffer.from(b);expect(await matchGzipHash(b,'bad',hash)).toBeNull();expect(b).toEqual(before);});

test('new gzip headers are independent of mtime and OS; immutable bytes, deflate and CRC are preserved',()=>{
 const zipped=gzipSync('正文/🙂'.repeat(1000)),input=Buffer.from(zipped);input[4]=17;input[7]=99;input[9]=0;
 const before=Buffer.from(input),stable=stableGzipHeader(input);
 expect([...stable.subarray(0,10)]).toEqual([31,139,8,0,0,0,0,0,0,255]);expect(input).toEqual(before);
 expect(stable.subarray(10)).toEqual(new Uint8Array(input.subarray(10)));expect(gunzipSync(stable)).toEqual(gunzipSync(zipped));expect(stableGzipHeader(stable)).toBe(stable);
});
test('stable gzip header rejects truncated, plain and optional-header members rather than invalidating FHCRC',()=>{
 expect(()=>stableGzipHeader(new Uint8Array(17))).toThrow('invalid_stable_gzip_header');
 const zipped=new Uint8Array(gzipSync('x'));for(const flags of [1,2,4,8,16,32,128]){const candidate=zipped.slice();candidate[3]=flags;expect(()=>stableGzipHeader(candidate)).toThrow('invalid_stable_gzip_header');}
});
