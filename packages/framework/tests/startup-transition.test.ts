import { expect, test } from "bun:test";
import {
  STARTUP_EXIT_DURATION_MS,
  startupExitFrame,
} from "../src/startup-transition.ts";

test("startup overlay exits with the user-set 300ms fade and 1.08x scale", () => {
  expect(STARTUP_EXIT_DURATION_MS).toBe(300);
  expect(startupExitFrame(0)).toEqual({opacity:1,scale:1,finished:false});
  const middle = startupExitFrame(150);
  expect(middle.opacity).toBeCloseTo(0.5);
  expect(middle.scale).toBeCloseTo(1.04);
  expect(middle.finished).toBe(false);
  expect(startupExitFrame(300)).toEqual({opacity:0,scale:1.08,finished:true});
  expect(startupExitFrame(5000)).toEqual({opacity:0,scale:1.08,finished:true});
});
