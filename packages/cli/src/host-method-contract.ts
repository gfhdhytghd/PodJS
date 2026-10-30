import ts from 'typescript';
/** Inspect request adapters without executing/importing an application's host. */
export function literalHostMethods(sources:readonly {path:string;text:string}[]):Set<string>{
 const methods=new Set<string>();
 const ports=new Map(['request','rawRequest','hostRequest','operation','hostOperation','host','raw','data','transform'].map(name=>[name,0]));ports.set('transcode',1);
 for(const {path,text}of sources){const source=ts.createSourceFile(path,text,ts.ScriptTarget.Latest,true);
  const visit=(node:ts.Node)=>{if(ts.isCallExpression(node)){
   const name=(ts.isIdentifier(node.expression)?node.expression.text:ts.isPropertyAccessExpression(node.expression)?node.expression.name.text:'').replace(/\d+$/,'');
   const index=ports.get(name),arg=index===undefined?undefined:node.arguments[index];
   if(arg&&(ts.isStringLiteral(arg)||ts.isNoSubstitutionTemplateLiteral(arg))&&/^[a-z][a-zA-Z0-9]*\.[a-zA-Z][a-zA-Z0-9]*$/.test(arg.text))methods.add(arg.text);
  }ts.forEachChild(node,visit);};visit(source);
 }return methods;
}
/** Deliberately fail if a closed Java literal set becomes computed or malformed. */
export function javaHostMethods(source:string):Set<string>{
 const body=/\bMETHODS\s*=\s*Collections\.unmodifiableSet\(new HashSet<>\(Arrays\.asList\(([\s\S]*?)\)\)\);/.exec(source)?.[1];
 if(body===undefined||body.replace(/"[a-zA-Z0-9.]+"/g,'').replace(/[\s,]/g,''))throw Error('Host METHODS must remain a closed literal set');
 const names=[...body.matchAll(/"([a-zA-Z0-9.]+)"/g)].map(v=>v[1]!);if(new Set(names).size!==names.length)throw Error('Duplicate host method');return new Set(names);
}
