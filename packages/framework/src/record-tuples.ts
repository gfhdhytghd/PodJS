/** Compact ordered JSON records for derived indexes. Unknown field names remain
 * strings; known names become indexes. Entry order and JSON values survive a
 * stringify/parse round trip. This does not preserve raw numeric token spelling. */
export function jsonRecordTupleCodec(fields:readonly string[]){
 const keys=[...fields];if(keys.some(k=>typeof k!=='string')||new Set(keys).size!==keys.length)throw Error('Invalid JSON record schema');
 const indexes=new Map(keys.map((key,i)=>[key,i]));
 return Object.freeze({
  encode(record:Record<string,unknown>):unknown[]{
   const tuple:unknown[]=[];for(const [key,value]of Object.entries(record)){
    if(value===undefined||typeof value==='function'||typeof value==='symbol')continue;
    tuple.push(indexes.get(key)??key,value);
   }return tuple;
  },
  decode(tuple:unknown):Record<string,unknown>{
   if(!Array.isArray(tuple)||tuple.length%2)throw Error('Invalid JSON record tuple');const out:Record<string,unknown>={};
   for(let i=0;i<tuple.length;i+=2){const tag=tuple[i];let key:string;
    if(typeof tag==='string')key=tag;else if(Number.isSafeInteger(tag)&&tag>=0&&tag<keys.length)key=keys[tag]!;else throw Error('Unknown JSON record field');
    if(Object.prototype.hasOwnProperty.call(out,key))throw Error('Duplicate JSON record field');
    if(key==='__proto__')Object.defineProperty(out,key,{value:tuple[i+1],writable:true,enumerable:true,configurable:true});else out[key]=tuple[i+1];
   }return out;
  },
 });
}
