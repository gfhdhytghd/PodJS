export interface StrokeArcShape {
  radius: number;
  strokeWidth: number;
  /** Degrees clockwise from the top, as for PocketJS arcStart/arcSweep. */
  start: number;
  sweep: number;
  /** Straight-alpha 0xRRGGBBAA. */
  color: number;
}

/** Small, round-capped arcs for retained controls. Rasterize only the occupied
 * sector, at presentation density. The palette keeps a full byte of coverage
 * per pixel, including the fill color in transparent texels for filtering. */
export function strokeArcPixels(shape: StrokeArcShape, density: number) {
  const {radius, strokeWidth, start, sweep, color} = shape;
  if (![radius, strokeWidth, start, sweep, density].every(Number.isFinite) ||
      radius <= 0 || strokeWidth <= 0 || density <= 0 || sweep === 0) return;
  const extent = Math.min(360, Math.abs(sweep));
  const begin = ((sweep < 0 ? start - extent : start) % 360 + 360) % 360;
  const angle = (degrees: number) => (degrees - 90) * Math.PI / 180;
  const a = angle(begin), b = angle(begin + extent);
  const ax = Math.cos(a), ay = Math.sin(a), bx = Math.cos(b), by = Math.sin(b);
  let minX = Math.min(ax, bx), maxX = Math.max(ax, bx);
  let minY = Math.min(ay, by), maxY = Math.max(ay, by);
  const contains = (degrees: number) => (degrees - begin + 360) % 360 <= extent;
  if (contains(0)) minY = -1;
  if (contains(90)) maxX = 1;
  if (contains(180)) maxY = 1;
  if (contains(270)) minX = -1;
  const r = radius * density, half = strokeWidth * density / 2;
  const left = Math.floor(minX * r - half) - 1, top = Math.floor(minY * r - half) - 1;
  const w = Math.ceil(maxX * r + half) + 1 - left, h = Math.ceil(maxY * r + half) + 1 - top;
  if (!(w > 0 && h > 0 && w <= 512 && h <= 512)) return;
  const textureWidth = 2 ** Math.ceil(Math.log2(w)), textureHeight = 2 ** Math.ceil(Math.log2(h));
  const bytes = new Uint8Array(1024 + textureWidth * textureHeight);
  const red = color >>> 24, green = (color >>> 16) & 255, blue = (color >>> 8) & 255, alpha = color & 255;
  for (let n = 0; n < 256; n++) {
    bytes[n * 4] = red;
    bytes[n * 4 + 1] = green;
    bytes[n * 4 + 2] = blue;
    bytes[n * 4 + 3] = Math.round(n * alpha / 255);
  }
  const inner2 = Math.max(0, r - half) ** 2, outer2 = (r + half) ** 2;
  const half2 = half * half, startX = ax * r, startY = ay * r, endX = bx * r, endY = by * r;
  // Each sample row intersects the ring, its angular limits and two cap discs
  // in a few horizontal spans. Mark their union on the same 4×4 sample grid.
  const samples = new Uint8Array(w * 4);
  let first = w, last = 0;
  function span(lo: number, hi: number) {
    const from = Math.max(0, Math.ceil((lo - left) * 4 - .5));
    const to = Math.min(samples.length, Math.floor((hi - left) * 4 - .5) + 1);
    if (from >= to) return;
    samples.fill(1, from, to);
    first = Math.min(first, from >> 2);
    last = Math.max(last, (to + 3) >> 2);
  }
  let startMin: number, startMax: number, endMin: number, endMax: number;
  function ring(lo: number, hi: number) {
    if (extent === 360) span(lo, hi);
    else if (extent <= 180) span(Math.max(lo, startMin, endMin), Math.min(hi, startMax, endMax));
    else {
      span(Math.max(lo, startMin), Math.min(hi, startMax));
      span(Math.max(lo, endMin), Math.min(hi, endMax));
    }
  }
  function cap(x0: number, dy: number) {
    const side2 = half2 - dy * dy;
    if (side2 < 0) return;
    const side = Math.sqrt(side2);
    span(x0 - side, x0 + side);
  }
  for (let row = 0; row < h; row++) {
    const offset = 1024 + row * textureWidth;
    let firstCol = w, lastCol = 0;
    for (let sy = .125; sy < 1; sy += .25) {
      const y = top + row + sy, y2 = y * y;
      if (y2 > outer2) continue;
      first = w;
      last = 0;
      samples.fill(0);
      const outer = Math.sqrt(outer2 - y2), inner = Math.sqrt(Math.max(0, inner2 - y2));
      // At a fixed y, each angular half-plane is an interval on the x axis.
      startMin = endMin = -Infinity;
      startMax = endMax = Infinity;
      if (ay < 0) startMin = ax * y / ay;
      else if (ay > 0) startMax = ax * y / ay;
      else if (ax * y < 0) startMax = -Infinity;
      if (by > 0) endMin = bx * y / by;
      else if (by < 0) endMax = bx * y / by;
      else if (bx * y > 0) endMax = -Infinity;
      if (inner > 0) {
        ring(-outer, -inner);
        ring(inner, outer);
      } else ring(-outer, outer);
      if (extent !== 360) {
        cap(startX, y - startY);
        cap(endX, y - endY);
      }
      for (let col = first; col < last; col++) {
        const s = col * 4;
        bytes[offset + col]! += samples[s]! + samples[s + 1]! + samples[s + 2]! + samples[s + 3]!;
      }
      firstCol = Math.min(firstCol, first);
      lastCol = Math.max(lastCol, last);
    }
    for (let col = firstCol; col < lastCol; col++) {
      const hits = bytes[offset + col]!;
      bytes[offset + col] = Math.round(hits * 255 / 16);
    }
  }
  return {bytes, textureWidth, textureHeight, left: left / density, top: top / density,
    width: w / density, height: h / density};
}
