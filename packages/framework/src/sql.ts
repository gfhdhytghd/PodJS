export type SqlParams=readonly unknown[];
export interface SqlQueryPort {query(sql:string,params?:SqlParams):Promise<Record<string,unknown>[]>}
/** Host queries cap rows and serialized bytes. Callers supply a stable order;
 * their surrounding revision checks reject a concurrently shifted result. */
export async function querySqlPages(db:SqlQueryPort,query:string,params:SqlParams=[],pageSize=400):Promise<Record<string,unknown>[]>{
 const result:Record<string,unknown>[]=[];
 let offset=0,size=pageSize;
 while(true){
  let rows:Record<string,unknown>[];
  try{rows=await db.query(query+' LIMIT ? OFFSET ?',[...params,size,offset]);}
  catch(error){
   if(size>1&&/query result too large/i.test(String(error))){size=Math.max(1,Math.floor(size/2));continue;}
   throw error;
  }
  result.push(...rows);offset+=rows.length;if(rows.length<size)return result;
 }
}

export interface SqlTextOptions {maxBytes?:number;errors?:{invalid:string;changed:string;boundary:string}}
/** Read a trusted single-column SELECT (aliased `value`) in bounded byte pages.
 * BLOB substr preserves embedded NULs. A four-byte boundary probe keeps each
 * returned TEXT slice on UTF-8 boundaries, so the host performs decoding rather
 * than making the UI guest parse a doubled hex representation of every byte.
 * Callers guard their surrounding snapshot against concurrent library edits. */
export async function readSqlText(db:SqlQueryPort,select:string,params:SqlParams=[],options:SqlTextOptions={}):Promise<string|null>{
 const row=(await db.query(`SELECT typeof(value) AS kind,length(CAST(value AS BLOB)) AS bytes,
  CASE WHEN length(CAST(value AS BLOB))<=32768 THEN value ELSE NULL END AS small_value FROM (${select})`,params))[0];
 if(!row||row.kind==='null')return null;
 const size=Number(row.bytes);if(row.kind!=='text'||!Number.isSafeInteger(size)||size<0||size>(options.maxBytes??Number.MAX_SAFE_INTEGER))throw new Error(options.errors?.invalid??"Invalid or oversized SQL text");
 if(size<=32768){if(typeof row.small_value!=='string')throw new Error(options.errors?.changed??"SQL text changed during read");return row.small_value;}
 const parts:string[]=[];
 for(let offset=0;offset<size;){
  let count=Math.min(32768,size-offset);
  if(offset+count<size){
   const end=offset+count,probe=(await db.query(`SELECT hex(substr(CAST(value AS BLOB),?,4)) AS boundary FROM (${select})`,[end-2,...params]))[0];
   const hex=String(probe?.boundary??'');if(!/^[0-9A-Fa-f]{8}$/.test(hex))throw new Error(options.errors?.changed??"SQL text changed during read");
   const byte=(i:number)=>parseInt(hex.slice(i*2,i*2+2),16);
   if((byte(3)&0xc0)===0x80){let start=2;while(start>=0&&(byte(start)&0xc0)===0x80)start--;if(start<0)throw new Error(options.errors?.boundary??"Invalid UTF-8 boundary in SQL text");count-=3-start;}
  }
  const part=(await db.query(`SELECT CAST(substr(CAST(value AS BLOB),?,?) AS TEXT) AS value,length(substr(CAST(value AS BLOB),?,?)) AS bytes FROM (${select})`,[offset+1,count,offset+1,count,...params]))[0];
  if(typeof part?.value!=='string'||Number(part.bytes)!==count)throw new Error(options.errors?.changed??"SQL text changed during read");
  parts.push(part.value);offset+=count;
 }
 return parts.join('');
}
