import {expect, test} from "bun:test";
import {watchProgressArc} from "../src/watch-ui.ts";

// Fixed oracle points evaluated independently from the published Material3
// 1.3.0 head/tail keyframes and cubic curve. Angles add 90 degrees to Compose
// drawArc's coordinate system to match the generic renderer's top origin.
// These include both halves, integer phase changes and the full star cycle.
const composeOracle = [
  [0, 4.297183463, .1],
  [166, 39.939826106, 68.089966307],
  [333, 75.797183463, 224.912780224],
  [500, 111.654540821, 278.296438652],
  [666, 147.297183463, 290],
  [833, 252.287227381, 220.867313440],
  [999, 443.709963687, 65.087219776],
  [1166, 532.950979473, 11.703561348],
  [1331, 580.082127833, .1],
  [1332, 220.297183463, .1],
  [1998, 363.297183463, 290],
  [2664, 76.297183463, .1],
  [3330, 219.297183463, 290],
  [3996, 292.297183463, .1],
  [5328, 148.297183463, .1],
  [6659, 724.082127833, .1],
  [6660, 4.297183463, .1],
] as const;

test("progress arc follows Compose head/tail timing, easing and five-phase rotation", () => {
  for (const [time, start, sweep] of composeOracle) {
    const actual = watchProgressArc(time);
    expect(actual.arcStart, `start at ${time}ms`).toBeCloseTo(start, 4);
    expect(actual.arcSweep, `sweep at ${time}ms`).toBeCloseTo(sweep, 4);
  }
});

test("cap correction retains the native fixed diameter and visible minimum arc", () => {
  expect(watchProgressArc(0, 6).arcStart).toBeCloseTo(8.594366927, 6);
  for (const time of [0, 1332, 2664, 3996, 5328, 6660, 6_660_000]) {
    expect(watchProgressArc(time).arcSweep).toBe(.1);
  }
  expect(watchProgressArc(6660 + 333)).toEqual(watchProgressArc(333));
});
