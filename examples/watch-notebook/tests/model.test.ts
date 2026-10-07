import { expect, test } from "bun:test";
import { BackMotion } from "@podjs/framework/navigation";
import { contentRect } from "../src/model.ts";

test("content corners fit a round display", () => {
  const rect = contentRect(240, 240, { left: 0, right: 0, top: 0, bottom: 0 });
  for (const x of [rect.left, rect.left + rect.width]) for (const y of [rect.top, rect.top + rect.height]) {
    expect(Math.hypot(x - 120, y - 120)).toBeLessThan(120);
  }
});
test("back commits only after its animation", () => {
  const motion = new BackMotion(240);
  motion.release(0, 0, false, true);
  expect(motion.tick(1)).toBeUndefined();
  expect(motion.tick(300)).toBe("commit");
});
