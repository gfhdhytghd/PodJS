import { mount } from "@pocketjs/framework/solid";
import { View, Text } from "@pocketjs/framework/components";

// Runtime input deliberately avoids the compiler's literal scan. This fixture
// must exercise cmap misses even when the source TTF has the character.
const cps = [0x6c17, 0x2460, 0x1f600, 0x3400, 0x20bb7];
const author = "UP主：蒸" + String.fromCodePoint(cps[0]) + "火鸡";
const title = "旅行者号" + String.fromCodePoint(cps[1]) + "：前往愤…";
const sequences=[[0x1f468,0x200d,0x1f469,0x200d,0x1f467,0x200d,0x1f466],[0x1f44d,0x1f3fd],[0x2764,0xfe0f]];
mount(() => <View class="w-full h-full bg-black">
  <Text class="absolute left-[40] top-[40] text-sm leading-[20] text-white">{title}</Text>
  <Text class="absolute left-[40] top-[66] text-sm leading-[20] text-white">{author}</Text>
  {cps.map((cp, i) => <>
    <Text class="absolute top-[104] text-base leading-[24] text-white" style={{insetL:40+i*32}}>{String.fromCodePoint(cp)}</Text>
    <Text class="absolute top-[132] text-[9px] leading-[12] text-white" style={{insetL:40+i*32}}>{cp.toString(16)}</Text>
    <Text class="absolute top-[156] text-base leading-[24] font-bold text-white" style={{insetL:40+i*32}}>{String.fromCodePoint(cp)}</Text>
  </>)}
  {sequences.map((sequence,i)=><Text class="absolute top-[184] text-base leading-[24] text-white" style={{insetL:52+i*56}}>{String.fromCodePoint(...sequence)}</Text>)}
</View>);
