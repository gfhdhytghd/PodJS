import {test,expect} from 'bun:test';
import {gzipSync,gunzipSync} from 'node:zlib';
import {matchGzipHash} from '../src/crypto';
const hash=async(b:Uint8Array)=>new Bun.CryptoHasher('sha256').update(b).digest('hex');
test('recover Android gzip OS 0 and 255 only with exact full digest; preserve input',async()=>{const original=new Uint8Array(gzipSync('中文/\n'.repeat(1000))),other=original.slice();original[9]=0;other[9]=255;const recovered=await matchGzipHash(original,await hash(other),hash);expect(recovered).toEqual(other);expect(original[9]).toBe(0);expect(gunzipSync(recovered!).toString()).toBe('中文/\n'.repeat(1000));expect(await matchGzipHash(original,await hash(original),hash)).toBe(original);});
test('gzip compatibility never accepts changed deflate, plain data or a wrong digest',async()=>{const a=new Uint8Array(gzipSync('before')),b=new Uint8Array(gzipSync('after'));expect(await matchGzipHash(a,await hash(b),hash)).toBeNull();expect(await matchGzipHash(new Uint8Array([1,2,3]),'bad',hash)).toBeNull();});

test('failed digest probing does not mutate a Buffer view or its shared fixture',async()=>{const b=gzipSync('shared');const before=Buffer.from(b);expect(await matchGzipHash(b,'bad',hash)).toBeNull();expect(b).toEqual(before);});
