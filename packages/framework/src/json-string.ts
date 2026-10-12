import {encodeUtf8} from './byte-codecs.ts';
/** JSONObject.quote-compatible escaping, including literal lone UTF-16 surrogates. */
export function quoteAndroidJsonString(value:string):string {
 // The engine handles large well-formed bodies in native code. JSON.stringify
 // escapes lone surrogates, unlike JSONObject; preserve the old dialect there.
 if(!/[\ud800-\udbff](?![\udc00-\udfff])|(^|[^\ud800-\udbff])[\udc00-\udfff]/.test(value))return JSON.stringify(value).replace(/\//g,'\\/');
 const pieces:string[]=['"'];let start=0;
 const escapes:Record<string,string>={'"':'\\"','\\':'\\\\','/':'\\/','\b':'\\b','\f':'\\f','\n':'\\n','\r':'\\r','\t':'\\t'};
 for(let i=0;i<value.length;i++){
  const c=value[i]!,escaped=escapes[c]??(c.charCodeAt(0)<32?`\\u00${c.charCodeAt(0).toString(16).padStart(2,"0")}`:undefined);
  if(escaped!==undefined){pieces.push(value.slice(start,i),escaped);start=i+1;}
 }
 pieces.push(value.slice(start),'"');return pieces.join('');
}

/** Exact quoted Java UTF-8 byte size; native string/regex paths avoid JS scans of Base64. */
export function androidJsonQuotedByteLength(value:string):number {
 const native=(globalThis as {pod?:{androidJsonQuotedByteLength?:(value:string)=>number|null|undefined}}).pod?.androidJsonQuotedByteLength;
 if(native){let size=2;
  for(let start=0;start<value.length;){let end=Math.min(value.length,start+32768);
   if(end<value.length&&value.charCodeAt(end-1)>=0xd800&&value.charCodeAt(end-1)<=0xdbff&&value.charCodeAt(end)>=0xdc00&&value.charCodeAt(end)<=0xdfff)end--;
   const part=native(value.slice(start,end));if(part==null)break;
   if(!Number.isSafeInteger(part)||part<2)throw Error('Invalid native JSON quoted byte length');
   size+=part-2;start=end;if(start===value.length)return size;
  }if(!value.length)return 2;
 }
 const quoted=quoteAndroidJsonString(value);
 return /^[\x00-\x7f]*$/.test(quoted)?quoted.length:encodeUtf8(quoted,'question').length;
}
