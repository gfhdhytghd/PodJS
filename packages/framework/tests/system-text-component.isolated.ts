import {expect,mock,test} from 'bun:test';
import {createRoot,createRenderEffect,createSignal} from 'solid-js';
type Node={id:number;tag:string;props:Record<string,any>;children:unknown[]};let next=0;
const node=(tag:string):Node=>({id:++next,tag,props:{},children:[]});
mock.module('@pocketjs/framework/renderer',()=>({
 createElement:node,insertNode:(parent:Node,child:Node)=>parent.children.push(child),
 insert:(parent:Node,read:()=>unknown)=>createRenderEffect(()=>{parent.children=[read()];}),
 setProp:(target:Node,key:string,value:unknown)=>{target.props[key]=value;}
}));
const images:[number,number][]=[],freed:number[]=[],files:string[]=[];
mock.module('@pocketjs/framework/host',()=>({getOps:()=>({setImage:(id:number,handle:number)=>images.push([id,handle]),freeTexture:(id:number)=>freed.push(id)})}));
const requests:{options:any;resolve:(value:any)=>void}[]=[];let texture=700;
mock.module('../src/services.ts',()=>({
 textRaster:{line:(options:any)=>{let resolve!:(value:any)=>void;const result=new Promise<any>(yes=>resolve=yes);requests.push({options,resolve});return {result,cancel(){}};}},
 fileChunks:{remove:async(path:string)=>{files.push(path);}},base64ToBytes:()=>{throw Error('file upload must avoid pixel bytes');},
 hasTextureFileUpload:()=>true,uploadTextureFile:()=>texture++
}));
const {SystemTextLine,createSystemTextCache,systemTextResourceCounts}=await import('../src/text.tsx');
const settle=async()=>{for(let i=0;i<16;i++)await Promise.resolve();};
test('fixed text scene preserves clipping, fallback, carets, raster identity and final ownership',async()=>{
 let dispose!:()=>void,root!:Node;const caretEvents:any[]=[];
 const initial={text:'中文',widthPx:40,heightPx:24,baselinePx:18,fontSizePx:18,colorArgb:0xff112233};
 const [options,setOptions]=createSignal(initial),fallback=node('fallback');
 createRoot(stop=>{dispose=stop;root=SystemTextLine({get options(){return options();},pixelScale:2,fallback:fallback as any,onCarets:value=>caretEvents.push(value)}) as unknown as Node;});
 expect(root.tag).toBe('view');expect(root.props).toMatchObject({class:'relative overflow-hidden',accessibilityRole:'text',accessibilityLabel:'中文',style:{width:20,height:12}});
 const hidden=root.children[0] as Node,image=root.children[1] as Node;
 expect(hidden.props).toMatchObject({class:'absolute inset-0',accessibilityHidden:true,style:{opacity:1}});expect(hidden.children).toEqual([fallback]);
 expect(image.props).toMatchObject({class:'absolute left-[0] top-[0]',accessibilityHidden:true,style:{width:20,height:12,opacity:0}});
 expect(requests).toHaveLength(1);requests[0].resolve({path:'text/one',width:64,height:32,carets:[0,18,36]});await settle();
 expect(image.props.style).toEqual({width:32,height:16,opacity:1});expect(hidden.props.style).toEqual({opacity:0});expect(root.props.style).toEqual({width:20,height:12});expect(caretEvents.at(-1)).toEqual([0,9,18]);
 setOptions({...initial});await settle();expect(requests).toHaveLength(1);expect(freed).toEqual([]);
 setOptions({...initial,text:'文字'});await settle();expect(requests).toHaveLength(2);expect(freed).toEqual([700]);expect(root.props.accessibilityLabel).toBe('文字');expect(hidden.props.style.opacity).toBe(1);
 requests[1].resolve({path:'text/two',width:64,height:32,carets:[0,20,40]});await settle();expect(caretEvents.at(-1)).toEqual([0,10,20]);
 dispose();await settle();expect(freed).toEqual([700,701]);expect(images.at(-1)).toEqual([image.id,-1]);expect(systemTextResourceCounts()).toEqual({active:0,queued:0,cached:0});
});


test('direct fallback keeps identical pixel geometry while top changes do not rerasterize',async()=>{
 let dispose!:()=>void,root!:Node,paint!:Node;const [top,setTop]=createSignal(6);
 const options={text:'字',widthPx:40,heightPx:24,baselinePx:18,fontSizePx:18};const before=requests.length;
 createRoot(stop=>{dispose=stop;root=SystemTextLine({options,pixelScale:2,get top(){return top();},renderFallback:visible=>{paint=node('fallback');paint.props.accessibilityHidden=true;createRenderEffect(()=>paint.props.style={opacity:visible()?1:0});return paint as any;}}) as unknown as Node;});
 expect(root.props.class).toBe('absolute left-[0] overflow-hidden');expect(root.props.style).toEqual({width:20,height:12,insetT:6});expect(root.children).toHaveLength(2);expect(root.children[0]).toBe(paint);expect(paint.props.style.opacity).toBe(1);
 setTop(8);expect(root.props.style.insetT).toBe(8);expect(requests).toHaveLength(before+1);
 requests.at(-1)!.resolve({path:'text/compact',width:64,height:32,carets:[0,18]});await settle();
 expect(paint.props.style.opacity).toBe(0);expect((root.children[1] as Node).props.style).toEqual({width:32,height:16,opacity:1});expect(root.props.style).toEqual({width:20,height:12,insetT:8});
 dispose();await settle();expect(systemTextResourceCounts()).toEqual({active:0,queued:0,cached:0});
});


test('scoped bitmap history reuses released pixels, obeys both limits and closes every lease',async()=>{
 const cache=createSystemTextCache({maxEntries:1,maxBytes:8192});let dispose!:()=>void;
 const mount=(text:string)=>createRoot(stop=>{dispose=stop;return SystemTextLine({options:{text,widthPx:40,heightPx:24,baselinePx:18,fontSizePx:18},pixelScale:2,cache});});
 let before=requests.length;mount('一');requests[before].resolve({path:'text/cache-one',width:64,height:32,carets:[0,18]});await settle();const firstHandle=images.at(-1)![1];dispose();await settle();
 expect(cache.stats()).toEqual({retained:1,retainedBytes:8192,entries:1});expect(freed).not.toContain(firstHandle);
 before=requests.length;mount('一');await settle();expect(requests).toHaveLength(before);expect(images.at(-1)![1]).toBe(firstHandle);dispose();await settle();
 mount('二');requests[before].resolve({path:'text/cache-two',width:64,height:32,carets:[0,18]});await settle();dispose();await settle();expect(freed).toContain(firstHandle);expect(cache.stats().retained).toBe(1);
 cache.close();cache.close();expect(cache.stats()).toEqual({retained:0,retainedBytes:0,entries:0});expect(systemTextResourceCounts()).toEqual({active:0,queued:0,cached:0});
 const tiny=createSystemTextCache({maxEntries:8,maxBytes:4});before=requests.length;createRoot(stop=>{dispose=stop;SystemTextLine({options:{text:'小',widthPx:40,heightPx:24,baselinePx:18,fontSizePx:18},pixelScale:2,cache:tiny});});requests[before].resolve({path:'text/cache-tiny',width:64,height:32,carets:[0,18]});await settle();dispose();await settle();expect(tiny.stats().retained).toBe(0);tiny.close();
});
