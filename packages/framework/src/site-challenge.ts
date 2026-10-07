import {browserChallenge, HostServiceError, type HostOperation, type SiteChallengeOptions} from './services.ts';

/** Recognizes a script-cookie/reload challenge, never ordinary HTML or feed text. */
export function isScriptCookieChallenge(body:string):boolean {
  const head=body.slice(0,64*1024);
  return /^\s*(?:<!doctype\s+html\b|<html\b)/i.test(head)
    && /<script\b/i.test(head) && /(?:document\s*(?:\.\s*cookie|\[)|_wafchallengeid)/i.test(head)
    && /(?:reload\s*\(|location\s*(?:\.|\[)|_wafchallengeid)/i.test(head)
    && /(?:安全检测|security\s+(?:check|verification)|_wafchallengeid|js[-_ ]challenge)/i.test(head);
}
/** Interactive verification stays an explicit error; no browser solver is run. */
export function isInteractiveSiteChallenge(body:string):boolean {
  const head=body.slice(0,64*1024);
  return /^\s*(?:<!doctype\s+html\b|<html\b)/i.test(head)
    && /(?:sec_sdk_build\/[^"']*captcha|captcha\/index\.js|captcha_verify_container|g-recaptcha|h-captcha|cf-turnstile)/i.test(head);
}
export interface SiteChallengePorts {
  cookies(options:SiteChallengeOptions):HostOperation<{cookie:string;origin:string}>;
  acquire(options:SiteChallengeOptions):HostOperation<{cookie:string;origin:string}>;
}
/** Normal HTTP first, then at most one browser refresh and HTTP retry. The host
 * stores only browser-managed cookies and caches their matching user agent.
 * Callers keep their own response decoding, status checks and source errors. */
export function requestWithSiteChallenge<T>(
  options:SiteChallengeOptions,
  load:(cookie:string)=>HostOperation<T>,
  challenged:(response:T)=>boolean|"interactive",
  ports:SiteChallengePorts=browserChallenge,
):HostOperation<T> {
  let active:HostOperation<unknown>|undefined, cancelled=false, settled=false;
  let rejectCancel!:(error:Error)=>void;
  const cancellation=new Promise<never>((_,reject)=>rejectCancel=reject);
  const check=()=>{if(cancelled)throw new HostServiceError('cancelled','Operation cancelled');};
  const run=async<U>(operation:HostOperation<U>):Promise<U>=>{active=operation;check();try{return await operation.result;}finally{if(active===operation)active=undefined;} };
  const work=(async()=>{
    let cached='';
    try {cached=(await run(ports.cookies(options))).cookie;} catch(error) {
      check();
      // An older host must still be able to fetch a source that needs no challenge.
      if(!(error instanceof HostServiceError)||error.code!=='unsupported')throw error;
    }
    check();const first=await run(load(cached));check();const firstChallenge=challenged(first);if(!firstChallenge)return first;
    if(firstChallenge==='interactive')throw new HostServiceError('interaction_required','Site requires interactive verification');
    const refreshed=await run(ports.acquire(options));check();
    if(!refreshed.cookie)throw new HostServiceError('challenge_failed','Site challenge did not return a cookie');
    const second=await run(load(refreshed.cookie));check();
    const secondChallenge=challenged(second);
    if(secondChallenge==='interactive')throw new HostServiceError('interaction_required','Site requires interactive verification');
    if(secondChallenge)throw new HostServiceError('challenge_failed','Site still returned a challenge page');
    return second;
  })();
  const result=Promise.race([work,cancellation]).finally(()=>{settled=true;});
  return {result,cancel(){if(cancelled||settled)return;cancelled=true;rejectCancel(new HostServiceError('cancelled','Operation cancelled'));active?.cancel();}};
}
