import {expect,mock,test} from 'bun:test';
import {plugin} from 'bun';
import {createRequire} from 'node:module';
import * as solid from 'solid-js';
import {createComponent,createMemo,createRenderEffect,createRoot,createSignal,mergeProps,onCleanup} from 'solid-js';
import {createScrollerWith} from '../../../vendor/pocketjs/framework/src/kinetics-core.ts';
const require=createRequire(new URL('../../../vendor/pocketjs/package.json',import.meta.url)),{transformSync}=require('@babel/core');
plugin({name:'variable-list-solid',setup(b){b.onLoad({filter:/\/variable-list\.tsx$/},async a=>({contents:transformSync(await Bun.file(a.path).text(),{filename:a.path,babelrc:false,configFile:false,presets:[[require('babel-preset-solid'),{generate:'universal',moduleName:'@pocketjs/framework/renderer'}],[require('@babel/preset-typescript'),{isTSX:true,allExtensions:true}]]}).code,loader:'js'}));}});
let gestures:any[]=[],frames=new Set<()=>void>();
const element=(props:any)=>{const p=Object.create(props);Object.defineProperty(p,'children',{value:props.children,enumerable:true});const n={props:p};props.ref?.(n);return n;};
const base=import.meta.dir+'/../src';mock.module(Bun.resolveSync('solid-js',Bun.resolveSync('@pocketjs/framework/kinetics',base)),()=>solid);
for(const [name,impl] of Object.entries({renderer:{createComponent,mergeProps,memo:createMemo,effect:createRenderEffect},components:{View:element},gesture:{createGesture:(g:any)=>{gestures.push(g);onCleanup(()=>gestures=gestures.filter(x=>x!==g));}},lifecycle:{onFrame:(f:any)=>{frames.add(f);onCleanup(()=>frames.delete(f));}},kinetics:{createScroller:(o:any)=>createScrollerWith(createSignal,o),bindDpadScroll:()=>{}},input:{pressNode:()=>{},hitNode:()=>undefined}}))mock.module(Bun.resolveSync('@pocketjs/framework/'+name,base),()=>impl);
const {VariableList}=await import('../src/variable-list.tsx');
const {RouteStateProvider,restoredSignal,finiteState}=await import('../src/navigation.ts');
const frame=()=>{for(const f of [...frames])f();};
test('generic route owner resolves saved schemas and functional updates without route identifiers',()=>{
 const memory={saved:{count:4},retained:{}},calls:number[]=[],owner={memory:()=>memory,changed:()=>calls.push(1),checkpoints:new Set<()=>void>(),scrollSlots:new Set<number>()};let signal:any,dispose!:()=>void;
 createRoot(d=>{dispose=d;return createComponent(RouteStateProvider,{...owner,get children(){signal=restoredSignal('saved','count',0,finiteState);return null;}});});expect(signal[0]()).toBe(4);signal[1]((n:number)=>n+1);expect(memory.saved.count).toBe(5);expect(calls).toEqual([1]);dispose();
});
test('default list controller pulls to refresh and disposes registrations without restoration injection',()=>{
 let stop!:()=>void,scroller:any,refresh=0;createRoot(d=>{stop=d;return VariableList({height:100,entries:[{height:600,render:()=>null}],onRefresh:()=>refresh++,ref:v=>scroller=v.scroller});});
 const g=gestures[0];g.onDown();g.onPanStart();g.onPanMove({fdy:47});g.onPanEnd({vy:0});expect(refresh).toBe(0);for(let i=0;i<40;i++)frame();g.onDown();g.onPanStart();g.onPanMove({fdy:48});g.onPanEnd({vy:0});expect(refresh).toBe(1);stop();expect(frames.size).toBe(0);expect(gestures).toHaveLength(0);expect(scroller.state()).toBe('idle');
});
test('explicit memory adapter restores late rows and touch cancels the pending target',()=>{
 const memory:any={saved:{'scroll:0':300},retained:{}},owner={memory,checkpoints:new Set<()=>void>(),scrollSlots:new Set<number>(),changed:()=>{}},[entries,setEntries]=createSignal<any[]>([]);let stop!:()=>void,scroller:any;
 createRoot(d=>{stop=d;return VariableList({height:100,get entries(){return entries();},gap:0,padding:0,restorationOwner:()=>owner,ref:v=>scroller=v.scroller});});frame();setEntries([{height:200,render:()=>null}]);frame();expect(scroller.offset()).toBe(100);gestures[0].onDown();scroller.scrollTo(40,{immediate:true});setEntries([{height:900,render:()=>null}]);frame();expect(scroller.offset()).toBe(40);stop();expect(owner.checkpoints.size).toBe(0);expect(owner.scrollSlots.size).toBe(0);expect(memory.saved['scroll:0'].offset).toBe(40);
});
