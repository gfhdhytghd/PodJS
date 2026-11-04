/** A generation is numeric; operation IDs deterministically order concurrent resets. */
export interface LifecycleToken {generation:number;operationId:string;startedAt:number}
export function validateLifecycleToken(value:unknown):asserts value is LifecycleToken {
 const v=value as LifecycleToken;
 if(!v||typeof v!=="object"||Array.isArray(v)||Object.keys(v).sort().join(',')!=="generation,operationId,startedAt"||!Number.isSafeInteger(v.generation)||v.generation<1||!Number.isSafeInteger(v.startedAt)||v.startedAt<0||typeof v.operationId!=="string"||!(/^[a-f0-9]{32}$/).test(v.operationId))throw Error('lifecycle_token_invalid');
}
export function compareLifecycleTokens(a?:LifecycleToken,b?:LifecycleToken):number {
 if(a)validateLifecycleToken(a);if(b)validateLifecycleToken(b);
 const x=a?.generation??0,y=b?.generation??0;
 if(x!==y)return x<y?-1:1;
 const l=a?.operationId??'',r=b?.operationId??'';return l===r?0:l<r?-1:1;
}
/** Reject exhausted counters before constructing a reset; never wrap an identity. */
export function nextLifecycleToken(old:LifecycleToken|undefined,operationId:string,now:number,clocks:readonly number[]):LifecycleToken {
 if(old)validateLifecycleToken(old);
 const generation=(old?.generation??0)+1,startedAt=Math.max(now,...clocks.map(n=>n+1));
 const next={generation,operationId,startedAt};validateLifecycleToken(next);return next;
}
