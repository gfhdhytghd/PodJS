import {expect,test} from 'bun:test';
import {isScriptCookieChallenge,isInteractiveSiteChallenge,requestWithSiteChallenge,type SiteChallengePorts} from '../src/site-challenge.ts';
import {HostServiceError,type HostOperation} from '../src/services.ts';
const html='<!DOCTYPE html><html><script>document.cookie="fixture_gate=1";location.reload()</script><p>Security check</p></html>';
const op=<T>(value:T):HostOperation<T>=>({result:Promise.resolve(value),cancel(){}});
const options={url:'https://fixture.test/feed',userAgent:'Fixture Reader',cookieNames:['fixture_token']};

test('recognizes script-cookie challenge without confusing HTML articles inside RSS or ordinary HTML',()=>{
  expect(isScriptCookieChallenge(html)).toBe(true);
  expect(isScriptCookieChallenge('<!DOCTYPE html><html><script>document[x]="_wafchallengeid=";window[y]["reload"]()</script>安全检测</html>')).toBe(true);
  for(const body of ['<rss><channel><item><description><![CDATA['+html+']]></description></item></channel></rss>','<!DOCTYPE html><html><p>ordinary article</p></html>','<!DOCTYPE html><html><div>CAPTCHA</div></html>','<rss/>'])expect(isScriptCookieChallenge(body)).toBe(false);
});
test('local fake server: HTML -> browser cookie -> RSS; cached cookie, expired cookie and retry limit',async()=>{
  let token='one',acquires=0,loads=0;const calls:string[]=[];let cache='';
  const server=Bun.serve({port:0,fetch(request){
    calls.push(new URL(request.url).pathname);
    if(new URL(request.url).pathname==='/grant')return new Response('browser fixture',{headers:{'set-cookie':`fixture_token=${token}; Path=/; HttpOnly`}});
    loads++;return new Response(request.headers.get('cookie')===`fixture_token=${token}`?'<rss><channel><title>Fixture</title></channel></rss>':html);
  }});
  const opts={...options,url:server.url+'feed'};
  const ports:SiteChallengePorts={cookies:()=>op({cookie:cache,origin:server.url.origin}),acquire:()=>({result:(async()=>{acquires++;const response=await fetch(server.url+'grant');cache=response.headers.get('set-cookie')!.split(';')[0];return {cookie:cache,origin:server.url.origin};})(),cancel(){}})};
  const load=(cookie:string):HostOperation<string>=>({result:fetch(opts.url,{headers:{cookie}}).then(r=>r.text()),cancel(){}});
  try {
    expect(await requestWithSiteChallenge(opts,load,isScriptCookieChallenge,ports).result).toContain('<rss>');expect(acquires).toBe(1);expect(loads).toBe(2);expect(calls).toEqual(['/feed','/grant','/feed']);
    expect(await requestWithSiteChallenge(opts,load,isScriptCookieChallenge,ports).result).toContain('<rss>');expect(acquires).toBe(1);expect(loads).toBe(3);
    token='two';expect(await requestWithSiteChallenge(opts,load,isScriptCookieChallenge,ports).result).toContain('<rss>');expect(acquires).toBe(2);expect(loads).toBe(5);
    await expect(requestWithSiteChallenge(opts,()=>op(html),isScriptCookieChallenge,ports).result).rejects.toMatchObject({code:'challenge_failed'});expect(acquires).toBe(3);
  }finally{server.stop(true);}
});
test('ordinary source errors and unsupported older hosts preserve plain HTTP success',async()=>{
  let acquires=0;const error=new Error('original HTTP failure');
  const ports:SiteChallengePorts={cookies:()=>({result:Promise.reject(new HostServiceError('unsupported','old host')),cancel(){}}),acquire:()=>{acquires++;return op({cookie:'token',origin:'https://fixture.test'});}};
  expect(await requestWithSiteChallenge(options,()=>op('<rss/>'),isScriptCookieChallenge,ports).result).toBe('<rss/>');
  await expect(requestWithSiteChallenge(options,()=>({result:Promise.reject(error),cancel(){}}),isScriptCookieChallenge,ports).result).rejects.toBe(error);expect(acquires).toBe(0);
  const timeout=new HostServiceError('challenge_timeout','timeout');
  await expect(requestWithSiteChallenge(options,()=>op(html),isScriptCookieChallenge,{cookies:()=>op({cookie:'',origin:'https://fixture.test'}),acquire:()=>({result:Promise.reject(timeout),cancel(){}})}).result).rejects.toBe(timeout);
});
test('cancellation during cached lookup, HTTP and challenge settles once and stops further stages',async()=>{
  for(const phase of ['cookies','load','acquire']){
    let cancelCount=0,acquires=0,loads=0;let unblock!:(value:any)=>void;
    const blocked=():HostOperation<any>=>({result:new Promise(resolve=>unblock=resolve),cancel(){cancelCount++;}});
    const ports:SiteChallengePorts={cookies:()=>phase==='cookies'?blocked():op({cookie:'',origin:'https://fixture.test'}),acquire:()=>{acquires++;return blocked();}};
    const request=requestWithSiteChallenge(options,()=>{loads++;return phase==='load'?blocked():op(html);},isScriptCookieChallenge,ports);
    for(let i=0;i<12;i++)await Promise.resolve();
    const result=request.result;request.cancel();request.cancel();await expect(result).rejects.toMatchObject({code:'cancelled'});expect(cancelCount).toBe(1);
    unblock(phase==='load'?html:{cookie:'late',origin:'https://fixture.test'});for(let i=0;i<12;i++)await Promise.resolve();
    expect(loads).toBe(phase==='cookies'?0:1);expect(acquires).toBe(phase==='acquire'?1:0);
  }
});

test('interactive CAPTCHA is detected as HTML and never starts cookie acquisition',async()=>{
  const captcha='<html><head><script src="https://cdn.test/sec_sdk_build/3.3.4/captcha/index.js"></script></head><body></body></html>';
  expect(isInteractiveSiteChallenge(captcha)).toBe(true);expect(isInteractiveSiteChallenge('<rss><description>'+captcha+'</description></rss>')).toBe(false);
  let acquisitions=0;
  await expect(requestWithSiteChallenge(options,()=>op(captcha),body=>isInteractiveSiteChallenge(body)?'interactive':isScriptCookieChallenge(body),{cookies:()=>op({cookie:'',origin:'https://fixture.test'}),acquire:()=>{acquisitions++;return op({cookie:'fake',origin:'https://fixture.test'});}}).result).rejects.toMatchObject({code:'interaction_required'});
  expect(acquisitions).toBe(0);
});
