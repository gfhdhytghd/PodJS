import { expect, test } from 'bun:test';
import { clampDockCenter, edgeDockCenter } from '../src/edge-dock.ts';

test('round docking keeps the direction and the entire touch circle at every angle', () => {
  const width = 233, height = 256, inset = 28, radius = width / 2 - inset;
  for (let degrees = 0; degrees < 360; degrees++) {
    const angle = degrees * Math.PI / 180;
    const center = { x: width / 2, y: height / 2 };
    const point = { x: center.x + 40 * Math.cos(angle), y: center.y + 40 * Math.sin(angle) };
    const edge = edgeDockCenter(point, width, height, true, inset);
    expect(edge.x).toBeCloseTo(center.x + radius * Math.cos(angle), 8);
    expect(edge.y).toBeCloseTo(center.y + radius * Math.sin(angle), 8);
    expect(clampDockCenter(point, width, height, true, inset)).toEqual(point);
    const outside = { x: center.x + 300 * Math.cos(angle), y: center.y + 300 * Math.sin(angle) };
    const clamped = clampDockCenter(outside, width, height, true, inset);
    expect(Math.hypot(clamped.x - center.x, clamped.y - center.y)).toBeCloseTo(radius, 8);
  }
});

test('rectangular docking retains the closest corner and collapses safely on a small viewport', () => {
  expect(edgeDockCenter({ x: 290, y: 20 }, 320, 240, false, 36)).toEqual({ x: 284, y: 36 });
  expect(edgeDockCenter({ x: 290, y: 235 }, 320, 240, false, 36)).toEqual({ x: 284, y: 204 });
  expect(edgeDockCenter({ x: 100, y: 100 }, 40, 30, false, 36)).toEqual({ x: 20, y: 15 });
});
