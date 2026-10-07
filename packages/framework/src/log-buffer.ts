export interface LineBufferOptions {maxLines:number;maxChars:number;maxLineChars:number}
/** Counts UTF-16 units, not bytes. Caller must redact before append. */
export class BoundedLineBuffer {
 private lines:string[]=[];
 private chars=0;
 constructor(private readonly options:LineBufferOptions){
  if(!Number.isSafeInteger(options.maxLines)||options.maxLines<0||!Number.isSafeInteger(options.maxChars)||options.maxChars<0||!Number.isSafeInteger(options.maxLineChars)||options.maxLineChars<1)throw Error('Invalid line buffer limits');
 }
 append(line:string){const value=line.slice(-this.options.maxLineChars);this.lines.push(value);this.chars+=value.length;while(this.chars>this.options.maxChars||this.lines.length>this.options.maxLines)this.chars-=this.lines.shift()!.length;}
 get size(){return this.lines.length;}
 dump(){return this.lines.join('\n');}
 clear(){this.lines=[];this.chars=0;}
}
