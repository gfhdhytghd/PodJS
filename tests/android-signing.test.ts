import { afterAll, beforeAll, expect, test } from "bun:test";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { androidBuildTool, androidPackagePlan, rotateAndroidSigning, signAndroidApk } from "../packages/cli/src/android-signing.ts";
import { POD_TARGETS } from "../packages/framework/src/targets.ts";
import { resolvePodProject } from "../packages/cli/src/project.ts";

const root = mkdtempSync(join(tmpdir(), "pod-signing-test-"));
const sdk = process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME;
const toolsAvailable = !!sdk;
const password = "temporary-test-only-9!";
const oldProperties = join(root, "old.properties"), newProperties = join(root, "new.properties");
const oldUnsigned = join(root, "unsigned-old.apk");
const unsigned = join(root, "unsigned.apk"), lineage = join(root, "test.lineage");
function run(cmd: string[], env?: Record<string, string>) {
  const result = Bun.spawnSync(cmd, { stdout: "pipe", stderr: "pipe", env: { ...process.env, ...env } });
  expect(result.exitCode).toBe(0);
  return result.stdout.toString();
}
beforeAll(() => {
  if (!toolsAvailable) return;
  for (const name of ["old", "new"]) {
    run(["keytool", "-genkeypair", "-keystore", join(root, `${name}.jks`), "-alias", name,
      "-storepass:env", "TEST_STORE", "-keypass:env", "TEST_KEY", "-keyalg", "RSA", "-keysize", "2048",
      "-validity", "2", "-dname", `CN=Temporary ${name}`], { TEST_STORE: password, TEST_KEY: password });
    // Java Properties escapes, continuation and ':' separators are intentionally exercised.
    writeFileSync(join(root, `${name}.properties`), `storeFile:${name}.jks\nstorePassword=temporary-test-\\\n  only-9!\nkeyAlias=${name}\nkeyPassword=temporary-test-only-9\\u0021\n`);
  }
  const manifest = join(root, "AndroidManifest.xml");
  writeFileSync(manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.signing" android:versionCode="2"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/><application android:debuggable="false"/></manifest>');
  run([androidBuildTool("aapt2"), "link", "--manifest", manifest, "-I", join(sdk!, "platforms/android-36/android.jar"), "-o", unsigned]);
  writeFileSync(manifest, readFileSync(manifest,"utf8").replace('android:versionCode="2"', 'android:versionCode="1"'));
  run([androidBuildTool("aapt2"), "link", "--manifest", manifest, "-I", join(sdk!, "platforms/android-36/android.jar"), "-o", oldUnsigned]);
}, 20000);
afterAll(() => rmSync(root, { recursive: true, force: true }));

test("rotation CLI help is usable without credentials", () => {
  const result = Bun.spawnSync(["bun", resolve(import.meta.dir, "../packages/cli/src/cli.ts"), "signing", "rotate", "--help"], {stdout:"pipe",stderr:"pipe"});
  expect(result.exitCode).toBe(0);
  expect(result.stdout.toString()).toContain("--new-properties");
  expect(result.stderr.toString()).toBe("");
});

test("no signing configuration preserves existing debug packaging", () => {
  expect(androidPackagePlan(false)).toEqual({ release: false, tasks: ["assembleDebug", "bundleRelease"] });
  expect(androidPackagePlan(true)).toEqual(androidPackagePlan(false));
  expect(androidPackagePlan(false, { keystoreProperties: newProperties })).toEqual(androidPackagePlan(false));
  expect(androidPackagePlan(true, { keystoreProperties: newProperties })).toEqual({ release: true, tasks: ["assembleRelease"] });
});

test("signing-only Android config resolves paths without reading secrets", () => {
  writeFileSync(join(root, "main.tsx"), "export default 1"); writeFileSync(join(root, "font.ttf"), "font");
  const config = { entry: "main.tsx", appId: "com.example.signing", versionName: "1", versionCode: 2, name: "Example", capabilities: [], android: { signing: { keystoreProperties: "missing.properties", lineage: "private/test.lineage", lineageSignerProperties: "old.properties", rotationMinSdk: 28 } } };
  const path = join(root, "pod.config.json");writeFileSync(path, JSON.stringify(config));
  const defaults = {fontRegular: "font.ttf", fontBold: "font.ttf", outputRoot: root};
  const project = resolvePodProject(root, POD_TARGETS, defaults);
  expect(project.androidSigning?.keystoreProperties).toBe(join(root, "missing.properties"));
  expect(project.androidSigning?.lineage).toBe(join(root, "private/test.lineage"));
  expect(project.config.android?.signing?.keystoreProperties).toBe("missing.properties");
  for (const rotationMinSdk of [27, 28.5, "28", null]) {
    writeFileSync(path, JSON.stringify({...config, android: {signing: {...config.android.signing, rotationMinSdk}}}));
    expect(() => resolvePodProject(root, POD_TARGETS, defaults)).toThrow("rotationMinSdk");
  }
});

test.skipIf(!toolsAvailable)("properties sign and rotation verify embedded lineage and installed-data capability", () => {
  const oldApk = join(root, "old.apk"), newApk = join(root, "new.apk");
  // No injected dependencies: same constructors and worker/tool discovery as production.
  signAndroidApk(oldUnsigned, oldApk, { keystoreProperties: oldProperties });
  const oldVerification = run([androidBuildTool("apksigner"), "verify", "--verbose", "--print-certs", oldApk]);
  expect(oldVerification).toContain("CN=Temporary old");
  expect(run([androidBuildTool("apksigner"), "verify", "--verbose", "--min-sdk-version", "24", "--max-sdk-version", "27", oldApk])).toContain("Verified using v2 scheme (APK Signature Scheme v2): true");
  // Exercise the real CLI, not only the exported helper.
  run(["bun", resolve(import.meta.dir, "../packages/cli/src/cli.ts"), "signing", "rotate", "--old-properties", oldProperties, "--new-properties", newProperties, "--out", lineage]);
  expect(readFileSync(lineage).length).toBeGreaterThan(0);
  expect(() => rotateAndroidSigning({oldProperties, newProperties, out: lineage})).toThrow("Android signing failed");
  signAndroidApk(unsigned, newApk, { keystoreProperties: newProperties, lineage, lineageSignerProperties: oldProperties, rotationMinSdk: 28 });
  const verification = run([androidBuildTool("apksigner"), "verify", "--verbose", "--print-certs", "--min-sdk-version", "30", "--max-sdk-version", "32", newApk]);
  expect(verification).toContain("CN=Temporary new");
  expect(verification).toContain("Verified using v3 scheme (APK Signature Scheme v3): true");
  expect(run([androidBuildTool("apksigner"), "verify", "--verbose", "--min-sdk-version", "24", "--max-sdk-version", "27", newApk])).toContain("Verified using v2 scheme (APK Signature Scheme v2): true");
  // apksig reads lineage directly from the signed APK and verifies old cert + upgrade capability.
  const probe = join(root, "LineageProbe.java");
  writeFileSync(probe, `import com.android.apksig.*; import java.io.*; class LineageProbe {public static void main(String[] a) throws Exception {var old=new ApkVerifier.Builder(new File(a[0])).build().verify().getSignerCertificates().get(0);var history=SigningCertificateLineage.readFromApkFile(new File(a[1]));if(history.getCertificatesInLineage().size()!=2||!history.getCertificatesInLineage().get(0).equals(old)||!history.getSignerCapabilities(old).hasInstalledData())throw new Exception("upgrade lineage mismatch");System.out.println("old certificate authorized for installed data; two certificates");}}`);
  const result = run(["java", "--class-path", join(androidBuildTool("apksigner"), "../lib/apksigner.jar"), probe, oldApk, newApk]);
  expect(result).toContain("old certificate authorized for installed data");
  expect(run([androidBuildTool("aapt2"), "dump", "badging", oldApk])).toContain("versionCode='1'");
  expect(run([androidBuildTool("aapt2"), "dump", "badging", newApk])).toContain("versionCode='2'");
  // A missing oldest signer fails closed, preserving any existing output.
  expect(() => signAndroidApk(unsigned, newApk, {keystoreProperties: newProperties, lineage})).toThrow("Android signing failed");
  expect(run([androidBuildTool("apksigner"), "verify", newApk])).toBe("");
}, 30000);

test.skipIf(!toolsAvailable)("passwords stay out of argv and hostile signer output on success and failure", () => {
  const fakeSdk = join(root, "fake-sdk"), bin = join(fakeSdk, "build-tools/36.1.0");
  mkdirSync(bin, {recursive:true});
  const args = join(root, "argv.txt"), envSeen = join(root, "env-seen.txt");
  const fake = join(bin, "apksigner");
  writeFileSync(fake, `#!/bin/sh\nprintf '%s\\n' "$@" > '${args}'\nprintf '%s' "$POD_SIGN_NEW_STORE" > '${envSeen}'\nprintf '%s' "$POD_SIGN_NEW_STORE"\nprintf '%s' "$POD_SIGN_NEW_KEY" >&2\nsleep 0.1\nexit 1\n`);chmodSync(fake, 0o700);
  const worker = resolve(import.meta.dir, "../packages/cli/signing/SigningWorker.java");
  const result = Bun.spawnSync(["java", worker, "rotate", "--apksigner", fake, "--old-properties", oldProperties, "--properties", newProperties, "--out", join(root, "failed.lineage")], { stdout: "pipe", stderr: "pipe" });
  expect(result.exitCode).toBe(1);
  expect(readFileSync(args,"utf8")).not.toContain(password);
  expect(readFileSync(args,"utf8")).toContain("env:POD_SIGN_NEW_STORE");
  expect(readFileSync(envSeen,"utf8")).toBe(password);
  expect(result.stdout.toString()+result.stderr.toString()).not.toContain(password);
  expect(result.stderr.toString()).toContain("diagnostics withheld");
  // Success output is suppressed too; rotate publishes only a public lineage receipt.
  writeFileSync(fake, `#!/bin/sh\nprintf '%s' "$POD_SIGN_NEW_STORE"\nprintf '%s' "$POD_SIGN_NEW_KEY" >&2\nexit 0\n`);chmodSync(fake,0o700);
  const success = Bun.spawnSync(["java", worker, "rotate", "--apksigner", fake, "--old-properties", oldProperties, "--properties", newProperties, "--out", join(root, "fake.lineage")], {stdout:"pipe",stderr:"pipe"});
  expect(success.exitCode).toBe(0);
  expect(success.stdout.toString()+success.stderr.toString()).not.toContain(password);
}, 15000);
