import type {RouteStateMemory} from './route-state.ts';
/** Caller selects whether this list owns restoration, independent of page names. */
export interface ListRestorationOwner {memory:RouteStateMemory;checkpoints:Set<()=>void>;scrollSlots:Set<number>;changed:()=>void}
import {createIndexedWindowSource,createWindowedList,visibleWindowRange,type IndexedWindowEntries} from "./windowed-list.ts";
import {ScrollRestoration,validScrollSnapshot} from './scroll-restoration.ts';
import {roundListInsets,roundRowInteractionBounds,type RoundListOptions} from './round-list.ts';
import type {QrRect} from './safe-qr-layout.ts';
import { For, createMemo, onCleanup, untrack, type JSX } from "solid-js";
import { View, type NodeMirror } from "@pocketjs/framework/components";
import { createGesture, type GestureContact } from "@pocketjs/framework/gesture";
import { createScroller, bindDpadScroll, type Scroller } from "@pocketjs/framework/kinetics";
import { onFrame } from "@pocketjs/framework/lifecycle";
import { pressNode, hitNode } from "@pocketjs/framework/input";

export interface ScrollEntry { height: number; onPress?:()=>void; render: (current?:()=>ScrollEntry) => JSX.Element; retain?:boolean; key?:unknown; data?:unknown; /** Legacy visible content bounds relative to the row's padded left/top. */ bounds?:QrRect; /** Preferred explicit visible bounds; overrides bounds. */ visibleBounds?:QrRect; /** Existing child hit targets: may extend outside the circle, but must fit the viewport/screen. */ interactionBounds?:QrRect }
export interface PositionedScrollEntry extends ScrollEntry {top:number}
export type IndexedScrollEntries=IndexedWindowEntries<PositionedScrollEntry,unknown>;
export interface VariableListHandle {scroller:Scroller;/** Additional layout whitespace, for caller-owned hit tests and overlays. */ roundInsets:()=>{start:number;end:number};/** Round-list touch rectangle in display coordinates at the current offset; independent of visible bounds. */ interactionBounds:(index:number)=>QrRect|undefined}
/** Variable-height, windowed column; the clipping viewport never translates. */
export function VariableList(props: { height: number; round?:RoundListOptions|null; entries: ScrollEntry[]; indexedEntries?:IndexedScrollEntries; footerEntries?:ScrollEntry[]; gap?: number; padding?: number; verticalPadding?: number; retainRows?:number; minRows?:number; recycleKey?:(row:PositionedScrollEntry)=>unknown; /** Equality for immutable keyed row revisions; the original keyed renderer remains mounted. */ entryEquals?:(a:PositionedScrollEntry,b:PositionedScrollEntry)=>boolean; fixed?: boolean; controller?: Scroller; inputActive?: () => boolean; accessibilityLabel?:string; onRefresh?: () => void; onManualScroll?:()=>void; onTap?:(contact:GestureContact)=>boolean|void; onLongPress?: (contact: GestureContact) => void; onScrollStateChange?:(scrolling:boolean)=>void; onOffsetChange?:(previous:number,current:number)=>void; onSettledRange?:(first:number,last:number)=>void; settleKey?:unknown; restorationOwner?:()=>ListRestorationOwner|undefined; overscan?:number;refreshDistance?:number; ref?: (handle: VariableListHandle) => void }) {
  let viewport: NodeMirror | undefined;
  const rawRows = createMemo(() => {
    if(props.indexedEntries)return createIndexedWindowSource(props.indexedEntries,props.entries,props.footerEntries??[],{padding:props.verticalPadding??props.padding??0,gap:props.gap??0});
    let top = props.verticalPadding ?? props.padding ?? 0;
    return props.entries.map(entry => { const row = { ...entry, top }; top += entry.height + (props.gap ?? 0); return row; });
  });
  const rawTotal = () => { const last = rawRows().at(-1); return last ? last.top + last.height + (props.verticalPadding ?? props.padding ?? 0) : 0; };
  const warned=new Set<string>();
  const insets=createMemo(()=>{
    if(!props.round||props.fixed)return {start:0,end:0,unfit:[]};
    const value=roundListInsets(rawRows(),rawTotal(),props.height,props.round,props.padding??0);
    if(props.round.development)for(const index of value.unfit){
      const row=rawRows().at(index)!,key=JSON.stringify([index,row.height,row.visibleBounds,row.bounds,row.interactionBounds,props.round,props.height,props.padding]);
      if(!warned.has(key)){warned.add(key);console.warn(`[VariableList] round row ${index} cannot fit completely in the safe circle and viewport; reduce/split its visible bounds.`,row.visibleBounds??row.bounds??{width:(props.round.viewport?.width??props.round.width)-2*(props.padding??0),height:row.height});}
    }
    return value;
  });
  const rows=createMemo(()=>{
    const source=rawRows(),shift=insets().start;if(!shift)return source;
    // Unkeyed arrays use object identity. Keep shifted row objects stable while
    // scrolling, just like the original positioned array.
    if(Array.isArray(source))return source.map(row=>({...row,top:row.top+shift}));
    const indexed=source as import('./windowed-rows.ts').WindowRowList<PositionedScrollEntry>;
    return {length:source.length,scope:indexed.scope,indexOfKey:indexed.indexOfKey?.bind(indexed),at(index:number){const row=source.at(index);return row?{...row,top:row.top+shift}:undefined;}};
  });
  const total = () => rawTotal()+insets().start+insets().end;
  const scroller = props.controller ?? createScroller({ max: () => Math.max(0, total() - props.height) });
  const activity=props.restorationOwner?.();
  const memory=activity?.memory;
  let scrollSlot=0;
  if(memory){while(activity!.scrollSlots.has(scrollSlot))scrollSlot++;activity!.scrollSlots.add(scrollSlot);}
  const scrollKey=memory?`scroll:${scrollSlot}`:undefined;
  const saved=scrollKey?memory!.saved[scrollKey]:undefined;
  const restoration=new ScrollRestoration(validScrollSnapshot(saved)?saved:typeof saved==='number'&&Number.isFinite(saved)&&saved>=0?saved:undefined);
  const checkpoint=()=>{if(scrollKey)memory!.saved[scrollKey]=restoration.snapshot(scroller.offset(),rows());};
  if(memory){activity!.checkpoints.add(checkpoint);onCleanup(()=>{checkpoint();activity!.checkpoints.delete(checkpoint);activity!.scrollSlots.delete(scrollSlot);});}
  const takeScroll=()=>restoration.cancel();
  const visible=createWindowedList({source:rows,lower:()=>scroller.offset()-(props.overscan??60),upper:()=>scroller.offset()+props.height+(props.overscan??60),retainRows:props.retainRows,minRows:props.minRows,recycleKey:props.recycleKey,equals:props.entryEquals});
  const active=()=>props.inputActive?.()??true;
  let refreshEligible=false,pullDistance=0;
  createGesture({ region: {node: () => viewport}, axis: "y",
    onLongPress: props.onLongPress ? contact => { if(active())props.onLongPress?.(contact); } : undefined,
    onDown: () => {if(!active())return;takeScroll();scroller.stop();refreshEligible=scroller.offset()<=0;pullDistance=0;},
    onPanStart: () => {if(!active())return;props.onManualScroll?.();if(!props.fixed)scroller.beginDrag();},
    onPanMove: c => {if(!active())return;if(refreshEligible)pullDistance=Math.max(0,pullDistance+c.fdy);if(!props.fixed)scroller.drag(-c.fdy);},
    onPanEnd: c => {if(!active())return;if(!props.fixed)scroller.endDrag(-c.vy);if(refreshEligible&&pullDistance>=(props.refreshDistance??48))props.onRefresh?.();refreshEligible=false;pullDistance=0;},
    onTap: c => {if(!active())return;if(props.onTap?.(c))return;const hit = hitNode(c.x, c.y, c.surface); if (hit) pressNode(hit); },
    onCancel: () => { refreshEligible=false;pullDistance=0;if (scroller.state() === "tracking") scroller.endDrag(0); }
  });
  bindDpadScroll(scroller,{active:()=>active()&&!props.fixed});
  let wasScrolling=false;
  let lastOffset=scroller.offset(),restoredOffset:number|undefined;
  let settledRows:unknown,settledKey:unknown,settledFirst=-1,settledLast=-1;
  onFrame(() => {
    if(memory){
      if(scroller.state()!=='idle'||restoredOffset!==undefined&&scroller.offset()!==restoredOffset){takeScroll();restoredOffset=undefined;}
      const target=restoration.target(Math.max(0,total()-props.height),rows());
      if(target!==undefined){scroller.scrollTo(target,{immediate:true});restoredOffset=target;checkpoint();}
    }
    scroller.step();
    const currentOffset=scroller.offset();if(currentOffset!==lastOffset){props.onOffsetChange?.(lastOffset,currentOffset);lastOffset=currentOffset;}
    const scrolling=active()&&scroller.state()!=="idle";if(scrolling!==wasScrolling){wasScrolling=scrolling;props.onScrollStateChange?.(scrolling);if(!scrolling&&memory){checkpoint();activity!.changed();}}
    if(!active()||scroller.state()!=="idle") {settledRows=undefined;return;}
    if(!props.onSettledRange)return;
    const list=rows(),range=visibleWindowRange(list,scroller.offset(),props.height);
    if(!range)return;
    if(list===settledRows&&props.settleKey===settledKey&&range.first===settledFirst&&range.last===settledLast)return;
    settledRows=list;settledKey=props.settleKey;settledFirst=range.first;settledLast=range.last;
    props.onSettledRange(range.first,range.last);
  }, () => scroller.state()!=="idle");
  onCleanup(() => {scroller.stop();if(wasScrolling)props.onScrollStateChange?.(false);});
  props.ref?.({scroller,roundInsets:insets,interactionBounds(index){
    const row=rows().at(index);
    return row&&props.round?roundRowInteractionBounds(row,row.top-scroller.offset(),props.round,props.height,props.padding??0):undefined;
  }});
  return <View ref={n => viewport = n} class="relative w-full overflow-hidden" style={{height: props.height}}
    accessibilityLabel={props.accessibilityLabel} accessibilityRole={props.accessibilityLabel?"list":undefined}
    accessibilityState={props.accessibilityLabel?{disabled:!active()}:undefined}
    accessibilityActions={props.accessibilityLabel&&!props.fixed&&total()>props.height?["increment","decrement"]:undefined}
    onAccessibilityAction={props.accessibilityLabel?event=>{
      if(!active()||props.fixed||(event.actionName!=="increment"&&event.actionName!=="decrement"))return;
      takeScroll();props.onManualScroll?.();scroller.scrollBy((event.actionName==="increment"?1:-1)*Math.max(1,props.height*.8),{durMs:120});
    }:undefined}>
    <View class="absolute left-[0] right-[0] top-[0]" style={{height:total(), translateY:props.fixed?0:-scroller.offset()}}>
      <For each={visible()}>{item => {const render=untrack(()=>item.value().render);return <View onPress={item.value().onPress} class="absolute" style={{insetL:props.padding ?? 0,insetR:props.padding ?? 0,insetT:item.value().top,height:item.value().height}}>{render(item.value)}</View>;}}</For>
    </View>
  </View>;
}
