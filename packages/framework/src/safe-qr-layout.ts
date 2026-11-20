export interface QrRect { x: number; y: number; width: number; height: number }
export interface SafeQrLayout { title: QrRect; qr: QrRect; status: QrRect; action: QrRect; scale: number; cx: number; cy: number; radius: number }

/** Fits every rectangle inside the largest centered circle of the viewport. */
export function safeQrLayout(width: number, height: number): SafeQrLayout {
  const diameter = Math.max(1, Math.min(width, height)), scale = diameter / 233, cx = width / 2, cy = height / 2, top = cy - diameter / 2;
  const rect = (centerX: number, y: number, w: number, h: number): QrRect => ({ x: cx + centerX * scale - w * scale / 2, y: top + y * scale, width: w * scale, height: h * scale });
  return { scale, cx, cy, radius: diameter / 2, title: rect(0, 13, 98, 15), qr: rect(0, 32, 152, 152), status: rect(0, 187, 117, 28), action: rect(0, 216, 44, 13) };
}

export function rectInsideCircle(rect: QrRect, layout: Pick<SafeQrLayout, "cx" | "cy" | "radius">, margin = 1): boolean {
  const radius = layout.radius - margin;
  return [[rect.x, rect.y], [rect.x + rect.width, rect.y], [rect.x, rect.y + rect.height], [rect.x + rect.width, rect.y + rect.height]].every(([x, y]) => Math.hypot(x - layout.cx, y - layout.cy) <= radius + 1e-6);
}

export interface SafeTopBlockOptions { margin?: number; minTop?: number; maxWidth?: number; minWidth?: number }
/** Centered top block whose four corners stay inside the round viewport and whose bottom edge is as
 * high as possible, leaving the most room below. Width at each candidate top is the circle's chord
 * there (capped by maxWidth); `measure` returns the height for a width, so a narrower chord can wrap
 * text taller. Crossing the centre can also narrow the available width at the
 * bottom edge. Returns undefined if nothing fits. */
export function safeTopBlock(width: number, height: number, measure: (width: number) => number, options: SafeTopBlockOptions = {}): QrRect | undefined {
  const { margin = 2, minTop = 0, maxWidth = width, minWidth = 1 } = options;
  const cx = width / 2, cy = height / 2, radius = Math.min(width, height) / 2, inner = radius - margin;
  if (inner <= 0) return;
  const chord = (distance: number) => Math.floor(2 * Math.sqrt(Math.max(0, inner * inner - distance * distance)));
  let best: QrRect | undefined;
  for (let y = Math.max(minTop, cy - inner); y < cy + inner; y++) {
    if (best && y >= best.y + best.height) break;
    let blockWidth = Math.min(Math.floor(maxWidth), chord(cy - y));
    while (blockWidth >= minWidth) {
      const rect = { x: cx - blockWidth / 2, y, width: blockWidth, height: measure(blockWidth) };
      if (!Number.isFinite(rect.height) || rect.height < 0) break;
      const bottom = rect.y + rect.height;
      if (rectInsideCircle(rect, { cx, cy, radius }, margin)) {
        if (!best || bottom < best.y + best.height) best = rect;
        break;
      }
      // A block crossing the centre can be constrained by its bottom edge instead.
      const narrowed = chord(bottom - cy);
      if (narrowed >= blockWidth) break;
      blockWidth = narrowed;
    }
  }
  return best;
}
