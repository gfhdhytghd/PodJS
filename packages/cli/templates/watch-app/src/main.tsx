import { createEffect, createSignal, For, onCleanup, Show } from "solid-js";
import { mount } from "@pocketjs/framework/solid";
import { Text, View } from "@pocketjs/framework/components";
import { onFrame } from "@pocketjs/framework/lifecycle";
import { getDisplayMetrics, kv, onSystemBack, setNavigationState } from "@podjs/framework/watch";
import { BackMotion } from "@podjs/framework/navigation";
import config from "../pod.config.json";
import { contentRect, items } from "./model.ts";

export function App() {
  const metrics = getDisplayMetrics();
  const rect = contentRect(metrics.logicalWidth, metrics.logicalHeight, metrics.safeInsets);
  const [selected, setSelected] = createSignal<number | undefined>();
  const [offset, setOffset] = createSignal(0);
  const motion = new BackMotion(metrics.logicalWidth);
  function back() {
    if (selected() === undefined) return false;
    if (motion.phase === "idle") motion.release(0, Date.now(), false, true);
    return true;
  }
  onCleanup(onSystemBack(back));
  createEffect(() => setNavigationState({ canGoBack: selected() !== undefined }));
  onCleanup(() => setNavigationState({ canGoBack: false }));
  onFrame(() => {
    if (motion.phase !== "settling") return;
    const result = motion.tick(Date.now());
    setOffset(motion.offset);
    if (result === "commit") { setSelected(undefined); motion.reset(); setOffset(0); }
  });
  function open(id: number) { kv.set("lastOpened", id); setSelected(id); }
  return <View class="relative bg-black overflow-hidden" style={{ width: metrics.logicalWidth, height: metrics.logicalHeight }}>
    <View class="absolute" style={{ insetL: rect.left, insetT: rect.top, width: rect.width, height: rect.height }}>
      <Text class="text-sm text-white" style={{ height: 24 }}>{config.name}</Text>
      <For each={items}>{(item, index) => <View class="absolute rounded-[12] bg-[#1c1f21]" style={{ insetT: 30 + index() * 40, width: rect.width, height: 34 }}
        onPress={() => open(item.id)} focusable accessibilityRole="button" accessibilityLabel={item.title}>
        <Text class="absolute text-sm text-white" style={{ insetL: 10, insetT: 8 }}>{item.title}</Text>
      </View>}</For>
    </View>
    <Show when={selected() !== undefined}>
      <View class="absolute inset-0 bg-black" style={{ translateX: offset() }}>
        <View class="absolute" style={{ insetL: rect.left, insetT: rect.top, width: rect.width, height: rect.height }}>
          <Text class="text-sm text-white">{items.find(item => item.id === selected())?.title}</Text>
          <Text class="absolute text-xs text-white" style={{ insetT: 32 }}>{items.find(item => item.id === selected())?.description}</Text>
          <View class="absolute rounded-[12] bg-[#1c1f21]" style={{ insetT: 100, width: rect.width, height: 32 }} onPress={back} focusable accessibilityRole="button" accessibilityLabel="Back">
            <Text class="absolute text-sm text-white" style={{ insetL: 10, insetT: 7 }}>Back</Text>
          </View>
        </View>
      </View>
    </Show>
  </View>;
}
mount(() => <App />);
