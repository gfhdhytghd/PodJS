#!/usr/bin/env bun
import { existsSync, readFileSync, writeFileSync, copyFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { createHash } from "node:crypto";
import { cac } from "cac";
import { POD_TARGETS, type PodTargetProfile } from "../../framework/src/targets.ts";
import { resolvePodProject, type ResolvedPodProject } from "./project.ts";
import { buildBackground } from "./background-build.ts";
import { syncProjectAssets } from "./project-assets.ts";
import { buildPakIntegrity } from "./pak-integrity.ts";

const ROOT = resolve(import.meta.dir, "../../..");
type Target = PodTargetProfile["id"];

function fail(message: string): never {
  console.error(`pod: ${message}`);
  process.exit(1);
}

function targetArg(positional?: string, option?: string): Target {
  const raw = option ?? positional;
  if (!raw || !(raw in POD_TARGETS)) {
    fail(`--target wants one of ${Object.keys(POD_TARGETS).join(", ")}`);
  }
  return raw as Target;
}

function run(cmd: string[], cwd = ROOT, env?: Record<string, string>): void {
  const child = Bun.spawnSync(cmd, {
    cwd,
    env: { ...process.env, ...env },
    stdout: "inherit",
    stderr: "inherit",
  });
  if (child.exitCode !== 0) fail(`${cmd.join(" ")} failed (${child.exitCode})`);
}

function capture(cmd: string[]): string | null {
  try {
    const result = Bun.spawnSync(cmd, { cwd: ROOT, stdout: "pipe", stderr: "pipe" });
    return result.exitCode === 0 ? result.stdout.toString().trim() : null;
  } catch {
    return null;
  }
}

function fnv1a64(bytes: Uint8Array): string {
  let hash = 0xcbf29ce484222325n;
  for (const byte of bytes) hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 0x100000001b3n);
  return hash.toString(16).padStart(16, "0");
}

function doctor(): void {
  const revision = capture(["git", "-C", "vendor/pocketjs", "rev-parse", "HEAD"]);
  const expected = "0a90bf904d835210e52a11ed275a86d0040b5086";
  const checks: [string, boolean, string][] = [
    ["PocketJS revision", revision === expected, revision ?? "missing"],
    ["Bun", !!capture(["bun", "--version"]), capture(["bun", "--version"]) ?? "missing"],
    ["Rust", !!capture(["rustc", "--version"]), capture(["rustc", "--version"]) ?? "missing"],
    ["ADB", !!capture(["adb", "version"]), capture(["adb", "version"])?.split("\n")[0] ?? "missing"],
    ["Android SDK", !!process.env.ANDROID_SDK_ROOT, process.env.ANDROID_SDK_ROOT ?? "missing"],
    ["Android NDK 27.2", !!process.env.ANDROID_SDK_ROOT && existsSync(join(process.env.ANDROID_SDK_ROOT, "ndk/27.2.12479018")), "required for ARMv7/ARM64"],
    ["Android CMake 3.22", !!process.env.ANDROID_SDK_ROOT && existsSync(join(process.env.ANDROID_SDK_ROOT, "cmake/3.22.1/bin/cmake")), "required by JNI renderer"],
    ["Xcode (external Mac allowed)", !!capture(["xcodebuild", "-version"]), capture(["xcodebuild", "-version"]) ?? "not on this host"],
    ["HDC (external DevEco host allowed)", !!capture(["hdc", "version"]), capture(["hdc", "version"]) ?? "not on this host"],
  ];
  for (const [name, ok, detail] of checks) {
    console.log(`${ok ? "OK " : "-- "}${name}: ${detail}`);
  }
  const devices = capture(["adb", "devices", "-l"]);
  if (devices) console.log(devices);
  if (revision !== expected) process.exitCode = 1;
}

