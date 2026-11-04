import {expect,mock,spyOn,test} from 'bun:test';
import {plugin} from 'bun';
import {createRequire} from 'node:module';
import * as solid from 'solid-js';
import {createComponent,createMemo,createRenderEffect,createRoot,createSignal,mergeProps,onCleanup} from 'solid-js';
import {createScrollerWith} from '../../../vendor/pocketjs/framework/src/kinetics-core.ts';
import {rectInsideCircle} from '../src/safe-qr-layout.ts';
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

const children=(node:any):any[]=>{const v=typeof node==='function'?node():node;if(Array.isArray(v))return v.flatMap(children);if(!v||!v.props)return [];return [v,...children(v.props.children)];};
test('real default round list renders safe row tops and extends the scroll range to the final card',()=>{
 let dispose!:()=>void,scroller:any;const tree=createRoot(d=>{dispose=d;return VariableList({height:233,round:{width:233,height:233},padding:18,gap:6,entries:[{height:48,render:()=>null},{height:70,render:()=>null},{height:48,render:()=>null}],ref:v=>scroller=v.scroller});});
 const nodes=children(tree),canvas=nodes[1],first=nodes[2];expect(first.props.style.insetT).toBeGreaterThan(18);expect(canvas.props.style.height).toBeGreaterThan(233);
 scroller.scrollTo(1e5,{immediate:true});const max=scroller.offset();expect(max).toBeCloseTo(canvas.props.style.height-233,8);
 const last=children(tree).at(-1)!;expect(rectInsideCircle({x:18,y:last.props.style.insetT-max,width:197,height:48},{cx:116.5,cy:116.5,radius:116.5},2)).toBe(true);
 dispose();
});
test('round list restores exact offset/anchor, touch/refresh and settled range retain row indices',()=>{
 const memory:any={saved:{},retained:{}},owner={memory,checkpoints:new Set<()=>void>(),scrollSlots:new Set<number>(),changed:()=>{}};
 const entries=Array.from({length:12},(_,key)=>({key,height:48,render:()=>null}));let dispose!:()=>void,scroller:any,refresh=0,ranges:number[][]=[];
 const mount=()=>createRoot(d=>{dispose=d;return VariableList({height:233,round:{width:233,height:233},entries,padding:18,gap:6,restorationOwner:()=>owner,onRefresh:()=>refresh++,onSettledRange:(a,b)=>ranges.push([a,b]),ref:v=>scroller=v.scroller});});
 mount();scroller.scrollTo(140,{immediate:true});frame();dispose();expect(memory.saved['scroll:0'].offset).toBe(140);const saved=structuredClone(memory.saved['scroll:0']);
 mount();frame();expect(scroller.offset()).toBe(140);expect(memory.saved['scroll:0']).toEqual(saved);expect(ranges.at(-1)![0]).toBe(1);
 scroller.scrollTo(0,{immediate:true});const g=gestures[0];g.onDown();g.onPanStart();g.onPanMove({fdy:48});g.onPanEnd({vy:0});expect(refresh).toBe(1);dispose();expect(frames.size).toBe(0);
});
test('indexed round padding preserves keyed owners through layout revisions',()=>{
 let dispose!:()=>void,mounts=0,releases=0;const key={},scope={},render=()=>{mounts++;onCleanup(()=>releases++);return null;},[body,setBody]=createSignal([{key,top:0,height:48,render}]);
 const tree=createRoot(d=>{dispose=d;return VariableList({height:233,round:{width:233,height:233},entries:[],get indexedEntries(){return {entries:body(),indices:new Map([[key,0]]),scope};},padding:18,gap:0});});
 expect(children(tree)[2].props.style.insetT).toBeGreaterThan(18);for(let i=0;i<5;i++)setBody([{key,top:0,height:48+i,render}]);expect(mounts).toBe(1);expect(releases).toBe(0);dispose();expect(releases).toBe(1);
});
test('unkeyed round rows keep their owners while scrolling within the same visible window',()=>{
 let dispose!:()=>void,scroller:any,mounts=0,releases=0;const entries=Array.from({length:10},()=>({height:48,render:()=>{mounts++;onCleanup(()=>releases++);return null;}}));
 createRoot(d=>{dispose=d;return VariableList({height:233,round:{width:233,height:233},entries,padding:18,gap:6,ref:v=>scroller=v.scroller});});
 const before=mounts;scroller.scrollTo(5,{immediate:true});frame();expect(mounts).toBe(before);expect(releases).toBe(0);dispose();expect(releases).toBe(mounts);
});
test('round diagnostics require development opt-in and fixed/non-round geometry stays unchanged',()=>{
 const warn=spyOn(console,'warn').mockImplementation(()=>{});
 try {for(const round of [undefined,null,{width:233,height:233},{width:233,height:233,development:true}]){
  let dispose!:()=>void;const tree=createRoot(d=>{dispose=d;return VariableList({height:233,entries:[{height:180,render:()=>null}],padding:18,round});});
  expect(children(tree)[2].props.style.insetT).toBe(18);dispose();
 }expect(warn).toHaveBeenCalledTimes(1);
 let dispose!:()=>void;const tree=createRoot(d=>{dispose=d;return VariableList({height:233,entries:[{height:48,render:()=>null}],padding:18,round:{width:233,height:233},fixed:true});});expect(children(tree)[2].props.style.insetT).toBe(18);expect(children(tree)[1].props.style.height).toBe(84);dispose();
 }finally{warn.mockRestore();}
});

