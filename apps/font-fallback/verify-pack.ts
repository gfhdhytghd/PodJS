import { unpack } from "../../vendor/pocketjs/framework/compiler/pak.ts";
import { mkdirSync } from "node:fs";
const path=process.argv[2];
if(!path)throw new Error("Pass the built main.pak path");
const slots:Record<string,unknown>={};
for(const blob of unpack(new Uint8Array(await Bun.file(path).arrayBuffer()))) {
  if(!/^ui:font\./.test(blob.key))continue;
  const b=blob.data,d=new DataView(b.buffer,b.byteOffset,b.byteLength),version=d.getUint16(4,true);
  const cps=new Set<number>();const count=d.getUint16(6,true);
  const offset=version>=5?64:16,stride=version>=5?16:version===4?12:8;
  for(let i=0;i<count;i++)cps.add(d.getUint32(offset+i*stride,true));
  for(const cp of [0x6c17,0x2460,0x1f600,0x3400,0x20bb7,0x1f468,0x1f44d,0x2764]) {
    if(cps.has(cp))throw new Error(`Fixture accidentally baked U+${cp.toString(16)} in ${blob.key}`);
  }
  slots[b[12]]={w:b[8],h:b[9],baseline:b[10],lineHeight:b[11],density:b[14],bold:b[13]&1,px:d.getFloat64(48,true)};
}
mkdirSync(new URL("./androidTest/assets",import.meta.url),{recursive:true});
await Bun.write(new URL("./androidTest/assets/font-metrics.json",import.meta.url),JSON.stringify({slots},null,2)+"\n");
console.log("PASS: every sample is a runtime cmap miss; exported actual atlas metrics",slots);
