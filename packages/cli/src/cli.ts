#!/usr/bin/env bun
import { createProject } from "./create.ts";
import {writeDebugExternal} from './debug-external.ts';
import { existsSync, readFileSync, writeFileSync, copyFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { createHash } from "node:crypto";
import { cac } from "cac";
import { POD_TARGETS, type PodTargetProfile } from "../../framework/src/targets.ts";
import { resolvePodProject, type ResolvedPodProject } from "./project.ts";
import { buildBackground } from "./background-build.ts";
import { syncProjectAssets } from "./project-assets.ts";
import { androidPackagePlan, androidPackageOptions, signAndroidApk, rotateAndroidSigning, type RotateOptions } from "./android-signing.ts";
import { buildPakIntegrity } from "./pak-integrity.ts";

import { findUnknownStyleProps } from "./style-props-check.ts";
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
  // Unknown inline style keys throw inside the renderer and abort every frame on device.
  const styleRoots=[join(ROOT,"packages/framework/src"),...(project?[join(project.root,"src")]:[])].filter(path=>existsSync(path));
  const unknownStyles=findUnknownStyleProps(styleRoots);
  if(unknownStyles.length) fail(`unknown inline style props (see PocketJS spec PROP):\n${unknownStyles.map(item=>`  ${item.file}:${item.line} ${item.key}`).join("\n")}`);
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
    ...(project?.config.resources?.preloadFontSlots?.length ? { preloadFontSlots: project.config.resources.preloadFontSlots } : {}),
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
    ...(project?.config.android?.workManagerTakeover ? { androidWorkManagerTakeover: project.config.android.workManagerTakeover } : {}),
    ...(Object.keys(assets).length ? { assets } : {}),
  };
  writeFileSync(join(out, "pod.manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`pod: built ${profile.id} -> ${out}`);
  return project;
}

function test(): void {
  run(["bun", "run", "test"]);
}

async function packageTarget(target: Target, project?: ResolvedPodProject, release = false, debuggable = false, versionCode?: unknown): Promise<void> {
  if (release && target !== "android-watch" && target !== "wearos-watch") fail("--release currently requires an Android target");
  if ((debuggable || versionCode !== undefined) && target !== "android-watch" && target !== "wearos-watch") fail("--debuggable and --version-code require an Android target");
  const overrides = androidPackageOptions(release, project?.androidSigning, debuggable, versionCode);
  // Change only the resolved in-memory configuration, before generating build metadata.
  if (project && overrides.versionCode !== undefined) project.config.versionCode = overrides.versionCode;
  if (debuggable) console.warn("pod: verification-only debuggable release APK; DO NOT PUBLISH");
  await build(target, project);
  if (target === "android-watch" || target === "wearos-watch") {
    run(["bash", "scripts/build-android-runtime.sh"]);
    const app = target === "android-watch" ? ":androidApp" : ":wearApp";
    const gradleArgs = project ? [`-PpodDistDir=${join(project.output, target)}`, `-PpodAppId=${project.config.appId}`, `-PpodAppName=${project.config.name}`, `-PpodVersionName=${project.config.versionName}`, `-PpodVersionCode=${project.config.versionCode}`,
      ...(project.androidResources ? [`-PpodResDir=${project.androidResources}`] : []),
      ...(project.androidTheme ? [`-PpodAppTheme=${project.androidTheme}`] : []),
      `-PpodCleartextTraffic=${project.androidCleartextTraffic === true}`,
      `-PpodTakeoverSourceVersionCode=${project.config.android?.takeover?.sourceVersionCode ?? 0}`,
      `-PpodTakeoverPurgeBackupAfter=${project.config.android?.takeover?.purgeBackupAfter ?? 0}`] : [];
    if(project){
      const debugDir=join(project.output,"debug-external",target);
      writeDebugExternal(debugDir,project.config.android?.debugExternal,target==="android-watch"?"dev.podjs.androidwatch.MainActivity":"dev.podjs.wear.MainActivity");
      gradleArgs.push(`-PpodDebugExternalDir=${debugDir}`);
    }
    gradleArgs.push(`-PpodReleaseDebuggable=${overrides.debuggable}`);
    if (!project && overrides.versionCode !== undefined) gradleArgs.push(`-PpodVersionCode=${overrides.versionCode}`);
    const plan = androidPackagePlan(release, project?.androidSigning);
    run(["./gradlew", ...gradleArgs, ...plan.tasks.map(task => `${app}:${task}`), ":runtime:assembleRelease"], join(ROOT, "platforms/android"));
    if (plan.release && project?.androidSigning) {
      const module = target === "android-watch" ? "android/app" : "wearos/app";
      // Keep APKs outside the directory Gradle packages as runtime assets.
      const output = join(project.output, "packages", target, "app-release.apk");
      signAndroidApk(join(ROOT, "platforms", module, "build/outputs/apk/release", `${app.slice(1)}-release-unsigned.apk`), output, project.androidSigning, overrides.debuggable);
      console.log(`pod: release APK -> ${output}`);
    }
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
  release?: boolean;
  debuggable?: boolean;
  versionCode?: string | number;
}

const cli = cac("pod");

cli
  .command("create <dir>", "Create a standalone watch app (source only)")
  .option("--app-id <id>", "Android application id")
  .option("--name <name>", "Display name")
  .action((directory: string, options: {appId: string; name: string}) => {
    try { createProject(directory, options); }
    catch (error) { fail(error instanceof Error ? error.message : String(error)); }
  });

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
  .option("--release", "Build a release APK using android.signing (non-debuggable by default)")
  .option("--debuggable", "Verification-only release APK; DO NOT PUBLISH (requires --release)")
  .option("--version-code <N>", "Override Android versionCode for this package invocation")
  .action(async (target: string | undefined, options: TargetOptions) => {
    await packageTarget(targetArg(target, options.target), projectOption(options.project), options.release, options.debuggable, options.versionCode);
  });

cli
  .command("signing <action>", "Create a private Android signing certificate lineage")
  .option("--new-properties <path>", "New signer keystore.properties (passwords read in a subprocess)")
  .option("--out <path>", "New lineage path; an existing file is never replaced")
  .option("--old-properties <path>", "Old signer keystore.properties")
  .option("--old-debug-keystore <path>", "Old standard Android debug keystore (explicit opt-in)")
  .option("--old-keystore <path>", "Old keystore with credentials supplied by environment references")
  .option("--old-key-alias <alias>", "Old key alias")
  .option("--old-store-pass-env <name>", "Old store password variable name; default POD_OLD_STORE_PASSWORD")
  .option("--old-key-pass-env <name>", "Old key password variable name; default POD_OLD_KEY_PASSWORD")
  .action((action: string, options: RotateOptions) => {
    if (action !== "rotate") fail("signing action must be rotate");
    if (!options.newProperties || !options.out) fail("signing rotate requires --new-properties and --out");
    rotateAndroidSigning(options);
    console.log(`pod: lineage -> ${resolve(options.out)}`);
  });

cli.help();
cli.version("0.1.0");
cli.addEventListener("command:*", event => {
  if (process.argv.some(arg => ["--help", "-h", "--version", "-v"].includes(arg))) return;
  fail(`unknown command: ${(event as CustomEvent<string>).detail}`);
});

if (process.argv.length === 2) {
  doctor();
} else {
  cli.parse(process.argv, { run: false });
  if (!process.argv.includes("--help") && !process.argv.includes("-h") && !process.argv.includes("--version") && !process.argv.includes("-v")) await cli.runMatchedCommand();

}
