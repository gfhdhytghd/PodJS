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
