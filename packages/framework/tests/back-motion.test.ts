import { describe, expect, test } from "bun:test";
import { BackMotion } from "../src/navigation.ts";
describe("follow-finger back", () => {
  test("tracks distance directly and clamps leftward movement", () => {
    const m = new BackMotion(233); m.begin(17.25); expect(m.offset).toBe(17.25);
    m.move(80.5); expect(m.offset).toBe(80.5); m.move(-12); expect(m.offset).toBe(0);
  });
  test("short drag returns to its original page, long drag commits", () => {
    const m = new BackMotion(233); m.begin(40); m.release(0, 0);
    expect(m.tick(30)).toBeUndefined(); expect(m.offset).toBeLessThan(40);
    expect(m.tick(300)).toBe("cancel"); expect(m.offset).toBe(0);
    m.begin(120); m.release(0, 400); expect(m.tick(700)).toBe("commit"); expect(m.offset).toBe(233);
  });
  test("system cancellation wins even after crossing commit threshold", () => {
    const m = new BackMotion(233); m.begin(180); m.release(900, 0, true);
    expect(m.tick(300)).toBe("cancel"); expect(m.offset).toBe(0);
  });
  test("short flick commits but a tiny fast tap does not", () => {
    const m = new BackMotion(233); m.begin(40); m.release(600, 0); expect(m.tick(300)).toBe("commit");
    m.begin(5); m.release(900, 400); expect(m.tick(700)).toBe("cancel");
  });
  test("hardware back animates from rest without an initial jump", () => {
    const m = new BackMotion(233); m.begin(0); m.release(0, 0, false, true);
    expect(m.offset).toBe(0); expect(m.tick(300)).toBe("commit");
  });
});
