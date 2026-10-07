import { describe, expect, test } from "bun:test";
import { rectInsideCircle, safeQrLayout } from "../src/safe-qr-layout.ts";

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
