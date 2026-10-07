/**
 * QuickJS compiles RegExp literals with the enclosing script. Unicode-property
 * patterns are disproportionately expensive there, so retain the exact pattern
 * semantics while compiling only when the owning feature first needs it.
 */
export function lazyRegExpTest(source:string,flags:string):(value:string)=>boolean {
 let pattern:RegExp|undefined;
 return value=>(pattern??=new RegExp(source,flags)).test(value);
}
