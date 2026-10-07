import { describe, expect, test } from "bun:test";
import { rectInsideCircle, safeQrLayout, safeTopBlock } from "../src/safe-qr-layout.ts";

describe("safe QR geometry", () => {
  for (const [width, height] of [[233, 233], [192, 192], [160, 160], [280, 233], [233, 280]]) {
    test(`${width}x${height} keeps every corner in the safe circle`, () => {
      const layout = safeQrLayout(width, height);
      for (const rect of [layout.title, layout.qr, layout.status, layout.action]) expect(rectInsideCircle(rect, layout, layout.scale)).toBe(true);
    });
  }
});

test("circle boundary includes tolerance and default margin is one viewport unit", () => {
  const circle = {cx:0,cy:0,radius:5};
  expect(rectInsideCircle({x:3,y:4,width:0,height:0},circle,0)).toBe(true);
  expect(rectInsideCircle({x:3,y:4,width:0,height:0},circle)).toBe(false);
  expect(rectInsideCircle({x:-3,y:-4,width:6,height:8},circle,0)).toBe(true);
  expect(rectInsideCircle({x:-3,y:-4,width:6.01,height:8},circle,0)).toBe(false);
});

describe("safe top block", () => {
  // Text-like block: fixed padding plus lines of a 120px-wide string wrapped to the width.
  const wrapped = (width: number) => 12 + Math.ceil(120 / Math.max(1, width - 20)) * 14;
  for (const [width, height] of [[233, 233], [192, 192], [280, 233], [233, 280]]) {
    test(`${width}x${height} returns the highest fully visible block`, () => {
      const block = safeTopBlock(width, height, wrapped, { minTop: 4, maxWidth: width - 16 })!;
      const circle = { cx: width / 2, cy: height / 2, radius: Math.min(width, height) / 2 };
      expect(block.height).toBe(wrapped(block.width));
      expect(rectInsideCircle(block, circle, 2)).toBe(true);
      const higher = safeTopBlock(width, height, wrapped, { minTop: 4, maxWidth: width - 16, minWidth: block.width + 1 });
      expect(higher === undefined || higher.y >= block.y).toBe(true);
      expect(block.x + block.width / 2).toBeCloseTo(width / 2);
    });
  }
  test("respects maxWidth and reports when nothing fits", () => {
    expect(safeTopBlock(233, 233, () => 10, { maxWidth: 40 })!.width).toBeLessThanOrEqual(40);
    expect(safeTopBlock(233, 233, () => 400)).toBeUndefined();
  });
});
