import { afterEach, describe, expect, spyOn, test } from "bun:test";
import { createHash } from "node:crypto";
import * as fs from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { resolveProjectAssets, syncProjectAssets } from "../packages/cli/src/project-assets.ts";

const temporaryDirectories: string[] = [];
const spies: Array<{ mockRestore(): void }> = [];

function fixture(): { root: string; output: string; source: string } {
  const root = fs.mkdtempSync(join(tmpdir(), "podjs-assets-"));
  temporaryDirectories.push(root);
  const source = join(root, "font.ttf");
  fs.writeFileSync(source, Buffer.from([0, 255, 1, 2, 0, 128]));
  return { root, output: join(root, "output"), source };
}

function digest(bytes: Uint8Array | string): string { return createHash("sha256").update(bytes).digest("hex"); }
function stages(output: string): string[] { return fs.readdirSync(output).filter(name => name.startsWith(".pod-assets-stage-")); }
function seed(output: string): string {
  const old = join(output, "pod-assets", "old.bin");
  fs.mkdirSync(join(output, "pod-assets"), { recursive: true });
  fs.writeFileSync(old, "original asset bytes");
  fs.writeFileSync(join(output, "application.pod"), "unrelated artifact");
  return old;
}
function assertOld(output: string, old: string): void {
  expect(fs.readFileSync(old, "utf8")).toBe("original asset bytes");
  expect(fs.readdirSync(join(output, "pod-assets"))).toEqual(["old.bin"]);
  expect(fs.readFileSync(join(output, "application.pod"), "utf8")).toBe("unrelated artifact");
  expect(stages(output)).toEqual([]);
}

afterEach(() => {
  for (const spy of spies.splice(0)) spy.mockRestore();
  for (const root of temporaryDirectories.splice(0)) fs.rmSync(root, { recursive: true, force: true });
});

describe("project asset configuration", () => {
  test("undefined stays empty and relative or absolute sources retain their config spelling", () => {
    const { root, source } = fixture();
    expect(resolveProjectAssets(undefined, root)).toEqual({ config: {}, resolved: {} });
    const mapping = { "fonts/body.ttf": "font.ttf", "data/current.bin": source };
    expect(resolveProjectAssets(mapping, root)).toEqual({
      config: mapping, resolved: { "fonts/body.ttf": source, "data/current.bin": source },
    });
  });

  test("requires a mapping of non-empty paths to regular source files", () => {
    const { root } = fixture();
    for (const value of [null, [], "font.ttf", 1, new Map(), new Date(), { good: 1 }, { good: "" }, { good: " " }, { good: "a\0b" }, { good: "missing" }, { good: "." }]) {
      expect(() => resolveProjectAssets(value, root)).toThrow("resources.assets");
    }
  });

  test("rejects traversal, separators, control characters and platform-dependent names", () => {
    const { root } = fixture();
    const invalid = ["", ".", "..", "../a", "a/../b", "/a", "a/", "a//b", "a\\b", "C:/a", "a\0b", "a\nb", "a ", "é", ".hidden", "a.", "a./b", "CON", "con.txt", "a/NUL.bin", "Aux.data", "PRN/a", "COM9.txt", "Lpt1", "a".repeat(256), ["a".repeat(200), "b".repeat(200), "c".repeat(111)].join("/")];
    for (const name of invalid) expect(() => resolveProjectAssets({ [name]: "font.ttf" }, root)).toThrow("unsafe logical name");
    const longest = ["a".repeat(255), "b".repeat(254), "c"].join("/");
    expect(longest.length).toBe(512);
    expect(Object.keys(resolveProjectAssets({ [longest]: "font.ttf", "conifer/com0.txt": "font.ttf", "LPT10.bin": "font.ttf", "data-v1.0/body_font.ttf": "font.ttf" }, root).resolved)).toHaveLength(4);
  });

  test("rejects case aliases and file-directory prefixes regardless of insertion order", () => {
    const { root } = fixture();
    for (const names of [["Font.ttf", "font.ttf"], ["Fonts/a", "fonts/b"], ["font", "font/body"], ["FONT", "font/body"], ["a/B", "a/b/C"]]) {
      for (const ordered of [names, [...names].reverse()]) {
        expect(() => resolveProjectAssets(Object.fromEntries(ordered.map(name => [name, "font.ttf"])), root)).toThrow("resources.assets");
      }
    }
  });
});

