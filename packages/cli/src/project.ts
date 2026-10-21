import {validateAndroidSplash,type AndroidSplash} from './android-splash.ts';
import {validateWorkManagerTakeover,type WorkManagerTakeoverConfig} from './workmanager-takeover.ts';
import {validateDebugExternal,type DebugExternalConfig} from './debug-external.ts';
import { existsSync, readFileSync, statSync } from "node:fs";
import { isAbsolute, join, resolve } from "node:path";
import type { PodTargetProfile } from "../../framework/src/targets.ts";
import { resolveProjectAssets } from "./project-assets.ts";

export interface PodFontSlot {
  slot: number;
  px: number;
  path: string;
  bold?: boolean;
  rasterAdvances?: boolean;
}

export interface PodProjectResources {
  /** Logical packaged asset name to a source path, relative to the project. */
  assets?: Record<string, string>;
  fontRegular?: string;
  fontSlots?: PodFontSlot[];
  /** Bounded startup hint; hashes and atlas parsing run on the existing worker. */
  preloadFontSlots?: number[];
  fontBold?: string;
  extraCharsFile?: string;
  /** Opt-in native TTF coverage cache; requires host ABI 3. */
  glyphCache?: { warmChars: string; warmCharsFile?:string; preseedScanned?:boolean };
}

export interface AndroidSigning {
  keystoreProperties: string;
  lineage?: string;
  rotationMinSdk?: number;
  /** v1/v2 with lineage require its oldest signer. */
  lineageSignerProperties?: string;
  /** Explicit opt-in for rotating from the standard Android debug keystore. */
  lineageDebugKeystore?: string;
}

export interface PodProjectConfig {
  entry: string;
  appId: string;
  versionName: string;
  versionCode: number;
  name: string;
  capabilities: string[];
  background?: Record<string, string>;
  backgroundServices?: string[];
  resources?: PodProjectResources;
  /** Omit duplicate function source strings, retaining error locations.
   * Opt in only if Function.toString source introspection is unused. */
  bytecode?: { stripSource: boolean };
  android?: { splash?: AndroidSplash; workManagerTakeover?: WorkManagerTakeoverConfig; debugExternal?: DebugExternalConfig; resources?: string; theme?: string; cleartextTraffic?: boolean; bleProvisioning?: boolean; signing?: AndroidSigning; takeover?: { sourceVersionCode: number; purgeBackupAfter?: number } };
}

export interface ResolvedPodProject {
  config: PodProjectConfig;
  root: string;
  configPath: string;
  entry: string;
  output: string;
  fontRegular: string;
  fontSlots: PodFontSlot[];
  fontBold: string;
  extraChars: string;
  background: Record<string, string>;
  assets: Record<string, string>;
  androidResources?: string;
  androidTheme?: string;
  /** android:usesCleartextTraffic; default false (Android platform default for targetSdk >= 28). */
  androidCleartextTraffic?: boolean;
  androidSigning?: AndroidSigning;
}

const APP_ID = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;

function invalid(message: string): never {
  throw new Error(`invalid pod.config.json: ${message}`);
}

function stringField(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim() === "") invalid(`${field} must be a non-empty string`);
  return value;
}

