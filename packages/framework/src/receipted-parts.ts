/** Reconstruct a canonical immutable piece layout from authenticated receipts.
 * The caller binds receipts to the input and scope. This is a read plan, not
 * proof of present file integrity: the consumer must authenticate every piece.
 * Undefined selects the caller's legacy/noncanonical index reader. */
export function receiptedParts(length:number,partBytes:number,receipt:(offset:number)=>number|undefined,maximumParts=2048):{offsets:number[];lengths:number[]}|undefined {
 if(!Number.isSafeInteger(length)||length<0||!Number.isSafeInteger(partBytes)||partBytes<1||!Number.isSafeInteger(maximumParts)||maximumParts<1)throw Error('invalid_receipted_parts');
 const count=Math.max(1,Math.ceil(length/partBytes));if(count>maximumParts)return;
 const offsets:number[]=[],lengths:number[]=[];
 for(let i=0;i<count;i++){const offset=i*partBytes,size=Math.min(partBytes,length-offset);if(receipt(offset)!==size)return;offsets.push(offset);lengths.push(size);}
 return {offsets,lengths};
}
