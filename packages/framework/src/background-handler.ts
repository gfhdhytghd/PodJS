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
export function defineBackgroundHandler(handler: BackgroundHandler): BackgroundHandler { return handler; }
