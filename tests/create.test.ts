import { expect, test } from "bun:test";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PodCapability } from "../packages/framework/src/targets.ts";
import { createProject } from "../packages/cli/src/create.ts";

test("create validates inputs and refuses existing user files", () => {
  const root = mkdtempSync(join(tmpdir(), "pod-create-test-"));
  try {
    writeFileSync(join(root, "keep.txt"), "keep");
    expect(() => createProject(root, {appId: "dev.example.app", name: "App"})).toThrow("already exists");
    expect(readFileSync(join(root, "keep.txt"), "utf8")).toBe("keep");
    expect(() => createProject(join(root, "new"), {appId: "bad/id", name: "App"})).toThrow("app-id");
    expect(() => createProject(join(root, "new"), {appId: "dev.example.app", name: " "})).toThrow("name");
  } finally { rmSync(root, { recursive: true }); }
});

test("CLI supports names and paths with spaces without adding signing or takeover", () => {
  const root = mkdtempSync(join(tmpdir(), "pod-create-test-"));
  const app = join(root, "my watch app");
  try {
    const result = Bun.spawnSync(["bun", resolve(import.meta.dir, "../packages/cli/src/cli.ts"), "create", app,
      "--app-id", "dev.example.app", "--name", 'A "quoted" watch'], {stdout: "pipe", stderr: "pipe"});
    expect(result.exitCode).toBe(0);
    const config = JSON.parse(readFileSync(join(app, "pod.config.json"), "utf8"));
    expect(config.name).toBe('A "quoted" watch');
    expect(config.android).toBeUndefined();
    expect(config.capabilities).not.toContain(PodCapability.Takeover);
    const pkg = JSON.parse(readFileSync(join(app, "package.json"), "utf8"));
    expect(pkg.dependencies["@podjs/framework"]).toStartWith("file:");
    expect(pkg.scripts.postinstall).toBe("bun scripts/link-framework.ts");
  } finally { rmSync(root, { recursive: true }); }
});

test("each framework export has a module overview row", () => {
  const pkg = JSON.parse(readFileSync(resolve(import.meta.dir, "../packages/framework/package.json"), "utf8"));
  const docs = readFileSync(resolve(import.meta.dir, "../docs/getting-started.md"), "utf8");
  for (const key of Object.keys(pkg.exports)) {
    const name = "@podjs/framework" + (key === "." ? "" : key.slice(1));
    expect(docs).toContain(`| \`${name}\` |`);
  }
});
