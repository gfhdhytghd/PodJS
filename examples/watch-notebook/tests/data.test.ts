import { expect, test } from "bun:test";
import { createWindowRows } from "@podjs/framework/windowed-list";
import { ScrollRestoration } from "@podjs/framework/navigation";
import { initialNotes, noteRows, validNotes } from "../src/data.ts";

test("large list selects only a small viewport and restores an anchor", () => {
  const rows = noteRows(initialNotes);
  const select = createWindowRows<(typeof rows)[number]>();
  const visible = select(rows, 480, 624);
  expect(visible.length).toBeLessThan(6);
  expect(visible[0]!.id).toBe(11);
  const state = new ScrollRestoration(undefined).snapshot(500, rows);
  expect(new ScrollRestoration(state).target(4500, rows)).toBe(500);
});
test("saved data rejects duplicate ids and malformed records", () => {
  expect(validNotes(initialNotes)).toBe(true);
  expect(validNotes([{id: 1, title: "One"}, {id: 1, title: "Two"}])).toBe(false);
  expect(validNotes([{id: 1, title: 42}])).toBe(false);
});
