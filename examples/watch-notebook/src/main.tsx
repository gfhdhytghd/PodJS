import { createEffect, createMemo, createSignal, For, onCleanup, Show } from "solid-js";
import { mount } from "@pocketjs/framework/solid";
import { Text, View, type NodeMirror } from "@pocketjs/framework/components";
import { createGesture } from "@pocketjs/framework/gesture";
import { hitNode, pressNode } from "@pocketjs/framework/input";
import { createScroller } from "@pocketjs/framework/kinetics";
import { onFrame } from "@pocketjs/framework/lifecycle";
import { getDisplayMetrics, kv, onSystemBack, setNavigationState, onLifecycleChange, onAxisDelta, RelativeAxis, RelativeAxisUnits } from "@podjs/framework/watch";
import { BackMotion, ScrollRestoration, validScrollSnapshot } from "@podjs/framework/navigation";
import { createWindowedList } from "@podjs/framework/windowed-list";
import { SwipeActionCard, WatchConfirmDialog } from "@podjs/framework/watch-ui";
import { RemoteImage } from "@podjs/framework/image";
import { createStartupTrace } from "@podjs/framework/startup";
import { contentRect } from "./model.ts";
import { initialNotes, noteRows, validNotes, type Note } from "./data.ts";
import { createResources } from "./resources.ts";

