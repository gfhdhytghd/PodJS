/** Headless bundle entry contract. Host services are not implicitly available. */
export interface BackgroundContext {
  readonly appId: string;
  readonly taskId: string;
  /** Absolute Unix milliseconds for the native hard deadline, not a duration. */
  readonly deadlineMs: number;
  readonly payload: unknown;
  readonly isCancelled: () => boolean;
  /** Present only when the host granted asynchronous methods for this run. */
  readonly request?: (method: string, args: unknown) => Promise<unknown>;
}
export type BackgroundOutcome = "success" | "retry" | "failure";
export type BackgroundHandler = (context: BackgroundContext) => BackgroundOutcome | Promise<BackgroundOutcome>;
/** One invocation per native guest. Pure async codecs can share a caller-owned
 * scheduler without importing services, creating timers, or granting methods. */
export function defineBackgroundHandler(handler: BackgroundHandler,options?:{yieldTurn:(context:BackgroundContext)=>Promise<void>}): BackgroundHandler {
 if(!options)return handler;
 return async context=>{
  const global=globalThis as {__podYieldCodecTurn?:()=>Promise<void>};
  const owned=Object.prototype.hasOwnProperty.call(global,'__podYieldCodecTurn'),previous=global.__podYieldCodecTurn;
  global.__podYieldCodecTurn=()=>options.yieldTurn(context);
  try{return await handler(context);}finally{if(owned)global.__podYieldCodecTurn=previous;else delete global.__podYieldCodecTurn;}
 };
}
