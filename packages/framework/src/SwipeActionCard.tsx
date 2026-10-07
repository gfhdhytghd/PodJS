import { createEffect, createSignal, type JSX } from "solid-js";
import { Image, Text, View, type NodeMirror } from "@pocketjs/framework/components";
import { createGesture } from "@pocketjs/framework/gesture";
import { onFrame } from "@pocketjs/framework/lifecycle";

/** Pill action geometry compatible with the 164-unit watch row preset. */
export interface SwipeCardAction {label:string;icon:string;right:number;onPress:()=>void;accessibilityLabel:string}
export interface SwipeActionCardProps {
 onOpen:()=>void;onActions?:()=>void;actions:readonly SwipeCardAction[];revealWidth:number;
 open:boolean;swipeEnabled:boolean;onStartSwipe:()=>void;onOpenSwipe:()=>void;onCloseSwipe:()=>void;cardClass?:string;
 edgeSwipe?:{startX:(x:number)=>boolean;onSwipe:(dx:number)=>void};children:JSX.Element;
}
export function SwipeActionCard(props:SwipeActionCardProps) {
  let node:NodeMirror|undefined;
  const [offset,setOffset]=createSignal(props.open?-props.revealWidth:0);
  let dragging=false,edge=false,dragStart=0,animation:{from:number;to:number;start:number}|null=null;
  const animate=(to:number)=>{animation={from:offset(),to,start:Date.now()};};
  onFrame(()=>{if(!animation)return;const part=Math.min(1,(Date.now()-animation.start)/180),eased=1-(1-part)*(1-part);setOffset(animation.from+(animation.to-animation.from)*eased);if(part>=1)animation=null;});
  createEffect(()=>{const open=props.open,enabled=props.swipeEnabled;if(!dragging)animate(open&&enabled?-props.revealWidth:0);});
  createGesture({region:{node:()=>props.swipeEnabled?node:null},axis:"x",panSlop:8,onLongPress:props.onActions,
    onPanStart:c=>{edge=!props.open&&!!props.edgeSwipe?.startX(c.x-c.dx);if(edge)return;dragging=true;animation=null;dragStart=offset();props.onStartSwipe();},
    onPanMove:c=>{if(!edge)setOffset(Math.max(-props.revealWidth,Math.min(0,dragStart+c.dx)));},
    onPanEnd:c=>{if(edge){edge=false;props.edgeSwipe?.onSwipe(c.dx);return;}dragging=false;if(offset()<=-props.revealWidth/2){props.onOpenSwipe();animate(-props.revealWidth);}else{props.onCloseSwipe();animate(0);}},
    onCancel:()=>{if(edge){edge=false;return;}dragging=false;props.onCloseSwipe();animate(0);}
  });
  const action=({label,icon,right,onPress:press,accessibilityLabel}:SwipeCardAction)=><View onPress={press} focusable={props.open} accessibilityRole="button" accessibilityLabel={accessibilityLabel} class="absolute top-[0] bottom-[0] w-[72] rounded-[16] bg-[#23272a] flex-col items-center justify-center" style={{insetR:right}}><Image src={icon} class="w-[16] h-[16]"/><Text class="mt-[4] text-xs leading-[14] text-[#f5f5f5]">{label}</Text></View>;
  return <View class="absolute inset-0 overflow-hidden bg-black">
    {props.actions.map(action)}
    <View ref={value=>node=value} onPress={props.onOpen} focusable class={props.cardClass??"absolute inset-0 rounded-[16] overflow-hidden bg-[#1c1f21]"} style={{translateX:offset()}}>{props.children}</View>
  </View>;
}
