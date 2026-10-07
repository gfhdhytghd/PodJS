import { existsSync, readdirSync, realpathSync, rmSync, symlinkSync, unlinkSync, lstatSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const modules = join(root, "node_modules/.bun");
for (const name of existsSync(modules) ? readdirSync(modules) : []) {
  if (!name.startsWith("@pocketjs+framework@file+")) continue;
  const link = join(modules, name, "node_modules/@pocketjs/framework");
  const target = join(root, "vendor/pocketjs");
  if (existsSync(link) && realpathSync(link) === realpathSync(target)) continue;
  if (lstatSync(link).isSymbolicLink()) unlinkSync(link);
  else rmSync(link, { recursive: true });
  symlinkSync(target, link, process.platform === "win32" ? "junction" : "dir");
}
