/** Public, application-owned legacy scheduling identities. No data/credentials. */
export interface WorkManagerTakeoverConfig {
  nativeAppId: string;
  workerClasses: string[];
  uniqueWorkNames: string[];
  tags: string[];
}

export function validateWorkManagerTakeover(raw: unknown): WorkManagerTakeoverConfig {
  const invalid = (): never => { throw Error('android.workManagerTakeover contains invalid legacy identities'); };
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return invalid();
  const value = raw as Record<string, unknown>;
  if (Object.keys(value).some(key => !['nativeAppId', 'workerClasses', 'uniqueWorkNames', 'tags'].includes(key))) return invalid();
  const className = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)+$/;
  if (typeof value.nativeAppId !== 'string' || !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value.nativeAppId)) return invalid();
  function list(key: string, classes: boolean): string[] {
    const items = value[key];
    if (!Array.isArray(items) || items.length > 64 || new Set(items).size !== items.length) return invalid();
    if (items.some(item => typeof item !== 'string' || item.length > 256 ||
      !(classes ? className : /^[A-Za-z0-9_.:$-]{1,256}$/).test(item) ||
      item.startsWith('dev.podjs.') || item.startsWith('podjs'))) return invalid();
    return [...items];
  }
  const workerClasses = list('workerClasses', true);
  const uniqueWorkNames = list('uniqueWorkNames', false);
  const tags = list('tags', false);
  if (!workerClasses.length || !(uniqueWorkNames.length || tags.length)) return invalid();
  return {nativeAppId: value.nativeAppId, workerClasses, uniqueWorkNames, tags};
}