test('production round list places different-width endpoint rows safely and keeps declared hit targets on screen',()=>{
 let stop!:()=>void,scroller:any,handle:any;
 const entries=[
  {height:48,visibleBounds:{x:58.5,y:14,width:80,height:20},interactionBounds:{x:-18,y:0,width:233,height:48},render:()=>null},
  ...Array.from({length:12},()=>({height:48,bounds:{x:28.5,y:0,width:140,height:48},render:()=>null})),
  {height:48,visibleBounds:{x:0,y:0,width:197,height:48},interactionBounds:{x:0,y:0,width:197,height:48},render:()=>null},
 ];
 const tree=createRoot(d=>{stop=d;return VariableList({height:233,round:{width:233,height:233},padding:18,gap:6,entries,ref:v=>{handle=v;scroller=v.scroller;}});});
 const rowNodes=()=>children(tree).filter(n=>n.props.style?.insetR===18);
 const first=rowNodes()[0].props.style;
 expect(rectInsideCircle({x:76.5,y:first.insetT+14,width:80,height:20},{cx:116.5,cy:116.5,radius:116.5},2)).toBe(true);
 expect(first.insetT).toBeGreaterThanOrEqual(0);expect(first.insetT+48).toBeLessThanOrEqual(233);
 expect(handle.interactionBounds(0)).toEqual({x:0,y:first.insetT,width:233,height:48});
 scroller.scrollTo(1e6,{immediate:true});
 const last=rowNodes().at(-1)!.props.style,y=last.insetT-scroller.offset();
 expect(rectInsideCircle({x:18,y,width:197,height:48},{cx:116.5,cy:116.5,radius:116.5},2)).toBe(true);
 expect(handle.interactionBounds(0)).toBeUndefined();
 expect(handle.interactionBounds(entries.length-1)).toEqual({x:18,y,width:197,height:48});
 stop();
});


test('keyed row equality keeps the mounted text owner across equivalent layout revisions',()=>{
 let dispose!:()=>void,handle:any,created=0;const seen:unknown[]=[];
 const [body,setBody]=createSignal({entries:[{key:'line',top:0,height:40,data:{text:'same'},render:(current:any)=>{created++;createRenderEffect(()=>seen.push(current().data));return null;}}],indices:new Map([['line',0]]),scope:'doc'});
 createRoot(stop=>{dispose=stop;return VariableList({height:100,entries:[],get indexedEntries(){return body();},entryEquals:(a,b)=>a.top===b.top&&a.height===b.height&&(a.data as any).text===(b.data as any).text,ref:v=>handle=v});});
 expect(created).toBe(1);
 const revision=(height:number)=>({entries:[{key:'line',top:0,height,data:{text:'same'},render:()=>{created++;return null;}}],indices:new Map([['line',0]]),scope:'doc'});
 setBody(revision(40));expect(created).toBe(1);expect(seen).toHaveLength(1);
 setBody(revision(60));expect(created).toBe(1);expect(seen).toHaveLength(2);expect(handle.scroller.offset()).toBe(0);
 dispose();
});


test('a window row can own its press action without another native wrapper',()=>{
 let dispose!:()=>void,pressed=0;
 const tree=createRoot(stop=>{dispose=stop;return VariableList({height:100,padding:18,entries:[{height:40,onPress:()=>pressed++,render:()=>null}]});});
 const entry=children(tree).find(n=>n.props.style?.insetR===18)!;expect(entry).toBeDefined();entry.props.onPress();expect(pressed).toBe(1);dispose();
});
