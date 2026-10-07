import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { relative, resolve } from "node:path";

const FRAMEWORK_ROOT = resolve(import.meta.dir, "../../..");
const TEMPLATE = resolve(import.meta.dir, "../templates/watch-app");
const APP_ID = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;

/** Creates source only. Never installs packages, signs, or overwrites a directory. */
export function createProject(directory: string, options: { appId: string; name: string }): string {
  if (!APP_ID.test(options.appId ?? "")) throw new Error("--app-id must be a valid Android application id");
  if (typeof options.name !== "string" || !options.name.trim()) throw new Error("--name must be a non-empty name");
  const root = resolve(directory);
  if (existsSync(root)) throw new Error(`destination already exists: ${root}`);
  const fromRoot = (path: string) => {
    const value = relative(root, resolve(FRAMEWORK_ROOT, path)).replaceAll("\\", "/");
    return value.startsWith(".") ? value : `./${value}`;
  };
  mkdirSync(root, { recursive: true });
  cpSync(TEMPLATE, root, { recursive: true });
  const json = (path: string, value: unknown) => writeFileSync(resolve(root, path), JSON.stringify(value, null, 2) + "\n");
  json("pod.config.json", {
    entry: "src/main.tsx", appId: options.appId, name: options.name,
    versionName: "1.0.0", versionCode: 1,
    capabilities: ["input.touch", "input.back", "data.kv"],
  });
  const lock = Bun.JSONC.parse(readFileSync(resolve(FRAMEWORK_ROOT, "bun.lock"), "utf8")) as {
    workspaces: Record<string, unknown>; packages: Record<string, unknown[]>; overrides?: Record<string, string>;
  };
  const lockedVersion = (name: string) => String(lock.packages[name]![0]).slice(name.length + 1);
  const pocketDependency = `file:${fromRoot("vendor/pocketjs")}`;
  const manifest = {
    name: options.appId.toLowerCase(), private: true, version: "1.0.0", type: "module",
    scripts: { postinstall: "bun scripts/link-framework.ts", pod: "bun scripts/pod.ts", build: "bun scripts/pod.ts build --target=android-watch",
      package: "bun scripts/pod.ts package --target=android-watch", typecheck: "tsc --noEmit", test: "bun --conditions=browser test ./tests" },
    dependencies: { "@podjs/framework": `file:${fromRoot("packages/framework")}`,
      "@pocketjs/framework": pocketDependency, "solid-js": lockedVersion("solid-js") },
    devDependencies: { "@types/bun": lockedVersion("@types/bun"), typescript: lockedVersion("typescript") },
    overrides: { "@pocketjs/framework": pocketDependency },
  };
  json("package.json", manifest);
  json("scripts/framework.json", relative(resolve(root, "scripts"), FRAMEWORK_ROOT).replaceAll("\\", "/"));
  // Seed the checkout's verified registry resolutions so warm-cache installs
  // need no network. Credentials and package-local build artifacts are excluded.
  const pocket = lock.packages["@podjs/framework/@pocketjs/framework"]!;
  pocket[0] = `@pocketjs/framework@${pocketDependency}`;
  for (const [key, record] of Object.entries(lock.packages)) {
    if (String(record[0]).includes("@workspace:") || key.startsWith("@podjs/")) delete lock.packages[key];
  }
  const framework = JSON.parse(readFileSync(resolve(FRAMEWORK_ROOT, "packages/framework/package.json"), "utf8"));
  lock.packages["@podjs/framework"] = [`@podjs/framework@${manifest.dependencies["@podjs/framework"]}`, { dependencies: framework.dependencies }];
  lock.packages["@pocketjs/framework"] = pocket;
  lock.workspaces = { "": { name: manifest.name, version: manifest.version, dependencies: manifest.dependencies, devDependencies: manifest.devDependencies } };
  lock.overrides = manifest.overrides;
  json("bun.lock", lock);
  // Literal module specifiers handle spaces and quotes in paths without shell interpolation.
  writeFileSync(resolve(root, "scripts/pod.ts"),
    'import {resolve} from "node:path";\nprocess.argv.push(`--project=${resolve(import.meta.dir, "..")}`);\n' +
    `const cli = ${JSON.stringify(relative(resolve(root, "scripts"), resolve(FRAMEWORK_ROOT, "packages/cli/src/cli.ts")).replaceAll("\\", "/"))};\nawait import(cli);\nexport {};\n`);
  console.log(`pod: created ${root}\nRun in that directory: bun install, bun run typecheck, bun run build`);
  return root;
}
