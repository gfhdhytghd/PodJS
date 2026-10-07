import {View} from "@pocketjs/framework/components";
import {onFrame} from "@pocketjs/framework/lifecycle";
import {createEffect, createMemo, createSignal} from "solid-js";
import {WATCH_PROGRESS_CYCLE_MS, watchProgressArc} from "./watch-progress-animation.ts";

export interface WatchProgressIndicatorProps {
  /** Logical units, matching native WatchCircularProgressIndicator defaults. */
  size?: number;
  strokeWidth?: number;
  color?: string;
  trackColor?: string;
  /** Supply the owning route's visibility and application lifecycle state. */
  active?: () => boolean;
  accessibilityLabel?: string;
}

/** Native Material3 head/tail animation over two generic, round-cap arcs.
 * Keep these nodes axis aligned: rotation is already included in arcStart.
 */
export function WatchProgressIndicator(props: WatchProgressIndicatorProps) {
  const active = () => props.active?.() ?? true;
  const size = () => props.size ?? 40;
  const strokeWidth = () => props.strokeWidth ?? 3;
  const [elapsedMs, setElapsedMs] = createSignal(0);
  let lastFrameAt: number | undefined;
  // A host may stop delivering frames while inactive. Clear the clock from
  // the reactive state change too, so resume never counts background time.
  createEffect(() => { if (!active()) lastFrameAt = undefined; });
  onFrame(() => {
    if (!active()) { lastFrameAt = undefined; return; }
    const now = Date.now();
    if (lastFrameAt !== undefined) {
      const delta = Math.max(0, now - lastFrameAt);
      setElapsedMs(value => (value + delta) % WATCH_PROGRESS_CYCLE_MS);
    }
    lastFrameAt = now;
  });
  const arc = createMemo(() => watchProgressArc(elapsedMs(), strokeWidth()));
  return <View class="relative" style={{width: size(), height: size()}}
    accessibilityLabel={props.accessibilityLabel ?? "Loading"}
    accessibilityState={{busy: true}} accessibilityHidden={!active()}>
    <View accessibilityHidden class="absolute inset-0"
      style={{arcStart: 0, arcSweep: 360, arcWidth: strokeWidth(), bgColor: props.trackColor ?? "#b6b6b659"}} />
    <View accessibilityHidden class="absolute inset-0"
      style={{arcStart: arc().arcStart, arcSweep: arc().arcSweep, arcWidth: strokeWidth(), bgColor: props.color ?? "#ffffff"}} />
  </View>;
}
