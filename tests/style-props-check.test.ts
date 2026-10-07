import { expect, test } from "bun:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { findUnknownStyleProps } from "../packages/cli/src/style-props-check.ts";

test("framework sources only use PocketJS spec style props inline", () => {
  expect(findUnknownStyleProps([new URL("../packages/framework/src", import.meta.url).pathname])).toEqual([]);
});

test("unknown inline style props are reported with file and line", () => {
  const dir = mkdtempSync(join(tmpdir(), "style-check-"));
  writeFileSync(join(dir, "a.tsx"), `export const A = () => <View style={{posType: 1, width: 2}}>\n<Image style={{position: "absolute", "overflow": 1}}/></View>;\n`);
  expect(findUnknownStyleProps([dir])).toEqual([{ file: join(dir, "a.tsx"), line: 2, key: "position" }]);
});

test("component style contracts other than primitives are not checked", () => {
  const dir = mkdtempSync(join(tmpdir(), "style-check-"));
  writeFileSync(join(dir, "b.tsx"), `export const B = () => <MediaSurface style={{fit: "fit", blurPx: 2}}/>;\n`);
  expect(findUnknownStyleProps([dir])).toEqual([]);
});