export function Notebook() {
  const metrics = getDisplayMetrics(), width = metrics.logicalWidth, height = metrics.logicalHeight;
  const rect = contentRect(width, height, metrics.safeInsets), listHeight = Math.max(0, rect.height - 28);
  const resources = createResources();
  const trace = createStartupTrace({ sink: message => resources.logger.i("startup", message) });
  trace.mark("mount");
  const savedNotes = kv.get<unknown>("notebook.notes");
  const [notes, setNotes] = createSignal(validNotes(savedNotes) ? savedNotes : initialNotes);
  const [detail, setDetail] = createSignal<Note>();
  const [actionId, setActionId] = createSignal<number>();
  const [remove, setRemove] = createSignal<Note>();
  const [offset, setOffset] = createSignal(0);
  const [loadImage, setLoadImage] = createSignal(false);
  const [imageError, setImageError] = createSignal(false);
  const rows = createMemo(() => noteRows(notes()));
  const max = () => Math.max(0, notes().length * 48 - listHeight);
  const savedScroll = kv.get<unknown>("notebook.scroll");
  const restoration = new ScrollRestoration(validScrollSnapshot(savedScroll) ? savedScroll : undefined);
  const scroller = createScroller({ max, extent: () => listHeight, overscroll: 0 });
  const window = createWindowedList({ source: rows, lower: () => scroller.offset() - 48, upper: () => scroller.offset() + listHeight + 48 });
  const motion = new BackMotion(width);
  let viewport: NodeMirror | undefined;
  const activeList = () => !detail() && !remove();
  const saveScroll = () => kv.set("notebook.scroll", restoration.snapshot(scroller.offset(), rows()));
  function back() {
    if (remove()) { setRemove(undefined); return true; }
    if (actionId() !== undefined && !detail()) { setActionId(undefined); return true; }
    if (!detail()) return false;
    if (motion.phase === "idle") motion.release(0, Date.now(), false, true);
    return true;
  }
  onCleanup(onSystemBack(back));
  onCleanup(onLifecycleChange(state => { if (state !== "active") { scroller.stop(); saveScroll(); } }));
  onCleanup(onAxisDelta(RelativeAxis.Primary, delta => {
    if (!activeList()) return;
    restoration.cancel(); scroller.scrollBy(-delta * 48 / (12 * RelativeAxisUnits.PerDegree), { durMs: 120 });
  }));
  createEffect(() => setNavigationState({ canGoBack: !!detail() || !!remove() || actionId() !== undefined }));
  createEffect(() => {
    const target = restoration.target(max(), rows());
    if (target !== undefined) scroller.scrollTo(target, { immediate: true });
  });
  createGesture({ region: { node: () => activeList() ? viewport : null }, axis: "y",
    onDown: () => { restoration.cancel(); scroller.stop(); },
    onPanStart: () => scroller.beginDrag(), onPanMove: contact => scroller.drag(-contact.fdy),
    onPanEnd: contact => scroller.endDrag(-contact.vy),
    onTap: contact => { const hit = hitNode(contact.x, contact.y, contact.surface); if (hit) pressNode(hit); },
    onCancel: () => scroller.endDrag(0) });
  onFrame(() => {
    if (activeList()) scroller.step();
    if (motion.phase !== "settling") return;
    const result = motion.tick(Date.now()); setOffset(motion.offset);
    if (result === "commit") { setDetail(undefined); setLoadImage(false); motion.reset(); setOffset(0); }
  });
  onCleanup(() => { saveScroll(); resources.loader.dispose(); setNavigationState({ canGoBack: false }); });
  trace.mark("content-ready"); // JS readiness, not GPU presentation.
  function open(note: Note) { scroller.stop(); saveScroll(); setActionId(undefined); setImageError(false); setDetail(note); resources.logger.i("navigation", "Opened note"); }
  function confirmRemove() {
    const note = remove(); if (!note) return;
    const next = notes().filter(item => item.id !== note.id);
    kv.set("notebook.notes", next); setNotes(next); setRemove(undefined); setActionId(undefined);
    scroller.scrollTo(Math.min(scroller.offset(), max()), { immediate: true });
    resources.logger.i("notes", "Removed note");
  }
  return <View class="relative bg-black overflow-hidden" style={{ width, height }}>
    <View class="absolute" style={{ insetL: rect.left, insetT: rect.top, width: rect.width, height: rect.height }}>
      <Text class="text-sm text-white">Watch Notebook</Text>
      <View ref={node => viewport = node} class="absolute overflow-hidden" style={{ insetT: 28, width: rect.width, height: listHeight }}>
        <View class="absolute" style={{ width: rect.width, height: notes().length * 48, translateY: -scroller.offset() }}>
          <For each={window()}>{item => <View class="absolute" style={{ insetT: item.value().top, width: rect.width, height: item.value().height }}>
            <SwipeActionCard actions={[{ label: "Remove", icon: "../assets/close.svg", right: 0, onPress: () => setRemove(item.value()), accessibilityLabel: "Remove note" }]}
              revealWidth={76} open={actionId() === item.value().id} swipeEnabled={activeList()} onOpen={() => open(item.value())}
              onActions={() => setRemove(item.value())} onStartSwipe={() => scroller.stop()}
              onOpenSwipe={() => setActionId(item.value().id)} onCloseSwipe={() => setActionId(undefined)}>
              <Text class="absolute text-sm text-white" style={{ insetL: 12, insetT: 13 }}>{item.value().title}</Text>
            </SwipeActionCard>
          </View>}</For>
        </View>
        <Show when={!notes().length}><Text class="text-sm text-white">No notes</Text></Show>
      </View>
    </View>
    <Show when={detail()}>{note => <View class="absolute inset-0 bg-black" style={{ translateX: offset() }}>
      <View class="absolute" style={{ insetL: rect.left, insetT: rect.top, width: rect.width, height: rect.height }}>
        <Text class="text-sm text-white">{note().title}</Text>
        <View class="absolute" style={{ insetT: 24, width: rect.width, height: 64 }}>
          <Show when={loadImage()} fallback={<View onPress={() => setLoadImage(true)} focusable accessibilityRole="button" accessibilityLabel="Load online image" class="absolute inset-0 bg-[#1c1f21]"><Text class="text-xs text-white">Load online image</Text></View>}>
            <RemoteImage loader={resources.loader} source={{ url: "https://picsum.photos/seed/podjs/128/128" }} width={64} height={64} onError={() => setImageError(true)} />
            <Show when={imageError()}><Text class="absolute text-xs text-white" style={{ insetT: 24 }}>Image unavailable</Text></Show>
          </Show>
        </View>
        <View class="absolute bg-[#1c1f21] rounded-[12]" style={{ insetT: 100, width: rect.width, height: 32 }} onPress={back} focusable accessibilityRole="button" accessibilityLabel="Back"><Text class="text-sm text-white">Back</Text></View>
      </View>
    </View>}</Show>
    <Show when={remove()}><WatchConfirmDialog width={width} height={height} title="Remove note?" message="Saved on this watch" busy={false} measureLines={message => [message]}
      cancel={{ icon: "../assets/close.svg", label: "Cancel", class: "absolute rounded-[9999] bg-[#23272a]" }}
      confirm={{ icon: "../assets/check.svg", label: "Confirm removal", class: "absolute rounded-[9999] bg-[#23272a]" }} onConfirm={confirmRemove} onCancel={() => setRemove(undefined)} /></Show>
  </View>;
}
mount(() => <Notebook />);
