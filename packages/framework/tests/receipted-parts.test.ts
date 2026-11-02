import {test,expect} from 'bun:test';
import {receiptedParts} from '../src/work-window';
test('receipted immutable layouts require every exact piece, including empty and short tails',()=>{
 expect(receiptedParts(0,4,()=>0)).toEqual({offsets:[0],lengths:[0]});
 expect(receiptedParts(10,4,offset=>offset===8?2:4)).toEqual({offsets:[0,4,8],lengths:[4,4,2]});
 expect(receiptedParts(10,4,()=>4)).toBeUndefined();
 expect(receiptedParts(4,4,()=>undefined)).toBeUndefined();
 expect(receiptedParts(8,4,()=>4,1)).toBeUndefined();
 // Independent new calls consume only durable state; no cross-run cache.
 const receipts=new Map([[0,4]]);expect(receiptedParts(8,4,o=>receipts.get(o))).toBeUndefined();receipts.set(4,4);
 expect(receiptedParts(8,4,o=>receipts.get(o))).toEqual({offsets:[0,4],lengths:[4,4]});
 for(const [length,size]of [[-1,4],[1.5,4],[1,0],[1,NaN],[Infinity,4]])expect(()=>receiptedParts(length,size,()=>4)).toThrow('invalid_receipted_parts');
});
