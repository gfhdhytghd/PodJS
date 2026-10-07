import { mkdtempSync, readFileSync, realpathSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
const root = resolve(import.meta.dir, "..");
const temp = mkdtempSync(join(tmpdir(), "pod-scaffold-"));
const app = join(temp, "Round app with spaces");
const appId = `dev.podjs.scaffold.p${process.pid}`;
function run(command: string[], cwd = root) {
  const result = Bun.spawnSync(command, {cwd, stdout: "inherit", stderr: "inherit"});
  if (result.exitCode !== 0) throw new Error(`Scaffold verification failed (${result.exitCode}): ${command.join(" ")}`);
}
function verify(project: string) {
  run(["bun", "install", "--offline"], project);
  for (const [name, path] of [["@pocketjs/framework", "vendor/pocketjs"], ["@podjs/framework", "packages/framework"], ["solid-js", null]] as const) {
    const expected = path ? join(root, path) : dirname(Bun.resolveSync("solid-js/package.json", join(root, "packages/framework")));
    if (realpathSync(join(project, "node_modules", name)) !== realpathSync(expected)) throw new Error(`Runtime instance mismatch: ${name}`);
  }
  run(["bun", "run", "typecheck"], project);
  run(["bun", "run", "test"], project);
  run(["bun", "run", "build"], project);
  const config = JSON.parse(readFileSync(join(project, "pod.config.json"), "utf8"));
  const output = join(root, "dist/projects", config.appId, "android-watch");
  for (const name of ["main.js", "main.pak", "main.qbc", "pod.manifest.json"]) {
    if (!readFileSync(join(output, name)).length) throw new Error(`Missing build output: ${name}`);
  }
}
try {
  run(["bun", "packages/cli/src/cli.ts", "create", app, "--app-id", appId, "--name", "Round Demo"]);
  verify(app);
  verify(join(root, "examples/watch-notebook"));
  console.log("Scaffold + notebook offline install/typecheck/test/android-watch build: PASS");
} finally {
  rmSync(temp, {recursive: true, force: true});
  rmSync(join(root, "dist/projects", appId), {recursive: true, force: true});
}
