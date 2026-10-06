import {systemTextMemoryStats} from "./text.tsx";
import {serviceResourceCounts} from "./services.ts";
import {watchResourceCounts} from "./watch.ts";
import * as renderer from "@pocketjs/framework/renderer";
// Older PocketJS runtimes have no registry diagnostics. Leave them absent
// instead of loading a second renderer/registry or inventing zero counts.
const registry=renderer as unknown as {nativeTreeResourceCounts?:()=>unknown;frameResourceCounts?:()=>unknown};
export {debugMemoryStats} from "./services.ts";

/** Install only after application.buildInfo().debugBuild is true. This adds
 * no frame hook, timer or retained samples. Native reads it between turns. */
export function installDebugMemoryStats():void {
 (globalThis as {__podMemoryStats?:()=>string}).__podMemoryStats=()=>JSON.stringify({
  systemText:systemTextMemoryStats(),services:serviceResourceCounts(),watch:watchResourceCounts(),
  tree:registry.nativeTreeResourceCounts?.()??null,frame:registry.frameResourceCounts?.()??null,
 });
}
