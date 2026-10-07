import {expect,test} from 'bun:test';
import {requestWithSiteChallenge,isScriptCookieChallenge} from '../src/site-challenge.ts';
import {__pumpPodEvents} from '../src/watch.ts';
const commands:any[]=[],events:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['system.browser.challenge']),emit:(s:string)=>commands.push(JSON.parse(s)),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined};
const turn=async()=>{for(let i=0;i<15;i++)await Promise.resolve();};
const reply=async(value:any)=>{events.push({t:'service.result',id:commands.at(-1).id,ok:true,value});__pumpPodEvents();await turn();};
test('production default ports emit the native cookie lookup and acquisition services with exact UA and cookie names',async()=>{
  const options={url:'https://fixture.test/feed',userAgent:'Reader',cookieNames:['challenge_token']};const cookies:string[]=[];
  const operation=requestWithSiteChallenge(options,cookie=>{cookies.push(cookie);return {result:Promise.resolve(cookie?'<rss/>':'<!DOCTYPE html><html><script>document.cookie="x=y";location.reload()</script>Security check</html>'),cancel(){}};},isScriptCookieChallenge);
  await turn();expect(commands.at(-1)).toMatchObject({method:'browser.challengeCookies',args:options});await reply({cookie:'',origin:'https://fixture.test'});
  expect(commands.at(-1)).toMatchObject({method:'browser.acquireChallengeCookies',args:options});await reply({cookie:'challenge_token=valid',origin:'https://fixture.test'});
  expect(await operation.result).toBe('<rss/>');expect(cookies).toEqual(['','challenge_token=valid']);expect(commands).toHaveLength(2);
});
