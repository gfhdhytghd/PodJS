import {expect,mock,spyOn,test} from 'bun:test';
import {plugin} from 'bun';
import {createRequire} from 'node:module';
import * as solid from 'solid-js';
import {createComponent,createMemo,createRenderEffect,createRoot,createSignal,mergeProps,onCleanup} from 'solid-js';
const require=createRequire(new URL('../../../vendor/pocketjs/package.json',import.meta.url));
const {transformSync}=require('@babel/core');
plugin({name:'startup-overlay-solid',setup(b){b.onLoad({filter:/\/startup-overlay\.tsx$/},async args=>({contents:transformSync(await Bun.file(args.path).text(),{filename:args.path,babelrc:false,configFile:false,presets:[[require('babel-preset-solid'),{generate:'universal',moduleName:'@pocketjs/framework/renderer'}],[require('@babel/preset-typescript'),{isTSX:true,allExtensions:true}]]}).code,loader:'js'}));}});
mock.module(Bun.resolveSync('solid-js',import.meta.dir+'/../src'),()=>solid);
const frames=new Set<()=>void>();
const renderer={createComponent,mergeProps,memo:createMemo,effect:createRenderEffect};
const lifecycle={onFrame:(fn:()=>void)=>{frames.add(fn);onCleanup(()=>frames.delete(fn));}};
for(const [name,impl] of Object.entries({renderer,lifecycle}))for(const path of ['@pocketjs/framework/'+name,Bun.resolveSync('@pocketjs/framework/'+name,import.meta.dir+'/../src')])mock.module(path,()=>impl);
const {StartupOverlayTransition}=await import('../src/startup-overlay.tsx');
const settle=async()=>{await Promise.resolve();await Promise.resolve();};
function flatten(v:any):any[]{if(typeof v==='function')return flatten(v());if(Array.isArray(v))return v.flatMap(flatten);return v===undefined?[]:[v];}
test('production default clock/frame subscription mounts content independently and resets fade',async()=>{
 let time=10,dispose!:()=>void;const clock=spyOn(globalThis.performance,'now').mockImplementation(()=>time),calls:string[]=[];
 const [ready,setReady]=createSignal(false),[exit,setExit]=createSignal(false);
 try{
  const tree=createRoot(d=>{dispose=d;return createComponent(StartupOverlayTransition,{ready,exit,content:()=>{calls.push('content');onCleanup(()=>calls.push('content-cleanup'));return 'content';},overlay:(elapsed:()=>number)=>{calls.push('overlay');onCleanup(()=>calls.push('overlay-cleanup'));return elapsed as unknown as solid.JSX.Element;},onExitStart:()=>calls.push('start'),onFinished:()=>calls.push('finish')});});
  await settle();expect(flatten(tree)).toEqual([0]);expect(frames.size).toBe(1);
  setReady(true);await settle();expect(flatten(tree)).toEqual(['content',0]);setExit(true);await settle();
  time=160;for(const cb of frames)cb();expect(flatten(tree)).toEqual(['content',150]);
  time=5;for(const cb of frames)cb();expect(flatten(tree)).toEqual(['content',0]);
  setExit(false);await settle();time=300;setExit(true);await settle();time=600;for(const cb of frames)cb();await settle();expect(flatten(tree)).toEqual(['content']);
  for(const cb of frames)cb();expect(calls.filter(c=>c==='finish')).toHaveLength(1);
  dispose();expect(frames.size).toBe(0);expect(calls).toEqual(['overlay','content','start','start','finish','overlay-cleanup','content-cleanup']);
 }finally{dispose?.();clock.mockRestore();}
});
test('caller duration and clock, early unmount and readiness remount remain independent',async()=>{
 let time=0,dispose!:()=>void;const [ready,setReady]=createSignal(true),[exit,setExit]=createSignal(true);let mounts=0,completed=0;
 createRoot(d=>{dispose=d;return createComponent(StartupOverlayTransition,{ready,exit,content:()=>{mounts++;return 'body';},overlay:()=> 'cover',durationMs:5,clock:()=>time,onFinished:()=>completed++});});await settle();setReady(false);await settle();setReady(true);await settle();expect(mounts).toBe(2);time=4;for(const cb of frames)cb();expect(completed).toBe(0);dispose();expect(frames.size).toBe(0);
});
