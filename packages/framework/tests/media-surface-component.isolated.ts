import {expect,mock,test} from 'bun:test';
import {plugin} from 'bun';
import {createRequire} from 'node:module';
import {createRoot,createSignal,createComponent,createMemo,createRenderEffect,mergeProps} from 'solid-js';
import type {MediaSurfaceStyle} from '../src/services.ts';

const require=createRequire(new URL('../../../vendor/pocketjs/package.json',import.meta.url));
const {transformSync}=require('@babel/core');
plugin({name:'media-surface-solid-test',setup(builder){
 builder.onLoad({filter:/\/media-surface\.tsx$/},async args=>({contents:transformSync(await Bun.file(args.path).text(),{
  filename:args.path,babelrc:false,configFile:false,
  presets:[[require('babel-preset-solid'),{generate:'universal',moduleName:'@pocketjs/framework/renderer'}],[require('@babel/preset-typescript'),{isTSX:true,allExtensions:true}]],
 }).code,loader:'js'}));
}});
const events:any[]=[],emitted:any[]=[],images:any[]=[],pending=new Map<number,any>();
(globalThis as any).pod={takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>{const value=JSON.parse(line);emitted.push(value);pending.set(value.id,value);},capabilities:()=>JSON.stringify(['data.images','media.video'])};
mock.module('@pocketjs/framework/renderer',()=>({createComponent,mergeProps,memo:createMemo,effect:createRenderEffect}));
mock.module('@pocketjs/framework/components',()=>({
 View:(props:any)=>({props,children:props.children}),
 Image:(props:any)=>{props.ref?.({id:77});return {props};},
}));
mock.module('@pocketjs/framework/host',()=>({getOps:()=>({setImage(...args:any[]){images.push(args);}})}));
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {MediaSurface,mediaSurfaceOwners}=await import('../src/media-surface.tsx');
const tick=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};
const reply=async(operation:any,value:any)=>{pending.delete(operation.id);events.push({t:'service.result',id:operation.id,ok:true,value});__pumpPodEvents();await tick();};
const opens=()=>emitted.filter(value=>value.method==='image.surface.open');
const closes=(id:string)=>emitted.filter(value=>value.method==='image.surface.close'&&value.args.id===id);
async function drain(){
 for(const operation of [...pending.values()]){
  expect(operation.method).not.toBe('image.surface.open');
  await reply(operation,operation.method.endsWith('.close')?{closed:true}:{texture:8,width:64,height:64,state:'ready',textureSampling:'linear'});
 }
}

test('sampling changes reopen the actual Solid component while appearance changes retain its owner',async()=>{
 const [sampling,setSampling]=createSignal<'nearest'|'linear'|undefined>();
 const [style,setStyle]=createSignal({brightness:1});
 const states:any[]=[],errors:unknown[]=[];
 let dispose!:()=>void;
 createRoot(stop=>{dispose=stop;return MediaSurface({kind:'image',path:'local.png',widthPx:48,heightPx:48,pixelScale:2,
  get textureSampling(){return sampling();},get style(){return style();},onState:value=>states.push(value),onError:error=>errors.push(error),
 });});
 try{
  await tick();expect(opens()).toHaveLength(1);const initial=opens()[0];expect(initial.args).not.toHaveProperty('textureSampling');
  setSampling('linear');await tick();expect(opens()).toHaveLength(2);const linear=opens()[1];
  expect(linear.args.textureSampling).toBe('linear');expect(linear.args.id).not.toBe(initial.args.id);expect(closes(initial.args.id)).toHaveLength(1);
  expect(emitted.indexOf(closes(initial.args.id)[0])).toBeLessThan(emitted.indexOf(linear));
  await reply(initial,{texture:7,width:64,height:64,state:'ready'});expect(images).not.toContainEqual([77,7]);expect(states).toEqual([]);
  await reply(linear,{texture:8,width:64,height:64,state:'ready',textureSampling:'linear'});await drain();expect(images).toContainEqual([77,8]);
  setStyle({brightness:.8});await tick();expect(opens()).toHaveLength(2);expect(closes(linear.args.id)).toHaveLength(0);
  const update=[...pending.values()].find(value=>value.method==='image.surface.update');expect(update.args).toEqual({id:linear.args.id,brightness:.8});await drain();
  setSampling('nearest');await tick();expect(opens()).toHaveLength(3);const nearest=opens()[2];expect(nearest.args.textureSampling).toBe('nearest');expect(closes(linear.args.id)).toHaveLength(1);
  await reply(nearest,{texture:9,width:64,height:64,state:'ready'});await drain();expect(images).toContainEqual([77,9]);expect(errors).toEqual([]);
  dispose();await drain();expect(mediaSurfaceOwners()).toEqual([]);expect(closes(nearest.args.id)).toHaveLength(1);
  setSampling('linear');await tick();expect(opens()).toHaveLength(3);
 }finally{dispose();await drain();}
});

