import { afterEach, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { encodePackageManifest, PACKAGE_MANIFEST_MAX_BYTES, writeProjectManifest } from "../packages/cli/src/package-manifest.ts";
import type { PackagedAsset, PakFontAlias } from "../packages/cli/src/project-assets.ts";

const roots: string[] = [];
const exported = process.env.PODJS_MANIFEST_CASES;
const base = { schema: 1, target: "android-watch", hostAbi: 3, capabilities: [], background: {}, backgroundServices: [] };
const sha = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");
function fixture(large = true) {
  const root = mkdtempSync(join(tmpdir(), "pod-manifest-")); roots.push(root);
  const output = join(root, "output"); mkdirSync(output);
  // R1 retains the appendix's 8-digit byte count and offset: +266 metadata bytes.
  const bytes = Buffer.alloc(large ? 22_741_096 : 6, 42);
  const font = join(root, "source.ttf"), tiny = join(root, "tiny.bin");
  writeFileSync(font, bytes); writeFileSync(tiny, "x");
  const fontMeta = { size: bytes.length, sha256: sha(bytes) };
  const alias: PakFontAlias = { key: `ui:font-source.${fontMeta.sha256}`, offset: 19_406_784, bytes: bytes.length, sha256: fontMeta.sha256 };
  return { root, output, font, tiny, bytes, fontMeta, alias };
}
function manifest(assets: Record<string, PackagedAsset>) { return { ...base, assets }; }
/** Pad a legal logical name, as in review appendix A, not an ignored JSON field. */
function padLast(assets: Record<string, PackagedAsset>, targetBytes: number) {
  const key = Object.keys(assets).at(-1)!;
  const extra = targetBytes - encodePackageManifest(manifest(assets)).byteLength;
  expect(extra).toBeGreaterThanOrEqual(0);
  const prefix = extra <= 200 ? "x".repeat(extra) : "x".repeat(200) + "/" + "x".repeat(extra - 201);
  const name = key.replace("/", "/" + prefix);
  expect(name.length).toBeLessThanOrEqual(512);
  expect(Math.max(...name.split("/").map(s => s.length))).toBeLessThanOrEqual(255);
  const value = assets[key]!; delete assets[key]; assets[name] = value;
  expect(encodePackageManifest(manifest(assets)).byteLength).toBe(targetBytes);
}
function ordinaryAssets(f: ReturnType<typeof fixture>, bytes: number, shared: boolean) {
  const assets: Record<string, PackagedAsset> = { "fonts/watch-sans.ttf": { ...f.fontMeta, ...(shared ? { pakFont: f.alias } : {}) } };
  const tiny = { size: 1, sha256: sha(Buffer.from("x")) };
  let i = 0;
  while (true) {
    const key = `data/a${i++}.bin`; assets[key] = tiny;
    if (encodePackageManifest(manifest(assets)).byteLength > bytes) { delete assets[key]; break; }
  }
  padLast(assets, bytes);
  const sources = Object.fromEntries(Object.keys(assets).map(name => [name, name.startsWith("fonts/") ? f.font : f.tiny]));
  return { assets, sources };
}
function exportCase(name: string, bytes: Uint8Array) {
  if (exported) { mkdirSync(exported, { recursive: true }); writeFileSync(join(exported, name + ".json"), bytes); }
}
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }); });

test("R1: readable 131000-byte legacy metadata falls back when pakFont expands it to 131266", () => {
  const f = fixture(); const { assets, sources } = ordinaryAssets(f, 131_000, false);
  const expanded = structuredClone(assets); expanded["fonts/watch-sans.ttf"]!.pakFont = f.alias;
  expect(encodePackageManifest(manifest(expanded)).byteLength).toBe(131_266);
  writeProjectManifest(f.output, base, sources);
  const old = readFileSync(join(f.output, "pod.manifest.json")); expect(old.byteLength).toBe(131_000);
  exportCase("r1-legacy", old); exportCase("r1-expanded", encodePackageManifest(manifest(expanded)));
  writeProjectManifest(f.output, base, sources, [f.alias]);
  const result = readFileSync(join(f.output, "pod.manifest.json"));
  expect(result).toEqual(old);
  expect(JSON.parse(result.toString()).assets["fonts/watch-sans.ttf"].pakFont).toBeUndefined();
  expect(sha(readFileSync(join(f.output, "pod-assets/fonts/watch-sans.ttf")))).toBe(f.alias.sha256);
  exportCase("r1-fallback", result);
});

test("a complete 128-KiB shared file fits, including its final newline", () => {
  const f = fixture(false); const { sources } = ordinaryAssets(f, PACKAGE_MANIFEST_MAX_BYTES, true);
  writeProjectManifest(f.output, base, sources, [f.alias]);
  const result = readFileSync(join(f.output, "pod.manifest.json"));
  expect(result.byteLength).toBe(PACKAGE_MANIFEST_MAX_BYTES);
  expect(result.at(-1)).toBe(10);
  expect(JSON.parse(result.toString()).assets["fonts/watch-sans.ttf"].pakFont).toEqual(f.alias);
  expect(existsSync(join(f.output, "pod-assets/fonts/watch-sans.ttf"))).toBe(false);
  exportCase("exact-limit", result);
});

