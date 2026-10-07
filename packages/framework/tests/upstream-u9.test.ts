import {expect,test} from 'bun:test';
import {lineStarts,anchorAtOffset,offsetForAnchor} from '../src/text-anchor.ts';
import {sunTimes,scheduledDark,scheduledDarkNow} from '../src/theme-schedule.ts';
import {parseJsonObjectNumbers,jsonNumberToken,isJsonInteger} from '../src/json-numbers.ts';
import {readHttpRequest} from '../src/http-request.ts';
import {BoundedLineBuffer} from '../src/logging.ts';
import {restoreIfOwned} from '../src/startup.ts';
test('text positions preserve UTF-16, missing-line cursor and fractional offsets',()=>{
 const starts=lineStarts('😀a\r\nb\r\nc',['😀a','b','c','missing']);expect(starts).toEqual([0,4,6,7]);
 expect(anchorAtOffset(25,20,starts)).toEqual({character:4,fraction:.25});
 expect(offsetForAnchor({character:5,fraction:.5},20,starts,100)).toBe(30);
 expect(anchorAtOffset(-10,0,[])).toEqual({character:0,fraction:0});
});
test('daily schedule defaults construct without timezone/clock injection and support overnight fallback',()=>{
 const s={mode:'FIXED_TIME' as const,lightStartMinutes:1200,darkStartMinutes:400,latitude:null,longitude:null};
 expect(scheduledDark(s,{year:2026,month:10,day:7},800)).toBe(true);expect(scheduledDark(s,{year:2026,month:10,day:7},1200)).toBe(false);
 expect(typeof scheduledDarkNow(s)).toBe('boolean');
 expect(sunTimes({year:2026,month:2,day:30},0,0)).toBeNull();
 expect(sunTimes({year:2026,month:6,day:21},90,0)).toBeNull();
 const times=sunTimes({year:2026,month:10,day:7},0,0);expect(times?.sunrise).toBeGreaterThanOrEqual(0);expect(times?.sunset).toBeLessThan(1440);
});
test('number parser default contract retains tokens, safe integers and duplicate deletion',()=>{
 const obj=parseJsonObjectNumbers('{"a":1.0,"b":1e0,"c":-0,"d":9007199254740992,"a":1,"null":2,"null":null,"arr":[1,1.0]}');
 expect(jsonNumberToken(obj,'a')).toBe('1');expect(isJsonInteger(obj,'a')).toBe(true);expect(isJsonInteger(obj,'b')).toBe(false);expect(isJsonInteger(obj,'c')).toBe(true);expect(isJsonInteger(obj,'d')).toBe(false);expect(jsonNumberToken(obj,'null')).toBeUndefined();expect(jsonNumberToken(obj.arr as object,'1')).toBe('1.0');
 expect(isJsonInteger({value:1},'value')).toBe(true);
 expect(()=>parseJsonObjectNumbers('[]')).toThrow('JSON value must be an object');
 const error=Error('policy');expect(()=>parseJsonObjectNumbers('{"x":{"y":1}}',{maxDepth:1,depthError:()=>error})).toThrow(error);
});
test('HTTP reader default invocation and configured byte limits retain read contract',async()=>{
 const bytes=new TextEncoder().encode('POST /path HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc');let at=0;
 const result=await readHttpRequest({read:async max=>{const part=bytes.slice(at,at+max);at+=part.length;return part;}});
 expect(result).toMatchObject({method:'POST',target:'/path',body:'abc'});expect(Object.getPrototypeOf(result.headers)).toBeNull();
 at=0;await expect(readHttpRequest({read:async max=>{const part=bytes.slice(at,at+Math.min(max,2));at+=part.length;return part;}},{readBytes:2,maxBodyBytes:2})).rejects.toThrow('Body too large');
 at=0;await expect(readHttpRequest({read:async max=>{const part=bytes.slice(at,at+max);at+=part.length;return part;}},{maxHeaderBytes:5})).rejects.toThrow('Headers too large');
});
test('bounded lines are instance local and count UTF-16 units; zero capacity is safe',()=>{
 const a=new BoundedLineBuffer({maxLines:2,maxChars:5,maxLineChars:4}),b=new BoundedLineBuffer({maxLines:2,maxChars:5,maxLineChars:4});
 a.append('abcdef');a.append('😀');expect(a.dump()).toBe('😀');a.append('x');expect(a.size).toBe(2);expect(a.dump()).toBe('😀\nx');expect(b.size).toBe(0);a.clear();expect(a.dump()).toBe('');
 const zero=new BoundedLineBuffer({maxLines:0,maxChars:0,maxLineChars:1});zero.append('x');expect(zero.size).toBe(0);
 for(const invalid of [-1,NaN,Infinity,.5])expect(()=>new BoundedLineBuffer({maxLines:invalid,maxChars:10,maxLineChars:2})).toThrow();
});
test('owned state cleanup preserves a new publisher and original call order',()=>{
 let state={},calls:string[]=[];const cleanup=restoreIfOwned(()=>{calls.push('apply');state={};},()=>{calls.push('snapshot');return state;},()=>{calls.push('restore');});
 state={};cleanup();expect(calls).toEqual(['apply','snapshot','snapshot']);
 calls=[];const owned=restoreIfOwned(()=>calls.push('apply'),()=>state,()=>calls.push('restore'));owned();expect(calls).toEqual(['apply','restore']);
});
