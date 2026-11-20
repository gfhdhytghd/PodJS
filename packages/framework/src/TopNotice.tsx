import {Text,View} from '@pocketjs/framework/components';
import {createMemo,Show} from 'solid-js';
import {layoutAtlasText} from './atlas-text.ts';
import {safeTopBlock,type QrRect} from './safe-qr-layout.ts';

export interface TopNoticeProps {
  message:string;
  width:number;
  height:number;
  round:boolean;
  top:number;
  maxWidth?:number;
  background:string;
  color:string;
}

const horizontalPadding=12, verticalPadding=7, fontSlot=0, lineHeight=14;

/** A passive foreground notice. Its measured lines and background share the same width budget. */
export function TopNotice(props:TopNoticeProps) {
  const layout=createMemo(()=>{
    const width=props.width,height=props.height,top=Math.max(0,props.top);
    const maxWidth=Math.min(width,props.maxWidth??width-24);
    const minWidth=2*horizontalPadding+24;
    const completeLineLimit=Math.floor((height-top-2*verticalPadding)/lineHeight);
    if(!props.message||maxWidth<minWidth||completeLineLimit<1)return;
    // Try the full message first. Only an oversized message uses the compact preview.
    for(const maxLines of [undefined,4,3,2,1]){
      const layouts=new Map<number,ReturnType<typeof layoutAtlasText>>();
      const text=(w:number)=>{
        let result=layouts.get(w);
        if(!result){
          result=layoutAtlasText(props.message,{width:w-2*horizontalPadding,fontSlot,lineHeight,maxLines:maxLines??completeLineLimit+1});
          layouts.set(w,result);
        }
        return result;
      };
      const measure=(w:number)=>{
        const layout=text(w);
        return maxLines===undefined&&layout.lines.length>completeLineLimit
          ? Infinity
          : layout.style.height+2*verticalPadding;
      };
      const box:QrRect|undefined=props.round
        ? safeTopBlock(width,height,measure,{minTop:top,maxWidth,minWidth})
        : {x:(width-maxWidth)/2,y:top,width:maxWidth,height:measure(maxWidth)};
      if(box&&box.y+box.height<=height)return {box,text:text(box.width)};
    }
  });
  return <Show when={layout()}>{item=><View debugName="TopNotice" accessibilityLabel={props.message} accessibilityRole="text"
    class="absolute z-10 overflow-hidden rounded-[8]"
    style={{insetL:item().box.x,insetT:item().box.y,width:item().box.width,height:item().box.height,bgColor:props.background}}>
    <Text accessibilityHidden class="absolute text-xs leading-[14] text-center overflow-hidden"
      style={{...item().text.style,insetL:horizontalPadding,insetT:verticalPadding,textColor:props.color}}>{item().text.lines.join('\n')}</Text>
  </View>}</Show>;
}
