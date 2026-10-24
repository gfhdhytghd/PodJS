import {writeFileSync,readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {quoteAndroidJsonString} from '../packages/framework/src/json-string';
import {encodeUtf8} from '../packages/framework/src/byte-codecs';
const [root,json]=process.argv.slice(2),fields=[{name:'first',column:'a',omitNull:true},{name:'second',column:'b'}];
const cases=[{a:null,b:''},{a:'<p>中文🙂 / slash\\ "\n</p>',b:'text '.repeat(1024)},{a:'\ud800 lone \udfff',b:'\0\b\f\t\r\n /'},{a:'a'.repeat(40000),b:'中文 /'.repeat(2000)}];
writeFileSync(root+'/input.json',JSON.stringify({fields,cases}));process.stdout.write(execFileSync('java',['-ea','-cp',root+':'+json,'dev.podjs.runtime.GzipJsonTest',root]));
const out=JSON.parse(readFileSync(root+'/output.json','utf8'));
for(let i=0;i<cases.length;i++){const c=cases[i],expected='{'+(c.a===null?'':'"first":'+quoteAndroidJsonString(c.a)+',')+'"second":'+quoteAndroidJsonString(c.b)+'}',hex=Buffer.from(encodeUtf8(expected,'question')).toString('hex');if(out[i].utf8Hex!==hex)throw Error('Native JSON byte differential '+i);if(out[i].chunkHashes.length!==Math.ceil(out[i].size/131072))throw Error('Chunk count');}
console.log('PASS: native Android JSON byte differential against production JS (including lone UTF16 and controls)');
