/** Pure, schema-driven atomic register codec. Null means the portable reader
 * must validate/merge the input. No JNI, storage or service queue is involved. */
export interface RegisterMergePlan {
 root:Record<string,unknown>;outputOrder:number[];
 fields:{key:string;kind:'string'|'nullableString'|'uint'|'int'|'unit'|'boolean'|'sources';required?:boolean;nonblank?:boolean}[];
 registers:{name:string;fields:number[];tuple:{field?:number;source?:boolean;invert?:boolean}[]}[];
 forbidden:string[];sourceField:number;sourcesField:number;identity:number;metadata:number;nonzero:[number,number][];maxBytes:number;
}
export function createRegisterMerge(plan:RegisterMergePlan){
 const schema=JSON.stringify(plan);
 return (left:string,right:string,leftRaw?:string,rightRaw?:string):{value:Record<string,any>;orders:number[];source:string;unknownBytes:number}|undefined=>{
  const root=globalThis as {pod?:{mergeJsonRegisters?:Function};__podByteCodecs?:{mergeJsonRegisters?:Function};__podDisableRegisterMerge?:boolean};
  if(root.__podDisableRegisterMerge)return;
  return (root.pod??root.__podByteCodecs)?.mergeJsonRegisters?.(schema,left,right,leftRaw,rightRaw)??undefined;
 };
}
