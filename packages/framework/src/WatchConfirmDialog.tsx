import { Image, Text, View } from "@pocketjs/framework/components";

/** Native WatchConfirmDialog geometry, scaled from its 466-unit design. */
export interface WatchConfirmDialogProps {
 width:number;height:number;title:string;message:string;busy:boolean;onConfirm:()=>void;onCancel:()=>void;
 measureLines:(message:string)=>string[];
 confirm:{icon:string;label:string;class:string};cancel:{icon:string;label:string;class:string};
}
export function WatchConfirmDialog(props:WatchConfirmDialogProps) {
  const scale=()=>Math.min(props.width,props.height)/466;
  const lines=()=>props.measureLines(props.message);
  const textTop=()=>96+(204-(46*(1+lines().length)+8))/2;
  return <View class="absolute inset-0 bg-[#000000]" onPress={()=>{}}>
    <Text class="absolute text-[17px] text-center text-[#f5f5f5]" style={{insetL:40*scale(),width:193,scale:2*scale(),originX:-.5,originY:-.5,insetT:textTop()*scale(),lineHeight:23,height:23}}>{props.title}</Text>
    <Text class="absolute text-[17px] text-center text-[#b6b6b6]" style={{insetL:40*scale(),width:193,scale:2*scale(),originX:-.5,originY:-.5,insetT:(textTop()+54)*scale(),lineHeight:23,height:23*lines().length}}>{lines().join("\n")}</Text>
    <View class={props.cancel.class} style={{insetL:113*scale(),insetT:332*scale(),width:104*scale(),height:104*scale()}}>
      <Image src={props.cancel.icon} class="absolute" style={{insetL:28*scale(),insetT:28*scale(),width:48*scale(),height:48*scale()}}/>
      <View class="absolute inset-0" onPress={()=>{if(!props.busy)props.onCancel();}} focusable={!props.busy} accessibilityRole="button" accessibilityLabel={props.cancel.label}/>
    </View>
    <View class={props.confirm.class} style={{insetL:249*scale(),insetT:332*scale(),width:104*scale(),height:104*scale()}}>
      <Image src={props.confirm.icon} class="absolute" style={{insetL:28*scale(),insetT:28*scale(),width:48*scale(),height:48*scale()}}/>
      <View class="absolute inset-0" onPress={()=>{if(!props.busy)props.onConfirm();}} focusable={!props.busy} accessibilityRole="button" accessibilityLabel={props.confirm.label}/>
    </View>
  </View>;
}