async function build(target: Target, project?: ResolvedPodProject): Promise<ResolvedPodProject | undefined> {
  const profile = POD_TARGETS[target];
  if(project?.config.resources?.glyphCache&&target!=="android-watch"&&target!=="wearos-watch") fail("runtime glyph pages currently require android-watch or wearos-watch");
  if (project) {
    const unsupported = project.config.capabilities.filter(capability => !profile.capabilities.includes(capability as never));
    if (unsupported.length) fail(`project requires capabilities unavailable on ${target}: ${unsupported.join(", ")}`);
  }
  const glyphOption=project?.config.resources?.glyphCache;
  let glyphCache=glyphOption;
  if(glyphOption?.warmCharsFile&&project) {
    const path=resolve(project.root,glyphOption.warmCharsFile);
    if(!existsSync(path))fail("glyphCache.warmCharsFile does not exist");
    const chars=readFileSync(path,"utf8");
    if(chars.length>8192)fail("glyphCache.warmCharsFile exceeds 8192 code units");
    glyphCache={...glyphOption,warmChars:glyphOption.warmChars+chars};
  }
  const density = target === "android-watch" || target === "wearos-watch" ? 2 : 2;
  const out = project ? join(project.output, target) : join(ROOT, "dist", target);
  const app = project?.entry ?? resolve(ROOT, "apps/gallery/src/main.tsx");
  run([
    "bun", "vendor/pocketjs/tools/build.ts", app,
    "--framework=solid", `--density=${density}`, "--hz=60",
    ...(target === "android-watch" || target === "wearos-watch" ? ["--minify"] : []),
    ...(project?.fontSlots.length ? [`--font-slots-json=${JSON.stringify(project.fontSlots)}`] : []),
    ...(glyphCache ? [`--glyph-cache-json=${JSON.stringify(glyphCache)}`] : []),
    ...(project?.extraChars ? [`--extra-chars=${project.extraChars}`] : []),
    `--font-regular=${project?.fontRegular ?? join(ROOT, "assets/fonts/NotoSansCJKSC-Regular-subset.ttf")}`,
    `--font-bold=${project?.fontBold ?? join(ROOT, "assets/fonts/NotoSansCJKSC-Bold-subset.ttf")}`,
    `--outdir=${out}`, `--project-root=${project?.root ?? ROOT}`,
  ]);
  const output = basename(app).replace(/\.tsx?$/, "");
  const js = join(out, `${output}.js`);
  const pak = join(out, `${output}.pak`);
  if (!existsSync(js) || !existsSync(pak)) fail(`PocketJS did not produce ${js} and ${pak}`);
  if (output !== "main") {
    copyFileSync(js, join(out, "main.js"));
    copyFileSync(pak, join(out, "main.pak"));
  }
  const bytecodePath = join(out, "main.qbc");
  const bytecodeEnabled = target === "android-watch" || target === "wearos-watch";
  if (bytecodeEnabled) {
    run([
      "cargo", "run", "--quiet", "--manifest-path", join(ROOT, "Cargo.toml"),
      "-p", "podjs-runtime", "--example", "compile-bytecode", "--", js, bytecodePath,
      ...(project?.config.bytecode?.stripSource ? ["--strip-source"] : []),
    ]);
  }
  const background = await buildBackground(project?.background ?? {}, out);
  const assets = syncProjectAssets(out, project?.assets ?? {});
  const bundleHash = fnv1a64(readFileSync(js));
  const pakBytes = readFileSync(pak);
  const manifest = {
    schema: 1,
    target: profile.id,
    hostAbi: project?.config.resources?.glyphCache ? 3 : profile.hostAbi,
    logicalViewport: profile.logicalViewport,
    renderer: profile.renderer,
    capabilities: profile.capabilities,
    pocketjsRevision: capture(["git", "-C", join(ROOT, "vendor/pocketjs"), "rev-parse", "HEAD"]) ?? fail("cannot resolve PocketJS revision"),
    bundleHash,
    pakHash: fnv1a64(pakBytes),
    pakIntegrity: buildPakIntegrity(pakBytes),
    ...(bytecodeEnabled ? { bytecode: {
      file: "main.qbc",
      format: "quickjs-rquickjs-0.12.2-bc26-le-v1",
      rquickjsVersion: "0.12.2",
      quickjsBytecodeVersion: 26,
      endianness: "little",
      sourceHash: bundleHash,
      sourceIncluded: !project?.config.bytecode?.stripSource,
      debugIncluded: true,
      sha256: createHash("sha256").update(readFileSync(bytecodePath)).digest("hex"),
      bytes: readFileSync(bytecodePath).byteLength,
    }} : {}),
    background,
    backgroundServices: project?.config.backgroundServices ?? [],
    ...(Object.keys(assets).length ? { assets } : {}),
  };
  writeFileSync(join(out, "pod.manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`pod: built ${profile.id} -> ${out}`);
  return project;
}

function test(): void {
  run(["bun", "run", "test"]);
}

async function packageTarget(target: Target, project?: ResolvedPodProject): Promise<void> {
  await build(target, project);
  if (target === "android-watch" || target === "wearos-watch") {
    run(["bash", "scripts/build-android-runtime.sh"]);
    const app = target === "android-watch" ? ":androidApp" : ":wearApp";
    const gradleArgs = project ? [`-PpodDistDir=${join(project.output, target)}`, `-PpodAppId=${project.config.appId}`, `-PpodAppName=${project.config.name}`, `-PpodVersionName=${project.config.versionName}`, `-PpodVersionCode=${project.config.versionCode}`,
      ...(project.androidResources ? [`-PpodResDir=${project.androidResources}`] : []),
      ...(project.androidTheme ? [`-PpodAppTheme=${project.androidTheme}`] : [])] : [];
    run(["./gradlew", ...gradleArgs, `${app}:assembleDebug`, `${app}:bundleRelease`, ":runtime:assembleRelease"], join(ROOT, "platforms/android"));
  } else if (target === "watchos-watch") {
    if (!capture(["xcodebuild", "-version"])) fail("watchOS packaging must run on the configured Mac");
    run([
      "xcodebuild", "-project", "platforms/watchos/PodJSWatchApp.xcodeproj",
      "-scheme", "PodJSWatchApp", "-destination", "generic/platform=watchOS",
      "-derivedDataPath", ".pod/watchos", "CODE_SIGNING_ALLOWED=NO", "build",
    ]);
  } else {
    if (process.platform === "win32") {
      run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build-harmony-runtime.ps1"]);
    } else if (process.platform === "darwin") {
      run(["bash", "scripts/build-harmony-runtime.sh"]);
    } else {
      fail("HarmonyOS packaging requires DevEco Studio on Windows or macOS");
    }
  }
}

function projectOption(path?: string): ResolvedPodProject | undefined {
  if (!path) return undefined;
  try {
    return resolvePodProject(path, POD_TARGETS, {
      fontRegular: join(ROOT, "assets/fonts/NotoSansCJKSC-Regular-subset.ttf"),
      fontBold: join(ROOT, "assets/fonts/NotoSansCJKSC-Bold-subset.ttf"),
      outputRoot: join(ROOT, "dist/projects"),
    });
  } catch (error) { fail(error instanceof Error ? error.message : String(error)); }
}

interface TargetOptions {
  target?: string;
  project?: string;
}

const cli = cac("pod");

cli
  .command("doctor", "Check the local PodJS development environment")
  .action(doctor);

cli
  .command("build [target]", "Build an app for a watch target")
  .option("--target <target>", "Watch target (can also be passed positionally)")
  .option("--project <path>", "Project directory or pod.config.json")
  .action(async (target: string | undefined, options: TargetOptions) => {
    await build(targetArg(target, options.target), projectOption(options.project));
  });

cli
  .command("test", "Run the framework and Rust workspace tests")
  .action(test);

cli
  .command("package [target]", "Build and package a watch target")
  .option("--target <target>", "Watch target (can also be passed positionally)")
  .option("--project <path>", "Project directory or pod.config.json")
  .action(async (target: string | undefined, options: TargetOptions) => {
    await packageTarget(targetArg(target, options.target), projectOption(options.project));
  });

cli.help();
cli.version("0.1.0");
cli.addEventListener("command:*", event => {
  fail(`unknown command: ${(event as CustomEvent<string>).detail}`);
});

if (process.argv.length === 2) {
  doctor();
} else {
  cli.parse(process.argv, { run: false });
  await cli.runMatchedCommand();

}
