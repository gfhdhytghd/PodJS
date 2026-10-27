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

/** Ascending positive integer key pagination. The caller supplies the key
 * predicate as the last placeholder (for example `id>? ORDER BY id`); no OFFSET
 * is evaluated. Surrounding revision guards still own snapshot consistency. */
export async function querySqlKeysetPages(db:SqlQueryPort,query:string,params:SqlParams=[],options:{key:string;after?:number;pageSize?:number}={key:'id'}):Promise<Record<string,unknown>[]>{
 let cursor=options.after??0,size=options.pageSize??400;
 if(!options.key||!Number.isSafeInteger(cursor)||cursor<0||!Number.isSafeInteger(size)||size<1||size>1000)throw Error('Invalid SQL keyset options');
 const result:Record<string,unknown>[]=[];
 for(;;){
  let rows:Record<string,unknown>[];
  try{rows=await db.query(query+' LIMIT ?',[...params,cursor,size]);}
  catch(error){if(size>1&&/query result too large/i.test(String(error))){size=Math.max(1,Math.floor(size/2));continue;}throw error;}
  if(rows.length>size)throw Error('SQL keyset page exceeds requested limit');
  let next=cursor;
  for(const row of rows){const key=Number(row[options.key]);if(!Number.isSafeInteger(key)||key<=next)throw Error('SQL keyset cursor did not advance');next=key;}
  result.push(...rows);cursor=next;if(rows.length<size)return result;
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

/** Equivalent to ordered PRAGMA table_info for exact sqlite_master names.
 * Table-valued PRAGMA batches metadata into bounded pages instead of one host
 * round trip per table. Older SQLite hosts retain the individual-PRAGMA path. */
export async function querySqlTableInfo(db:SqlQueryPort,names:readonly string[]):Promise<Map<string,Record<string,unknown>[]>>{
 const unique=[...new Set(names)],out=new Map(unique.map(name=>[name,[] as Record<string,unknown>[]]));
 try{
  for(let at=0;at<unique.length;at+=200){const page=unique.slice(at,at+200);
   const rows=await querySqlPages(db,"SELECT m.name AS table_name,p.* FROM sqlite_master m JOIN pragma_table_info(m.name) p WHERE m.type IN ('table','view') AND m.name IN ("+page.map(()=>'?').join(',')+") ORDER BY m.name,p.cid",page);
   for(const row of rows){const {table_name,...columns}=row;out.get(String(table_name))?.push(columns);}
  }return out;
 }catch(error){
  if(!/no such (?:table|function):\s*pragma_table_info|near ["']\(["']:\s*syntax error/i.test(String(error)))throw error;
  // Discard any partial batch before falling back; no failed query certifies metadata.
  out.clear();for(const name of unique)out.set(name,await db.query('PRAGMA table_info("'+name.replaceAll('"','""')+'")'));
  return out;
 }
}
