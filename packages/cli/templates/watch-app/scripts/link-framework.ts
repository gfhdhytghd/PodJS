import { existsSync, lstatSync, mkdirSync, realpathSync, rmSync, symlinkSync, unlinkSync } from "node:fs";
import { dirname, resolve } from "node:path";
import frameworkPath from "./framework.json";

// Bun file dependencies can be copies; source-relative imports and Solid's
// owner registry require the checkout's single runtime instance.
const root = resolve(import.meta.dir, frameworkPath);
const app = resolve(import.meta.dir, "..");
for (const [name, source] of [
  ["@podjs/framework", "packages/framework"],
  ["@pocketjs/framework", "vendor/pocketjs"],
  ["solid-js", "node_modules/solid-js"],
]) {
  const target = resolve(root, source), link = resolve(app, "node_modules", name);
  if (!existsSync(target)) throw new Error(`Install framework dependencies first: ${name}`);
  if (existsSync(link) && realpathSync(link) === realpathSync(target)) continue;
  try {
    if (lstatSync(link).isSymbolicLink()) unlinkSync(link);
    else rmSync(link, { recursive: true });
  } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
  mkdirSync(dirname(link), { recursive: true });
  symlinkSync(target, link, process.platform === "win32" ? "junction" : "dir");
}
