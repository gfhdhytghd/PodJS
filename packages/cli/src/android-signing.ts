import { existsSync, readdirSync, mkdtempSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import type { AndroidSigning } from "./project.ts";

export function androidBuildTool(name: string): string {
  const sdk = process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME;
  if (!sdk) throw new Error("ANDROID_SDK_ROOT or ANDROID_HOME is required for Android signing");
  const root = join(sdk, "build-tools");
  const versions = readdirSync(root).filter(version => /^\d+(\.\d+)+$/.test(version)).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  for (const version of versions) {
    const path = join(root, version, name);
    if (existsSync(path)) return path;
  }
  throw new Error(`Android build tool is missing: ${name}`);
}

function worker(operation: "sign" | "rotate", args: string[]): void {
  const result = Bun.spawnSync(["java", resolve(import.meta.dir, "../signing/SigningWorker.java"), operation,
    "--apksigner", androidBuildTool("apksigner"), ...args], { stdout: "pipe", stderr: "pipe" });
  // Do not propagate even JVM startup diagnostics: only fixed messages are public.
  if (result.exitCode !== 0) throw new Error("Android signing failed; check credential paths, passwords, oldest signer and SDK tools (diagnostics withheld)");
}

export function androidPackagePlan(release: boolean, signing?: AndroidSigning) {
  // Existing packaging remains debug-signed when signing is not configured.
  return { release: release && !!signing, tasks: release && signing ? ["assembleRelease"] : ["assembleDebug", "bundleRelease"] };
}

export function androidPackageOptions(release: boolean, signing: AndroidSigning | undefined, debuggable = false, versionCode?: unknown) {
  if (debuggable && (!release || !signing)) throw new Error("--debuggable requires --release and android.signing; verification packages must not be published");
  if (versionCode !== undefined && !/^[1-9][0-9]*$/.test(String(versionCode))) throw new Error("--version-code must be a positive Android integer");
  const version = versionCode === undefined ? undefined : Number(versionCode);
  if (version !== undefined && (!Number.isSafeInteger(version) || version > 2100000000)) throw new Error("--version-code must be <= 2100000000");
  return { debuggable, versionCode: version };
}

export function signAndroidApk(input: string, output: string, signing: AndroidSigning, debuggable = false): void {
  const temporary = mkdtempSync(join(tmpdir(), "pod-align-"));
  try {
    const aligned = join(temporary, "aligned.apk");
    const result = Bun.spawnSync([androidBuildTool("zipalign"), "-P", "16", "-f", "4", input, aligned], { stdout: "pipe", stderr: "pipe" });
    if (result.exitCode !== 0) throw new Error("Android APK alignment failed");
    worker("sign", ["--in", aligned, "--out", output, "--properties", signing.keystoreProperties, "--debuggable", String(debuggable),
      ...(signing.lineage ? ["--lineage", signing.lineage, "--rotation-min-sdk", String(signing.rotationMinSdk ?? 28)] : []),
      ...(signing.lineageSignerProperties ? ["--old-properties", signing.lineageSignerProperties] : []),
      ...(signing.lineageDebugKeystore ? ["--old-debug-keystore", signing.lineageDebugKeystore] : [])]);
  } finally { rmSync(temporary, { recursive: true, force: true }); }
}

export interface RotateOptions {
  newProperties: string;
  out: string;
  oldProperties?: string;
  oldDebugKeystore?: string;
  oldKeystore?: string;
  oldKeyAlias?: string;
  oldStorePassEnv?: string;
  oldKeyPassEnv?: string;
}
export function rotateAndroidSigning(options: RotateOptions): void {
  const choices = [options.oldProperties, options.oldDebugKeystore, options.oldKeystore].filter(Boolean);
  if (choices.length !== 1) throw new Error("Choose exactly one of --old-properties, --old-debug-keystore or --old-keystore");
  if (options.oldKeystore && !options.oldKeyAlias) throw new Error("--old-keystore requires --old-key-alias and password environment references");
  worker("rotate", ["--properties", resolve(options.newProperties), "--out", resolve(options.out),
    ...Object.entries({ "old-properties": options.oldProperties, "old-debug-keystore": options.oldDebugKeystore,
      "old-keystore": options.oldKeystore, "old-key-alias": options.oldKeyAlias,
      "old-store-pass-env": options.oldStorePassEnv, "old-key-pass-env": options.oldKeyPassEnv })
      .flatMap(([key, value]) => value ? [`--${key}`, key.endsWith("properties") || key.endsWith("keystore") ? resolve(value) : value] : [])]);
}