describe("project asset synchronization", () => {
  test("empty legacy projects do not create even the output directory", () => {
    const { output } = fixture();
    expect(syncProjectAssets(output, {})).toEqual({});
    expect(fs.existsSync(output)).toBe(false);
  });

  test("an empty legacy project may observe a symlink output without touching it", () => {
    const { root, output } = fixture();
    const external = join(root, "external");
    fs.mkdirSync(external);
    fs.writeFileSync(join(external, "unrelated.bin"), "keep");
    fs.symlinkSync(external, output);
    expect(syncProjectAssets(output, {})).toEqual({});
    expect(syncProjectAssets(join(output, "absent-child"), {})).toEqual({});
    expect(fs.readdirSync(external)).toEqual(["unrelated.bin"]);
    fs.mkdirSync(join(external, "pod-assets"));
    expect(() => syncProjectAssets(output, {})).toThrow("symlink");
  });

  test("publishes exact binary bytes and metadata, removes stale assets, and preserves source and unrelated outputs", () => {
    const { root, output, source } = fixture();
    seed(output);
    const bytes = Buffer.alloc(200_001);
    for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 37) % 256;
    fs.writeFileSync(source, bytes);
    const before = digest(fs.readFileSync(source));
    const map = { "fonts/body.ttf": source, "binary.dat": source };
    const metadata = syncProjectAssets(output, map);
    expect(Object.keys(metadata).sort()).toEqual(["binary.dat", "fonts/body.ttf"]);
    for (const name of Object.keys(map)) {
      const copied = fs.readFileSync(join(output, "pod-assets", name));
      expect(copied.equals(bytes)).toBe(true);
      expect(metadata[name]).toEqual({ size: copied.length, sha256: digest(copied) });
    }
    expect(digest(fs.readFileSync(source))).toBe(before);
    expect(fs.existsSync(join(output, "pod-assets", "old.bin"))).toBe(false);
    expect(fs.readFileSync(join(output, "application.pod"), "utf8")).toBe("unrelated artifact");
    fs.writeFileSync(join(root, "new.bin"), "new bytes");
    expect(syncProjectAssets(output, { replacement: join(root, "new.bin") })).toEqual({ replacement: { size: 9, sha256: digest("new bytes") } });
    expect(fs.readdirSync(join(output, "pod-assets"))).toEqual(["replacement"]);
    expect(syncProjectAssets(output, {})).toEqual({});
    expect(fs.readdirSync(join(output, "pod-assets"))).toEqual([]);
    expect(stages(output)).toEqual([]);
  });

  test("fingerprints the staged copy even if its source changes immediately after copying", () => {
    const { output, source } = fixture();
    const original = fs.readFileSync(source);
    const copy = fs.copyFileSync;
    spies.push(spyOn(fs, "copyFileSync").mockImplementation((from, to, mode) => {
      copy(from, to, mode);
      fs.writeFileSync(source, "changed after copying");
    }));
    const metadata = syncProjectAssets(output, { body: source });
    expect(fs.readFileSync(join(output, "pod-assets", "body")).equals(original)).toBe(true);
    expect(metadata.body).toEqual({ size: original.length, sha256: digest(original) });
  });

  test("missing or directory sources fail before changing the previous tree", () => {
    const { root, output, source } = fixture();
    const old = seed(output);
    for (const broken of [join(root, "missing"), root]) {
      expect(() => syncProjectAssets(output, { good: source, broken })).toThrow("regular file");
      assertOld(output, old);
    }
  });

  test("a mid-copy failure preserves the old tree and removes its own staging directory", () => {
    const { output, source } = fixture();
    const old = seed(output);
    const before = digest(fs.readFileSync(source));
    const copy = fs.copyFileSync;
    let copied = 0;
    spies.push(spyOn(fs, "copyFileSync").mockImplementation((from, to, mode) => {
      if (copied++ === 1) throw new Error("injected copy failure");
      copy(from, to, mode);
    }));
    expect(() => syncProjectAssets(output, { first: source, second: source })).toThrow("injected copy failure");
    expect(copied).toBe(2);
    assertOld(output, old);
    expect(digest(fs.readFileSync(source))).toBe(before);
  });

  test("a publication rename failure restores the previous tree", () => {
    const { output, source } = fixture();
    const old = seed(output);
    const rename = fs.renameSync;
    spies.push(spyOn(fs, "renameSync").mockImplementation((from, to) => {
      if (basename(String(from)) === "next") throw new Error("injected publication failure");
      rename(from, to);
    }));
    expect(() => syncProjectAssets(output, { replacement: source })).toThrow("injected publication failure");
    assertOld(output, old);
  });

  test("if rollback also fails, the previous tree remains in the reported recovery directory", () => {
    const { output, source } = fixture();
    seed(output);
    const rename = fs.renameSync;
    spies.push(spyOn(fs, "renameSync").mockImplementation((from, to) => {
      if (["next", "previous"].includes(basename(String(from)))) throw new Error(`injected ${basename(String(from))} failure`);
      rename(from, to);
    }));
    let failure: unknown;
    try { syncProjectAssets(output, { replacement: source }); } catch (error) { failure = error; }
    expect(failure).toBeInstanceOf(AggregateError);
    const remaining = stages(output);
    expect(remaining).toHaveLength(1);
    const recovery = join(output, remaining[0]!, "previous");
    expect((failure as Error).message).toContain(recovery);
    expect((failure as AggregateError).errors.map(error => error.message)).toEqual(["injected next failure", "injected previous failure"]);
    expect(fs.readFileSync(join(recovery, "old.bin"), "utf8")).toBe("original asset bytes");
    expect(fs.readFileSync(join(output, "application.pod"), "utf8")).toBe("unrelated artifact");
  });

  test("a post-commit cleanup failure reports the commit and keeps the new valid tree", () => {
    const { output, source } = fixture();
    seed(output);
    const remove = fs.rmSync;
    spies.push(spyOn(fs, "rmSync").mockImplementation((path, options) => {
      if (basename(String(path)).startsWith(".pod-assets-stage-")) throw new Error("injected cleanup failure");
      remove(path, options);
    }));
    expect(() => syncProjectAssets(output, { replacement: source })).toThrow("assets were committed");
    expect(fs.readFileSync(join(output, "pod-assets", "replacement")).equals(fs.readFileSync(source))).toBe(true);
    expect(stages(output)).toHaveLength(1);
  });

  test("rejects sources in the previous tree and symlink aliases without deleting source bytes", () => {
    const { root, output, source } = fixture();
    const old = seed(output);
    const alias = join(root, "alias.bin");
    fs.symlinkSync(old, alias);
    const directoryAlias = join(root, "alias-directory");
    fs.symlinkSync(join(output, "pod-assets"), directoryAlias);
    for (const path of [old, alias, join(directoryAlias, "old.bin")]) {
      expect(() => syncProjectAssets(output, { asset: path })).toThrow("source must not be inside");
      assertOld(output, old);
    }
    const sibling = join(output, "pod-assets-old");
    fs.mkdirSync(sibling);
    fs.copyFileSync(source, join(sibling, "body"));
    expect(syncProjectAssets(output, { asset: join(sibling, "body") }).asset.sha256).toBe(digest(fs.readFileSync(source)));
    expect(fs.readFileSync(join(sibling, "body")).equals(fs.readFileSync(source))).toBe(true);
  });

  test("allows a symlink source outside the output and preserves its target", () => {
    const { root, output, source } = fixture();
    const alias = join(root, "alias.bin");
    fs.symlinkSync(source, alias);
    const expected = fs.readFileSync(source);
    expect(syncProjectAssets(output, { asset: alias }).asset).toEqual({ size: expected.length, sha256: digest(expected) });
    expect(fs.lstatSync(alias).isSymbolicLink()).toBe(true);
    expect(fs.readFileSync(source).equals(expected)).toBe(true);
  });

  test("rejects symlink output ancestors, target trees, and nested or dangling target links", () => {
    for (const mode of ["ancestor", "target", "dangling-target", "nested", "dangling-nested"]) {
      const { root, output, source } = fixture();
      const external = join(root, "external");
      fs.mkdirSync(external);
      fs.writeFileSync(join(external, "untouched"), "protected");
      let destination = output;
      if (mode === "ancestor") {
        fs.symlinkSync(external, output);
        destination = join(output, "child");
      } else if (mode === "target" || mode === "dangling-target") {
        fs.mkdirSync(output);
        fs.symlinkSync(mode === "target" ? external : join(root, "absent"), join(output, "pod-assets"));
      } else {
        seed(output);
        fs.symlinkSync(mode === "nested" ? external : join(root, "absent"), join(output, "pod-assets", "link"));
      }
      expect(() => syncProjectAssets(destination, { body: source })).toThrow("symlink");
      expect(fs.readFileSync(join(external, "untouched"), "utf8")).toBe("protected");
      expect(fs.readdirSync(external)).toEqual(["untouched"]);
      if (mode !== "ancestor") expect(stages(output)).toEqual([]);
    }
  });

  test("rejects an existing file at the managed tree path without replacing it", () => {
    const { output, source } = fixture();
    fs.mkdirSync(output);
    fs.writeFileSync(join(output, "pod-assets"), "keep this file");
    expect(() => syncProjectAssets(output, { body: source })).toThrow("must be a directory");
    expect(fs.readFileSync(join(output, "pod-assets"), "utf8")).toBe("keep this file");
    expect(stages(output)).toEqual([]);
  });
});
