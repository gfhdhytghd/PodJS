import {stringToUtf8} from './bytes.ts';
export type LogLevel='V'|'D'|'I'|'W'|'E';
/** Native header/Bearer/cookie rules, extended to JSON credentials and all URL queries. */
export function createLogRedactor(options:{sensitivePattern:string;headerPattern?:string}):(raw:string)=>string {
 const sensitive=options.sensitivePattern;
 const headers=new RegExp(`\\b(${options.headerPattern??'(?:set-)?cookie|authorization|proxy-authorization'})["']?\\s*([:=])\\s*[^\\r\\n]*`,'gi');
 return (raw:string):string => {
 return raw
  .replace(headers,'$1$2<redacted>')
  .replace(/\b(Bearer\s+)[A-Za-z0-9._~+/\-=]+/gi,'$1<redacted>')
  .replace(/\b[a-z][a-z0-9+.-]*:\/\/(?:<redacted>|[^\s<>"'])+/gi,url=>url.replace(/\/\/[^/@]+@/,'//<redacted>@').replace(/[?#].*$/,'?<redacted>'))
  .replace(/(\/(?:<redacted>|[^\s<>"'?])+)[?](?:<redacted>|[^\s<>"'])*/g,'$1?<redacted>')
  .replace(new RegExp(`\\b(${sensitive})["']?\\s*[:=]\\s*(?:"[^"\\r\\n]*"|'[^'\\r\\n]*'|[^;\\s,\\]\\)}]+)`,'gi'),'$1=<redacted>');
};
}
export interface FileLoggerOptions {
 redact:(raw:string)=>string;
 maxBytes:number;
 trim:(lines:string[])=>string;
 writeFailureMessage:string;
}
export interface LogFile {
 read():Promise<string|null>;
 size():Promise<number>;
 replace(text:string):Promise<void>;
 append(text:string,offset:number):Promise<void>;
}
function timestamp(d:Date):string {
 const p=(n:number,width=2)=>String(n).padStart(width,'0');
 return `${p(d.getFullYear(),4)}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(),3)}`;
}
/** A single FIFO owns every read/write/trim. Logging never rejects into application code. */
export class FileLogger {
 private queue:Promise<unknown>=Promise.resolve();
 constructor(private readonly file:LogFile,private readonly options:FileLoggerOptions,private readonly now=()=>new Date(),private readonly output:(level:LogLevel,line:string)=>void=()=>{}){}
 private serial<T>(work:()=>Promise<T>):Promise<T>{const next=this.queue.then(work);this.queue=next.catch(()=>{});return next;}
 /** `scanExisting=false` skips the one-time full-file redaction of logs written by older builds
  * (every new line is already redacted in write()). */
 init(legacyTimeline?:string,scanExisting=true):Promise<void>{
  const ready=this.serial(async()=>{const raw=scanExisting?await this.file.read():(legacyTimeline&&await this.file.size()>0?'present':null);if(raw){if(scanExisting){const safe=this.options.redact(raw);if(safe!==raw)await this.file.replace(safe);}}else if(legacyTimeline){await this.file.replace(this.options.redact(legacyTimeline));}});
  return ready;
 }
 log(tag:string,message:string){this.d(tag,message);}
 v(tag:string,message:string){this.write('V',tag,message);}
 d(tag:string,message:string){this.write('D',tag,message);}
 i(tag:string,message:string){this.write('I',tag,message);}
 w(tag:string,message:string,error?:unknown){this.write('W',tag,message,error);}
 e(tag:string,message:string,error?:unknown){this.write('E',tag,message,error);}
 private write(level:LogLevel,tag:string,message:string,error?:unknown){
  try {
   const stack=error==null?'':error instanceof Error?error.stack||String(error):String(error);
   const line=this.options.redact(`[${timestamp(this.now())}] [${level}] [${tag}] ${message}${stack?'\n'+stack:''}`);
   try{this.output(level,line);}catch{}
   void this.serial(async()=>{
    let size=await this.file.size();
    // Trim once BEFORE append, only when strictly over the configured byte limit.
    if(size>this.options.maxBytes){
     const lines=(await this.file.read()??'').split(/\r\n|\n|\r/);if(lines.at(-1)==='')lines.pop();
     const text=this.options.trim(lines);
     await this.file.replace(text);size=stringToUtf8(text).length;
    }
    await this.file.append(line+'\n',size);
   }).catch(()=>{try{this.output('E',this.options.writeFailureMessage);}catch{}});
  }catch{} // Formatting or console failures must not change application behavior.
 }
 readLogs():Promise<string|null>{return this.serial(async()=>{const raw=await this.file.read();return raw?this.options.redact(raw):null;});}
 clearLogs():Promise<void>{return this.serial(async()=>{await this.file.replace('');});}
 flush():Promise<void>{return this.serial(async()=>{});}
}
