/** Add a null-safe value-change guard to code-authored UPDATE/UPSERT statements.
 * SET expressions must be deterministic. Anonymous bindings are numbered once,
 * so the guard reuses their values without duplicating parameters. Not a SQL
 * sanitizer; identifiers/SQL must never come from an untrusted peer. */
export function onlyChangedSql(input:string):string {
 const table=/^\s*(?:UPDATE|INSERT(?:\s+OR\s+\w+)?\s+INTO)\s+([A-Za-z_]\w*)\b/i.exec(input)?.[1];
 if(!table||!/^\s*UPDATE\b/i.test(input)&&! /\bDO\s+UPDATE\s+SET\b/i.test(input))return input;
 let sql='',quote='',index=0;
 for(let i=0;i<input.length;i++){
  const c=input[i]!;if(quote){sql+=c;if(c===quote){if(input[i+1]===quote)sql+=input[++i];else quote='';}continue;}
  if(c==='\''||c==='"'||c==='`'){quote=c;sql+=c;continue;}
  if(c==='?'&&!/[0-9]/.test(input[i+1]??'')){sql+='?'+(++index);continue;}sql+=c;
 }
 const start=/\bSET\b/i.exec(sql)!.index+3;let depth=0,at=start,parts:string[]=[],where=-1;quote='';
 for(let i=start;i<sql.length;i++){
  const c=sql[i]!;if(quote){if(c===quote){if(sql[i+1]===quote)i++;else quote='';}continue;}
  if(c==='\''||c==='"'||c==='`'){quote=c;continue;}if(c==='(')depth++;else if(c===')')depth--;
  else if(depth===0&&/^\sWHERE\s/i.test(sql.slice(i))){where=i;parts.push(sql.slice(at,i));break;}
  else if(depth===0&&c===','){parts.push(sql.slice(at,i));at=i+1;}
 }
 if(where<0)parts.push(sql.slice(at));
 const predicates=parts.map(part=>{const m=/^\s*([A-Za-z_]\w*)\s*=\s*([\s\S]+?)\s*;?$/.exec(part);if(!m)throw Error('Unsupported SQL assignment');return `${table}.${m[1]} IS NOT (${m[2]})`;});
 const end=where<0?sql:sql.slice(0,where),old=where<0?'':sql.slice(where).replace(/^\s*WHERE\s+/i,'').replace(/;\s*$/,'');
 return end.replace(/;\s*$/,'')+' WHERE '+(old?'('+old+') AND ':'')+'('+predicates.join(' OR ')+')';
}
