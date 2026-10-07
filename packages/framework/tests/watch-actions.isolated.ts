import {afterEach, expect, mock, spyOn, test} from "bun:test";
import {plugin} from "bun";
import {createRequire} from "node:module";
import {dirname} from "node:path";
import {realpathSync} from "node:fs";
import * as solid from "solid-js";
import {createComponent, createMemo, createRenderEffect, createRoot, createSignal, mergeProps, onCleanup} from "solid-js";
const require=createRequire(new URL("../../../vendor/pocketjs/package.json", import.meta.url));
const {transformSync}=require("@babel/core");
plugin({name:"u5-solid",setup(builder){builder.onLoad({filter:/\/(SwipeCard|SwipeActionCard|DeleteConfirmDialog|WatchConfirmDialog)\.tsx$/},async args=>({contents:transformSync(await Bun.file(args.path).text(),{filename:args.path,babelrc:false,configFile:false,presets:[[require("babel-preset-solid"),{generate:"universal",moduleName:"@pocketjs/framework/renderer"}],[require("@babel/preset-typescript"),{isTSX:true,allExtensions:true}]]}).code,loader:"js"}));}});
type Element={props:Record<string,any>};
const element=(props:Element["props"]):Element=>{const p=Object.create(props);Object.defineProperty(p,"children",{value:props.children,enumerable:true});const result={props:p};props.ref?.(result);return result;};
const renderer={createComponent,mergeProps,memo:createMemo,effect:createRenderEffect};
const components={View:element,Text:element,Image:element};
let gestures:any[]=[];const frames=new Set<()=>void>();
const gesture={createGesture:(options:any)=>{gestures.push(options);onCleanup(()=>{gestures=gestures.filter(g=>g!==options);});}};
const lifecycle={onFrame:(callback:()=>void)=>{frames.add(callback);onCleanup(()=>frames.delete(callback));}};
mock.module("@pocketjs/framework/renderer",()=>renderer);
mock.module("@pocketjs/framework/components",()=>components);
mock.module("@pocketjs/framework/gesture",()=>gesture);
mock.module("@pocketjs/framework/lifecycle",()=>lifecycle);
mock.module("@pocketjs/framework/host",()=>({getOps:()=>({measureText:(text:string)=>Array.from(text).length*12})}));
const componentBase=dirname(realpathSync(import.meta.dir+"/../src/watch-ui.ts"));
mock.module(Bun.resolveSync("solid-js",componentBase),()=>solid);
for(const [name,impl] of Object.entries({renderer,components,gesture,lifecycle}))mock.module(Bun.resolveSync("@pocketjs/framework/"+name,componentBase),()=>impl);
const disposers:Array<()=>void>=[];
afterEach(()=>{while(disposers.length)disposers.pop()!();mock.restore();});
const settle=()=>new Promise<void>(resolve=>setTimeout(resolve,0));
function nodes(value:any):Element[]{if(typeof value==="function")return nodes(value());if(Array.isArray(value))return value.flatMap(nodes);if(!value||typeof value!=="object"||!("props" in value))return [];return [value,...nodes(value.props.children)];}
function snapshot(value:any):any{
 if(typeof value==="function")return snapshot(value());
 if(Array.isArray(value))return value.flatMap(v=>{const s=snapshot(v);return Array.isArray(s)?s:[s];});
 if(!value||typeof value!=="object")return value;
 if("props" in value){const p=value.props;const result:Record<string,any>={};for(const k in p)if(!["ref","onPress"].includes(k))result[k]=snapshot(p[k]);return result;}
 return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,snapshot(v)]));
}
const {SwipeActionCard,WatchConfirmDialog}=await import("../src/watch-ui.ts");
test("generic dialog uses supplied assets, labels, measurement and guards both actions",async()=>{
 const [busy,setBusy]=createSignal(false);const calls:string[]=[];
 const root=createRoot(dispose=>{disposers.push(dispose);return WatchConfirmDialog({width:233,height:233,title:"Continue",message:"message",get busy(){return busy();},measureLines:()=>["one","two"],confirm:{icon:"ok.svg",label:"Accept",class:"confirm"},cancel:{icon:"back.svg",label:"Back",class:"cancel"},onConfirm:()=>calls.push("yes"),onCancel:()=>calls.push("no")});});
 const buttons=()=>nodes(root).filter(n=>n.props.accessibilityRole==="button");
 expect(buttons().map(n=>n.props.accessibilityLabel)).toEqual(["Back","Accept"]);
 expect(nodes(root).filter(n=>n.props.src).map(n=>n.props.src)).toEqual(["back.svg","ok.svg"]);
 for(const n of buttons())n.props.onPress();expect(calls).toEqual(["no","yes"]);
 setBusy(true);await settle();for(const n of buttons()){expect(n.props.focusable).toBe(false);n.props.onPress();}expect(calls).toEqual(["no","yes"]);
});
test("swipe supports arbitrary actions and reveal width with default Date clock",async()=>{
 const calls:string[]=[];const root=createRoot(dispose=>{disposers.push(dispose);return SwipeActionCard({open:true,swipeEnabled:true,revealWidth:96,actions:[{label:"Archive",icon:"archive.svg",right:8,accessibilityLabel:"Archive item",onPress:()=>calls.push("archive")}],onOpen:()=>{},onStartSwipe:()=>calls.push("start"),onOpenSwipe:()=>calls.push("reveal"),onCloseSwipe:()=>calls.push("close"),children:"body"});});
 await settle();const g=gestures[0];expect(nodes(root).find(n=>n.props.style?.translateX!==undefined)?.props.style.translateX).toBe(-96);
 nodes(root).find(n=>n.props.accessibilityLabel==="Archive item")!.props.onPress();
 g.onPanStart({x:100,dx:0});g.onPanMove({dx:200});g.onPanEnd({dx:200});expect(calls).toEqual(["archive","start","close"]);
 for(const frame of frames)frame();
 while(disposers.length)disposers.pop()!();expect(frames.size).toBe(0);expect(gestures).toHaveLength(0);
});
