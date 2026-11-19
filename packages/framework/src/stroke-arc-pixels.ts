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
  const scanInner = Math.max(0, r - half - 1) ** 2, scanOuter = (r + half + 1) ** 2;
  const half2 = half * half, startX = ax * r, startY = ay * r, endX = bx * r, endY = by * r;
  function covered(x: number, y: number) {
    const d2 = x * x + y * y;
    if (d2 < inner2 || d2 > outer2) return false;
    if (extent === 360) return true;
    const afterStart = ax * y - ay * x >= 0, beforeEnd = bx * y - by * x <= 0;
    if (extent > 180 ? afterStart || beforeEnd : afterStart && beforeEnd) return true;
    return (x - startX) ** 2 + (y - startY) ** 2 <= half2 ||
      (x - endX) ** 2 + (y - endY) ** 2 <= half2;
  }
  for (let row = 0; row < h; row++) {
    const y = top + row, y2 = (y + .5) ** 2;
    for (let col = 0; col < w; col++) {
      const x = left + col, d2 = (x + .5) ** 2 + y2;
      if (d2 < scanInner || d2 > scanOuter) continue;
      let hits = 0;
      for (let sy = .125; sy < 1; sy += .25) for (let sx = .125; sx < 1; sx += .25) {
        if (covered(x + sx, y + sy)) hits++;
      }
      bytes[1024 + row * textureWidth + col] = Math.round(hits * 255 / 16);
    }
  }
  return {bytes, textureWidth, textureHeight, left: left / density, top: top / density,
    width: w / density, height: h / density};
}
