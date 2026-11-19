import {createEffect, createSignal, onCleanup, Show} from "solid-js";
import {Image, View, type NodeMirror} from "@pocketjs/framework/components";
import {platform} from "@pocketjs/framework/platform";
import {getOps} from "@pocketjs/framework/host";
import {strokeArcPixels, type StrokeArcShape} from "./stroke-arc-pixels.ts";

type StrokeArcProps = StrokeArcShape & {cx: number; cy: number; scale?: number};

/** A retained arc for changing levels. Transforms use the existing image path
 * on every host. A host without sampled textures can still draw the stroke. */
export function StrokeArc(props: StrokeArcProps) {
  let node: NodeMirror | undefined;
  const [bounds, setBounds] = createSignal<{left: number; top: number; width: number; height: number; imageWidth: number; imageHeight: number}>();
  createEffect(() => {
    const {radius, strokeWidth, start, sweep, color} = props;
    const density = platform.pixelRatio * (props.scale ?? 1);
    const ops = getOps();
    if (!node || !ops.uploadTextureSampled || !ops.freeTexture) return;
    const pixels = strokeArcPixels({radius, strokeWidth, start, sweep, color}, density);
    if (!pixels) return;
    const {bytes, ...layout} = pixels;
    const handle = ops.uploadTextureSampled(bytes, layout.textureWidth, layout.textureHeight, 5, 1);
    if (handle < 0) return;
    const id = node.id;
    onCleanup(() => {
      try { ops.setImage(id, -1); } finally { ops.freeTexture?.(handle); setBounds(undefined); }
    });
    ops.setImage(id, handle);
    setBounds({...layout, imageWidth: layout.textureWidth / density, imageHeight: layout.textureHeight / density});
  });
  const outer = () => props.radius + props.strokeWidth / 2;
  return <>
    <View accessibilityHidden class="absolute overflow-hidden" style={{
      insetL: props.cx + (bounds()?.left ?? 0), insetT: props.cy + (bounds()?.top ?? 0),
      width: bounds()?.width ?? 0, height: bounds()?.height ?? 0,
    }}><Image accessibilityHidden ref={image => { node = image; }} class="absolute left-[0] top-[0]" style={{
      width: bounds()?.imageWidth ?? 0, height: bounds()?.imageHeight ?? 0,
    }}/></View>
    <Show when={!bounds() && props.sweep !== 0 && props.strokeWidth > 0 && props.radius > 0}>
      <View accessibilityHidden class="absolute" style={{insetL: props.cx - outer(), insetT: props.cy - outer(),
        width: 2 * outer(), height: 2 * outer(), arcStart: props.start, arcSweep: props.sweep,
        arcWidth: props.strokeWidth, bgColor: "#" + (props.color >>> 0).toString(16).padStart(8, "0")}}/>
    </Show>
  </>;
}