export function resolvePodProject(spec: string, targets: Record<string, PodTargetProfile>, defaults: { fontRegular: string; fontBold: string; outputRoot: string }): ResolvedPodProject {
  if (!isAbsolute(spec)) invalid("--project must be an absolute project directory or config path");
  const candidate = resolve(spec);
  const root = existsSync(candidate) && statSync(candidate).isDirectory() ? candidate : resolve(candidate, "..");
  const configPath = existsSync(candidate) && statSync(candidate).isDirectory() ? join(candidate, "pod.config.json") : candidate;
  if (!existsSync(configPath) || !statSync(configPath).isFile()) invalid(`config file not found: ${configPath}`);
  let raw: unknown;
  try { raw = JSON.parse(readFileSync(configPath, "utf8")); } catch { invalid(`cannot parse ${configPath}`); }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) invalid("root must be an object");
  const value = raw as Record<string, unknown>;
  const entryValue = stringField(value.entry, "entry");
  const appId = stringField(value.appId, "appId");
  if (!APP_ID.test(appId)) invalid("appId must be a valid Android application id");
  const versionName = stringField(value.versionName, "versionName");
  if (!Number.isInteger(value.versionCode) || (value.versionCode as number) < 1) invalid("versionCode must be a positive integer");
  const name = stringField(value.name, "name");
  if (!Array.isArray(value.capabilities) || value.capabilities.some(cap => typeof cap !== "string")) invalid("capabilities must be an array of strings");
  const capabilities = value.capabilities as string[];
  const backgroundServices = value.backgroundServices ?? [];
  if (!Array.isArray(backgroundServices) || backgroundServices.length > 6 ||
      backgroundServices.some(method => !["kv.get", "kv.set", "kv.delete", "kv.keys", "cloud.host", "debug.cloudHost"].includes(method)) ||
      new Set(backgroundServices).size !== backgroundServices.length) invalid("backgroundServices must be unique supported service methods");
  const known = new Set<string>(Object.values(targets).flatMap(profile => [...profile.capabilities]));
  const unknown = capabilities.filter(cap => !known.has(cap));
  if (unknown.length) invalid(`unknown capability(s): ${unknown.join(", ")}`);
  let bytecode: PodProjectConfig["bytecode"];
  if (value.bytecode !== undefined) {
    if (!value.bytecode || typeof value.bytecode !== "object" || Array.isArray(value.bytecode) ||
        typeof (value.bytecode as Record<string, unknown>).stripSource !== "boolean") invalid("bytecode.stripSource must be a boolean");
    bytecode = { stripSource: (value.bytecode as { stripSource: boolean }).stripSource };
  }
  let resources: PodProjectResources | undefined;
  let assets: Record<string, string> = Object.create(null);
  if (value.resources !== undefined) {
    if (!value.resources || typeof value.resources !== "object" || Array.isArray(value.resources)) invalid("resources must be an object");
    const r = value.resources as Record<string, unknown>;
    resources = {};
    if(r.glyphCache!==undefined){
      const option=r.glyphCache as Record<string,unknown>;
      if(!option||typeof option!=="object"||Array.isArray(option)||typeof option.warmChars!=="string"||option.warmChars.length>4096)invalid("resources.glyphCache requires warmChars string <=4096 code units");
      if(option.preseedScanned!==undefined&&typeof option.preseedScanned!=="boolean")invalid("resources.glyphCache.preseedScanned must be boolean");
      if(option.warmCharsFile!==undefined&&(typeof option.warmCharsFile!=="string"||!option.warmCharsFile.trim()))invalid("resources.glyphCache.warmCharsFile requires a path");
      resources.glyphCache={warmChars:option.warmChars,...(option.preseedScanned!==undefined?{preseedScanned:option.preseedScanned as boolean}:{}),...(option.warmCharsFile!==undefined?{warmCharsFile:option.warmCharsFile as string}:{})};
    }
    if (r.assets !== undefined) {
      const resolvedAssets = resolveProjectAssets(r.assets, root);
      resources.assets = resolvedAssets.config;
      assets = resolvedAssets.resolved;
    }
    if (r.fontRegular !== undefined) resources.fontRegular = stringField(r.fontRegular, "resources.fontRegular");
    if (r.fontBold !== undefined) resources.fontBold = stringField(r.fontBold, "resources.fontBold");
    if (r.preloadFontSlots !== undefined) {
      const slots = r.preloadFontSlots;
      if (!Array.isArray(slots) || slots.length > 8 || new Set(slots).size !== slots.length ||
          slots.some(slot => !Number.isInteger(slot) || slot < 0 || slot > 63)) invalid("resources.preloadFontSlots must be at most 8 unique slots 0..63");
      resources.preloadFontSlots = slots;
    }
    if (r.fontSlots !== undefined) {
      if (!Array.isArray(r.fontSlots)) invalid("resources.fontSlots must be an array");
      const used = new Set<number>();
      resources.fontSlots = r.fontSlots.map((item: unknown) => {
        if (!item || typeof item !== "object" || Array.isArray(item)) invalid("fontSlots entry must be an object");
        const f = item as Record<string, unknown>;
        if (!Number.isInteger(f.slot) || (f.slot as number) < 33 || (f.slot as number) > 63 || used.has(f.slot as number)) invalid("fontSlots require unique slots 33..63");
        if (typeof f.px !== "number" || !Number.isFinite(f.px) || f.px < 1 || f.px > 128) invalid("fontSlots px must be in 1..128");
        if ((f.bold !== undefined && typeof f.bold !== "boolean") || (f.rasterAdvances !== undefined && typeof f.rasterAdvances !== "boolean")) invalid("fontSlots bold/rasterAdvances must be booleans");
        used.add(f.slot as number);
        return {slot:f.slot as number, px:f.px, path:stringField(f.path, "fontSlots.path"),
          ...(f.bold === undefined ? {} : {bold:f.bold as boolean}), ...(f.rasterAdvances === undefined ? {} : {rasterAdvances:f.rasterAdvances as boolean})};
      });
    }
    if (r.extraCharsFile !== undefined) resources.extraCharsFile = stringField(r.extraCharsFile, "resources.extraCharsFile");
  }
  const entry = resolve(root, entryValue);
  if (!existsSync(entry) || !statSync(entry).isFile()) invalid(`entry file not found: ${entry}`);
  const background: Record<string, string> = Object.create(null);
  let backgroundConfig: Record<string, string> | undefined;
  if (value.background !== undefined) {
    if (!value.background || typeof value.background !== "object" || Array.isArray(value.background)) invalid("background must map handler ids to entry files");
    if (Object.keys(value.background).length > 32) invalid("background handler limit is 32");
    backgroundConfig = Object.create(null);
    for (const [handler, path] of Object.entries(value.background)) {
      if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(handler)) invalid("invalid background handler id");
      const relative = stringField(path, `background.${handler}`);
      const absolute = resolve(root, relative);
      if (!existsSync(absolute) || !statSync(absolute).isFile()) invalid(`background entry file not found: ${absolute}`);
      if (absolute === entry) invalid("background entry must be separate from UI entry");
      background[handler] = absolute; backgroundConfig![handler] = relative;
    }
  }
  const fontRegular = resolve(root, resources?.fontRegular ?? defaults.fontRegular);
  const fontBold = resolve(root, resources?.fontBold ?? defaults.fontBold);
  if (!existsSync(fontRegular) || !existsSync(fontBold)) invalid("configured font resource not found");
  const fontSlots = (resources?.fontSlots ?? []).map(resource => {
    const path = resolve(root, resource.path);
    if (!existsSync(path) || !statSync(path).isFile()) invalid(`fontSlots file not found: ${path}`);
    return {...resource, path};
  });
  const extraChars = resources?.extraCharsFile === undefined ? "" : (() => {
    const path = resolve(root, resources.extraCharsFile!);
    if (!existsSync(path) || !statSync(path).isFile()) invalid(`extraCharsFile not found: ${path}`);
    try { return readFileSync(path, "utf8"); } catch { invalid(`cannot read extraCharsFile: ${path}`); }
  })();
  let android: PodProjectConfig["android"];
  let androidResources: string | undefined;
  let androidTheme: string | undefined;
  let androidCleartextTraffic: boolean | undefined;
  let androidSigning: AndroidSigning | undefined;
  if (value.android !== undefined) {
    if (!value.android || typeof value.android !== "object" || Array.isArray(value.android)) invalid("android must be an object");
    const candidate = value.android as Record<string, unknown>;
    android = {};
    if(candidate.splash!==undefined)android.splash=validateAndroidSplash(candidate.splash);
    if(candidate.workManagerTakeover!==undefined)android.workManagerTakeover=validateWorkManagerTakeover(candidate.workManagerTakeover);
    if(candidate.debugExternal!==undefined)android.debugExternal=validateDebugExternal(candidate.debugExternal);
    if (candidate.resources !== undefined) {
      const resourcesPath = stringField(candidate.resources, "android.resources");
      androidResources = resolve(root, resourcesPath);
      if (!existsSync(androidResources) || !statSync(androidResources).isDirectory()) invalid(`android resources directory not found: ${androidResources}`);
      android.resources = resourcesPath;
    }
    if (candidate.theme !== undefined) {
      androidTheme = stringField(candidate.theme, "android.theme");
      if (!/^@style\/[A-Za-z0-9_.]+$/.test(androidTheme)) invalid("android.theme must be an @style resource");
      android.theme = androidTheme;
    }
    if (candidate.bleProvisioning !== undefined) {
      if (typeof candidate.bleProvisioning !== "boolean") invalid("android.bleProvisioning must be a boolean");
      android.bleProvisioning = candidate.bleProvisioning;
    }
    if (candidate.cleartextTraffic !== undefined) {
      if (typeof candidate.cleartextTraffic !== "boolean") invalid("android.cleartextTraffic must be a boolean");
      androidCleartextTraffic = candidate.cleartextTraffic;
      android.cleartextTraffic = androidCleartextTraffic;
    }
    if (candidate.takeover !== undefined) {
      const t = candidate.takeover as Record<string, unknown>;
      if (!t || typeof t !== "object" || Array.isArray(t)) invalid("android.takeover must be an object");
      if (Object.keys(t).some(k => !["sourceVersionCode", "purgeBackupAfter"].includes(k))) invalid("unknown android.takeover option");
      if (!Number.isInteger(t.sourceVersionCode) || (t.sourceVersionCode as number) < 1 || (t.sourceVersionCode as number) > 2147483647) invalid("android.takeover.sourceVersionCode must be a positive Android version code");
      if (t.purgeBackupAfter !== undefined && (!Number.isInteger(t.purgeBackupAfter) || (t.purgeBackupAfter as number) < 1 || (t.purgeBackupAfter as number) > 2147483647)) invalid("android.takeover.purgeBackupAfter must be a positive Android version code");
      android.takeover = { sourceVersionCode: t.sourceVersionCode as number, ...(t.purgeBackupAfter === undefined ? {} : { purgeBackupAfter: t.purgeBackupAfter as number }) };
    }
    if (candidate.signing !== undefined) {
      const signing = candidate.signing as Record<string, unknown>;
      if (!signing || typeof signing !== "object" || Array.isArray(signing)) invalid("android.signing must be an object");
      const configured: AndroidSigning = { keystoreProperties: stringField(signing.keystoreProperties, "android.signing.keystoreProperties") };
      for (const key of ["lineage", "lineageSignerProperties", "lineageDebugKeystore"] as const) {
        if (signing[key] !== undefined) configured[key] = stringField(signing[key], `android.signing.${key}`);
      }
      if (signing.rotationMinSdk !== undefined) {
        if (!Number.isInteger(signing.rotationMinSdk) || (signing.rotationMinSdk as number) < 28) invalid("android.signing.rotationMinSdk must be an integer >= 28");
        configured.rotationMinSdk = signing.rotationMinSdk as number;
      }
      if (configured.lineageSignerProperties && configured.lineageDebugKeystore) invalid("android.signing requires only one oldest signer");
      if (!configured.lineage && (configured.lineageSignerProperties || configured.lineageDebugKeystore || configured.rotationMinSdk !== undefined)) invalid("android.signing rotation options require lineage");
      android.signing = configured;
      androidSigning = { ...configured, keystoreProperties: resolve(root, configured.keystoreProperties) };
      for (const key of ["lineage", "lineageSignerProperties", "lineageDebugKeystore"] as const) {
        if (configured[key]) androidSigning[key] = resolve(root, configured[key]);
      }
    }
  }
  return { config: { entry: entryValue, appId, versionName, versionCode: value.versionCode as number, name, capabilities, resources, android, background: backgroundConfig, backgroundServices, bytecode }, root, configPath, entry, output: join(defaults.outputRoot, appId), fontRegular, fontBold, fontSlots, extraChars, background, assets, androidResources, androidTheme, androidCleartextTraffic, androidSigning };
}