test("R2: JSON at the build budget plus its newline restores the physical font", () => {
  const f = fixture(false); const { assets, sources } = ordinaryAssets(f, PACKAGE_MANIFEST_MAX_BYTES + 1, true);
  const encoded = encodePackageManifest(manifest(assets));
  expect(Buffer.byteLength(JSON.stringify(manifest(assets), null, 2))).toBe(PACKAGE_MANIFEST_MAX_BYTES);
  expect(encoded.byteLength).toBe(PACKAGE_MANIFEST_MAX_BYTES + 1);
  exportCase("newline-overflow", encoded);
  writeProjectManifest(f.output, base, sources, [f.alias]);
  const result = readFileSync(join(f.output, "pod.manifest.json"));
  expect(result.byteLength).toBeLessThanOrEqual(PACKAGE_MANIFEST_MAX_BYTES);
  expect(JSON.parse(result.toString()).assets["fonts/watch-sans.ttf"].pakFont).toBeUndefined();
  expect(readFileSync(join(f.output, "pod-assets/fonts/watch-sans.ttf"))).toEqual(f.bytes);
  exportCase("newline-fallback", result);
});

test("R2 appendix: exactly 2 MiB of JSON becomes 2097153 bytes with newline and is rejected before replacing assets", () => {
  const f = fixture(false); const shared = { ...f.fontMeta, pakFont: f.alias };
  let low = 1, high = 10_000;
  const aliases = (n: number) => Object.fromEntries(Array.from({ length: n }, (_, i) => [`fonts/f${i}.ttf`, shared]));
  while (low + 1 < high) {
    const mid = (low + high) >>> 1;
    if (encodePackageManifest(manifest(aliases(mid))).byteLength <= 2 * 1024 * 1024 + 1) low = mid;
    else high = mid;
  }
  const assets = aliases(low); padLast(assets, 2 * 1024 * 1024 + 1);
  expect(Buffer.byteLength(JSON.stringify(manifest(assets), null, 2))).toBe(2_097_152);
  exportCase("r2-appendix", encodePackageManifest(manifest(assets)));
  writeProjectManifest(f.output, base, { "fonts/old.ttf": f.font });
  const previous = readFileSync(join(f.output, "pod.manifest.json"));
  const sources = Object.fromEntries(Object.keys(assets).map(name => [name, f.font]));
  expect(() => writeProjectManifest(f.output, base, sources, [f.alias])).toThrow("131072 bytes including the final newline");
  expect(readFileSync(join(f.output, "pod.manifest.json"))).toEqual(previous);
  expect(readFileSync(join(f.output, "pod-assets/fonts/old.ttf"))).toEqual(f.bytes);
  expect(readdirSync(join(f.output, "pod-assets/fonts"))).toEqual(["old.ttf"]);
  expect(readdirSync(f.output).some(name => name.startsWith(".pod-assets-stage-"))).toBe(false);
});

test("empty assets use the same complete UTF-8 byte contract", () => {
  const f = fixture(false); const value = { ...base, description: "汉字𠮷 / Latin" };
  writeProjectManifest(f.output, value, {});
  const bytes = readFileSync(join(f.output, "pod.manifest.json"));
  expect(bytes).toEqual(encodePackageManifest(value));
  expect(bytes.byteLength).toBeGreaterThan(JSON.stringify(value, null, 2).length + 1);
  const previous = Buffer.from(bytes);
  expect(() => writeProjectManifest(f.output, { ...base, description: "汉".repeat(50_000) }, {})).toThrow("131072 bytes");
  expect(readFileSync(join(f.output, "pod.manifest.json"))).toEqual(previous);
});

test("R3: old 200-KiB metadata remains a runtime input but cannot be newly published", () => {
  const f = fixture(false);
  const value = { ...base, androidWorkManagerTakeover: {
    nativeAppId: "com.example.legacy", workerClasses: ["com.example.LegacyWorker"],
    uniqueWorkNames: ["legacy-periodic"], tags: [],
  } };
  const encoded = encodePackageManifest(value);
  const legacy = Buffer.concat([encoded.subarray(0, -1), Buffer.alloc(200 * 1024 - encoded.length, 32), Buffer.from("\n")]);
  expect(legacy.byteLength).toBe(204800);
  exportCase("r3-legacy-200k", legacy);
  const large = { ...value, padding: "" };
  large.padding = "x".repeat(200 * 1024 - encodePackageManifest(large).byteLength);
  expect(encodePackageManifest(large).byteLength).toBe(204800);
  writeProjectManifest(f.output, base, {});
  const previous = readFileSync(join(f.output, "pod.manifest.json"));
  expect(() => writeProjectManifest(f.output, large, {})).toThrow("131072 bytes including the final newline (204800 bytes)");
  expect(readFileSync(join(f.output, "pod.manifest.json"))).toEqual(previous);
});
