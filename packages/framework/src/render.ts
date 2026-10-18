import {hostOperation,type HostOperation} from './services.ts';
/** Yield expensive guest work until two window frame commits, or a bounded
 * fallback. `committed` is not a physical panel presentation fence. */
export function afterPaint():HostOperation<{committed:boolean}>{
 return hostOperation('runtime.afterPaint',{});
}
