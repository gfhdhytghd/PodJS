import {expect, test} from "bun:test";
import {strokeArcPixels, type StrokeArcShape} from "../src/stroke-arc-pixels.ts";

const color = 0xf0602680;

function reference(shape: StrokeArcShape, x: number, y: number) {
  const angle = (Math.atan2(x, -y) * 180 / Math.PI + 360) % 360;
  const from = shape.sweep < 0 ? shape.start + shape.sweep : shape.start;
  if ((angle - from % 360 + 720) % 360 <= Math.abs(shape.sweep)) {
    return Math.abs(Math.hypot(x, y) - shape.radius) <= shape.strokeWidth / 2;
  }
  return [shape.start, shape.start + shape.sweep].some(degrees => {
    const t = degrees * Math.PI / 180;
    return Math.hypot(x - shape.radius * Math.sin(t), y + shape.radius * Math.cos(t)) <= shape.strokeWidth / 2;
  });
}

test("density-scaled physical coverage matches a 16×16 reference, including caps and thin sweeps", () => {
  for (const [density, start, sweep] of [[1, 40, 26], [2, 41, 25], [2, 66, -.26], [2.25, -40, 280], [1, 15, -360]]) {
    const shape = {radius:12, strokeWidth:1.5, start:start!, sweep:sweep!, color};
    const px = strokeArcPixels(shape, density!)!;
    const count = Math.ceil((shape.radius + shape.strokeWidth / 2) * density! + 2);
    let partial = 0, worst = 0;
    for (let y = -count; y < count; y++) for (let x = -count; x < count; x++) {
      const row = y - Math.round(px.top * density!), col = x - Math.round(px.left * density!);
      const actual = col >= 0 && row >= 0 && col < px.textureWidth && row < px.textureHeight
        ? px.bytes[1024 + row * px.textureWidth + col]! / 255 : 0;
      let hits = 0;
      for (let sy = 0; sy < 16; sy++) for (let sx = 0; sx < 16; sx++) {
        if (reference(shape, (x + (sx + .5) / 16) / density!, (y + (sy + .5) / 16) / density!)) hits++;
      }
      if (actual > 0 && actual < 1) partial++;
      worst = Math.max(worst, Math.abs(actual - hits / 256));
    }
    expect(worst).toBeLessThan(.16);
    expect(partial).toBeGreaterThan(4);
    expect([...px.bytes.slice(0, 4)]).toEqual([0xf0, 0x60, 0x26, 0]);
    expect([...px.bytes.slice(1020, 1024)]).toEqual([0xf0, 0x60, 0x26, 0x80]);
  }
});

test("small sectors allocate a cropped texture and keep empty or oversized inputs out of the uploader", () => {
  const shape = {radius:110, strokeWidth:1.5, start:40, sweep:26, color};
  const p = strokeArcPixels(shape, 2)!;
  expect(p.textureWidth * p.textureHeight).toBeLessThanOrEqual(128 * 128);
  expect(p.bytes.length).toBe(1024 + p.textureWidth * p.textureHeight);
  expect(strokeArcPixels({...shape, sweep:0}, 2)).toBeUndefined();
  expect(strokeArcPixels({...shape, radius:NaN}, 2)).toBeUndefined();
  expect(strokeArcPixels({...shape, sweep:360}, 1000)).toBeUndefined();
});
