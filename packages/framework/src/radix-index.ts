/** Local bounded radix inventory, independent of any network object format.
 * Only affected root-to-leaf paths are read/rebuilt. Callers commit the returned
 * nodes together with their rows and checkpoint in their own short CAS writer. */
export type RadixRow={key:string;value:unknown};
export type RadixChild={digit:string;hash:string;count:number};
export type RadixNode={version:1;domain:'podjs/local-radix-index';prefix:string}&({form:'leaf';rows:RadixRow[]}|{form:'node';children:RadixChild[]});
export interface RadixPorts {read(prefix:string):Promise<{hash:string;node:RadixNode}|null>;encode(value:RadixNode):Uint8Array;hash(bytes:Uint8Array):string}
export async function updateRadixIndex(ports:RadixPorts,changes:RadixRow[]){
 const keys=new Set<string>();for(const row of changes){if(!/^[0-9a-f]{64}$/.test(row.key)||keys.has(row.key))throw Error('invalid_index_key');keys.add(row.key);}
 const writes:{prefix:string;hash:string;node:RadixNode;bytes:number;count:number}[]=[];
 const walk=async(prefix:string,patch:RadixRow[],expected?:string):Promise<{hash:string;count:number}>=>{
  const saved=await ports.read(prefix);if(expected&&!saved)throw Error('index_missing');if(saved&&(ports.hash(ports.encode(saved.node))!==saved.hash||expected!==undefined&&saved.hash!==expected||saved.node.prefix!==prefix))throw Error('index_hash_mismatch');
  let node:RadixNode,count=0;
  if(!saved||saved.node.form==='leaf'){
   const rows=new Map((saved?.node.form==='leaf'?saved.node.rows:[]).map(row=>[row.key,row]));for(const row of patch)rows.set(row.key,row);const sorted=[...rows.values()].sort((a,b)=>a.key<b.key?-1:a.key>b.key?1:0);count=sorted.length;
   const leaf:RadixNode={version:1,domain:'podjs/local-radix-index',prefix,form:'leaf',rows:sorted};
   if(count<=256&&ports.encode(leaf).length<=262144)node=leaf;
   else {
    if(prefix.length>=64)throw Error('index_row_too_large');const children:RadixChild[]=[];
    for(const digit of '0123456789abcdef'){const group=sorted.filter(row=>row.key[prefix.length]===digit);if(group.length)children.push({digit,...await walk(prefix+digit,group)});}node={version:1,domain:'podjs/local-radix-index',prefix,form:'node',children};
   }
  }else{
   const children=new Map(saved.node.children.map(child=>[child.digit,child]));
   for(const digit of '0123456789abcdef'){const group=patch.filter(row=>row.key[prefix.length]===digit);if(group.length)children.set(digit,{digit,...await walk(prefix+digit,group,children.get(digit)?.hash)});}
   const sorted=[...children.values()].sort((a,b)=>a.digit<b.digit?-1:1);count=sorted.reduce((n,child)=>n+child.count,0);node={version:1,domain:'podjs/local-radix-index',prefix,form:'node',children:sorted};
  }
  if(count>1000000)throw Error('index_too_large');const bytes=ports.encode(node),hash=ports.hash(bytes);writes.push({prefix,hash,node,bytes:bytes.length,count});return {hash,count};
 };
 const root=await walk('',changes);return {root,writes};
}
