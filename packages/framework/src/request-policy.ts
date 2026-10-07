/** Return a rejection reply, or undefined to dispatch the request. */
export type RequestFilter<Request, Reply> = (request: Request) => Reply | undefined;
/** Snapshot the allowlist; protocol-specific names and replies belong to the app. */
export function createRequestAllowlist<Request, Reply>(key: (request: Request) => string, allowed: Iterable<string>, reject: (request: Request) => Reply): RequestFilter<Request, Reply> {
  const keys = new Set(allowed);
  return request => keys.has(key(request)) ? undefined : reject(request);
}
