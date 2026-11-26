import type { NodeMirror } from "@pocketjs/framework/components";
import type { QrRect } from "./safe-qr-layout.ts";

/** Register a row-local button and expand its tap bounds about its center.
 * The visual bounds are relative to the padded row, before scrolling. */
export function createListHitSlop(visual: QrRect, areaScale: number) {
  const scale = Math.sqrt(areaScale);
  const width = visual.width * scale;
  const height = visual.height * scale;
  const bounds = {
    x: visual.x - (width - visual.width) / 2,
    y: visual.y - (height - visual.height) / 2,
    width,
    height,
  };
  let button: NodeMirror | undefined;
  return {
    visual,
    bounds,
    ref: (node: NodeMirror) => { button = node; },
    target: () => button,
  };
}

export type ListHitSlop = ReturnType<typeof createListHitSlop>;

export function containsTap(bounds: QrRect, x: number, y: number): boolean {
  return x >= bounds.x && x < bounds.x + bounds.width &&
    y >= bounds.y && y < bounds.y + bounds.height;
}

/** Ink hits bubble through ancestors, including text or icons inside a button. */
export function hitControl(hit: NodeMirror | null): NodeMirror | null {
  for (let node = hit; node; node = node.parent) {
    if (node.onPress || node.focusable || node.domAttrs?.accessibilityRole === "button") return node;
  }
  return null;
}