const presentationFrame={leftPx:-3.5,topPx:28.25,widthPx:240,heightPx:222,scale:1.16,opacity:.12};
const viewportState={texture:12,width:512,height:512,state:'ready',textureSampling:'linear'};
async function drainPresentation(){
 for(const operation of [...pending.values()]){
  expect(operation.method).not.toBe('image.surface.open');
  await reply(operation,operation.method.endsWith('.close')?{closed:true}:{...viewportState,presentationFrame:operation.args.presentationFrame??null});
 }
}

test('presentation updates retain the component owner and an update mismatch restores fallback',async()=>{
 const [style,setStyle]=createSignal<MediaSurfaceStyle>({fit:'fit',presentationFrame});
 const states:any[]=[],errors:unknown[]=[],count=opens().length;
 let dispose!:()=>void,tree:any;
 createRoot(stop=>{dispose=stop;tree=MediaSurface({kind:'image',path:'local.png',widthPx:466,heightPx:466,pixelScale:2,textureSampling:'linear',
  get style(){return style();},fallback:'Preparing',onState:value=>states.push(value),onError:error=>errors.push(error),
 });});
 try{
  await tick();const open=opens()[count];expect(opens()).toHaveLength(count+1);expect(open.args.presentationFrame).toEqual(presentationFrame);
  const [fallback,picture]=tree.children;expect(fallback.props.style.opacity).toBe(1);expect(picture.props.style.opacity).toBe(0);
  expect(tree.props.style).toEqual({width:233,height:233});
  await reply(open,{...viewportState,presentationFrame});await drainPresentation();
  expect(fallback.props.style.opacity).toBe(0);expect(picture.props.style).toMatchObject({width:256,height:256,opacity:1});
  const next={...presentationFrame,leftPx:114,topPx:211,scale:1.0131678581237793,opacity:1};
  setStyle({fit:'fit',presentationFrame:next});await tick();
  expect(opens()).toHaveLength(count+1);expect(closes(open.args.id)).toHaveLength(0);
  const update=[...pending.values()].find(value=>value.method==='image.surface.update');
  expect(update.args).toEqual({id:open.args.id,fit:'fit',presentationFrame:next});
  const published=states.length;await reply(update,{...viewportState,presentationFrame});
  expect(states).toHaveLength(published);expect(errors).toHaveLength(1);expect(String(errors[0])).toMatch(/presentation frame/i);
  expect(fallback.props.style.opacity).toBe(1);expect(picture.props.style.opacity).toBe(0);expect(images.at(-1)).toEqual([77,-1]);
  expect(closes(open.args.id)).toHaveLength(1);expect(mediaSurfaceOwners()).not.toContain(open.args.id);
  setStyle({fit:'fit'});await tick();expect(opens()).toHaveLength(count+1);await drainPresentation();
 }finally{dispose();await drainPresentation();}
});

test('an unsupported initial presentation leaves component fallback visible without attaching a texture',async()=>{
 const errors:unknown[]=[],states:any[]=[],count=opens().length,imageStart=images.length;
 let dispose!:()=>void,tree:any;
 createRoot(stop=>{dispose=stop;tree=MediaSurface({kind:'image',path:'local.png',widthPx:466,heightPx:466,pixelScale:2,
  style:{presentationFrame},fallback:'Preparing',onState:value=>states.push(value),onError:error=>errors.push(error),
 });});
 try{
  await tick();const open=opens()[count];await reply(open,viewportState);
  const [fallback,picture]=tree.children;expect(fallback.props.style.opacity).toBe(1);expect(picture.props.style.opacity).toBe(0);
  expect(states).toEqual([]);expect(errors).toHaveLength(1);expect(images.slice(imageStart).some(row=>row[1]>=0)).toBe(false);
  expect(closes(open.args.id)).toHaveLength(1);expect(mediaSurfaceOwners()).not.toContain(open.args.id);await drainPresentation();
 }finally{dispose();await drainPresentation();}
});
