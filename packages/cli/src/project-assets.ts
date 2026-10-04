import { createHash } from "node:crypto";
import {
  closeSync, constants, copyFileSync, fstatSync, lstatSync, mkdirSync, mkdtempSync,
  openSync, readSync, readdirSync, realpathSync, renameSync, rmSync, statSync,
} from "node:fs";
import { dirname, isAbsolute, join, parse, relative, resolve, sep } from "node:path";

const ASSET_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const WINDOWS_DEVICE = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i;

function invalid(message: string): never {
  throw new Error(`invalid pod.config.json: resources.assets ${message}`);
}

function validateNames(names: string[]): void {
  const files = new Set(names.map(name => name.toLowerCase()));
  const spellings = new Map<string, string>();
  for (const name of names) {
    const segments = name.split("/");
    if (name.length > 512 || segments.some(segment => segment.length > 255 || !ASSET_SEGMENT.test(segment) || segment.endsWith(".") || WINDOWS_DEVICE.test(segment))) {
      invalid(`has unsafe logical name: ${JSON.stringify(name)}`);
    }
    for (let index = 0; index < segments.length; index++) {
      const spelling = segments.slice(0, index + 1).join("/");
      const key = spelling.toLowerCase();
      const previous = spellings.get(key);
      if (previous !== undefined && previous !== spelling) invalid(`has a case alias: ${JSON.stringify(previous)} and ${JSON.stringify(spelling)}`);
      spellings.set(key, spelling);
      if (index < segments.length - 1 && files.has(key)) invalid(`has a file/directory conflict: ${JSON.stringify(spelling)}`);
    }
  }
}

function requireFile(path: string, name: string): void {
  try {
    if (statSync(path).isFile()) return;
  } catch { /* Report a stable config error for a missing or unreadable source. */ }
  invalid(`${JSON.stringify(name)} source must be a regular file: ${path}`);
}

/** Validate portable asset names while preserving the configured source spelling. */
export function resolveProjectAssets(value: unknown, projectRoot: string): {
  config: Record<string, string>;
  resolved: Record<string, string>;
} {
  const config: Record<string, string> = Object.create(null);
  const resolved: Record<string, string> = Object.create(null);
  if (value === undefined) return { config, resolved };
  if (value === null || typeof value !== "object" || Array.isArray(value) ||
      (Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null)) invalid("must map logical asset names to source paths");
  const entries = Object.entries(value);
  validateNames(entries.map(([name]) => name));
  for (const [name, source] of entries) {
    if (typeof source !== "string" || source.trim() === "" || source.includes("\0")) invalid(`${JSON.stringify(name)} source must be a non-empty path`);
    const path = resolve(projectRoot, source);
    requireFile(path, name);
    config[name] = source;
    resolved[name] = path;
  }
  return { config, resolved };
}

function lstatIfPresent(path: string): ReturnType<typeof lstatSync> | undefined {
  try { return lstatSync(path); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

/** Do not follow even a dangling symlink in a path that we will write through. */
function requireDirectoryPath(path: string): void {
  const absolute = resolve(path);
  let current = parse(absolute).root;
  for (const segment of relative(current, absolute).split(sep).filter(Boolean)) {
    current = join(current, segment);
    const stat = lstatIfPresent(current);
    if (stat?.isSymbolicLink()) throw new Error(`asset output path must not contain a symlink: ${current}`);
    if (stat && !stat.isDirectory()) throw new Error(`asset output path must be a directory: ${current}`);
  }
}

function requireSafeTree(path: string): boolean {
  const stat = lstatIfPresent(path);
  if (!stat) return false;
  if (stat.isSymbolicLink()) throw new Error(`asset output tree must not contain a symlink: ${path}`);
  if (!stat.isDirectory()) throw new Error(`asset output tree must be a directory: ${path}`);
  for (const entry of readdirSync(path)) {
    const child = join(path, entry);
    const childStat = lstatSync(child);
    if (childStat.isSymbolicLink()) throw new Error(`asset output tree must not contain a symlink: ${child}`);
    if (childStat.isDirectory()) requireSafeTree(child);
    else if (!childStat.isFile()) throw new Error(`asset output tree contains a non-regular file: ${child}`);
  }
  return true;
}

function isWithin(path: string, directory: string): boolean {
  const difference = relative(directory, path);
  return difference === "" || (!isAbsolute(difference) && difference !== ".." && !difference.startsWith(`..${sep}`));
}

function safeSources(assets: Record<string, string>, tree: string): Record<string, string> {
  const sources: Record<string, string> = Object.create(null);
  for (const [name, source] of Object.entries(assets)) {
    requireFile(source, name);
    const canonical = realpathSync(source);
    // Check both spellings: replacing the tree must not remove even a source symlink
    // that happens to point out of the tree, or a source reached through an alias.
    if (isWithin(resolve(source), tree) || isWithin(canonical, tree)) {
      throw new Error(`asset source must not be inside the output tree being replaced: ${source}`);
    }
    sources[name] = canonical;
  }
  return sources;
}

function fingerprint(path: string): { size: number; sha256: string } {
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    if (!fstatSync(fd).isFile()) throw new Error(`copied asset is not a regular file: ${path}`);
    const hash = createHash("sha256");
    const buffer = Buffer.allocUnsafe(64 * 1024);
    let size = 0;
    for (;;) {
      const count = readSync(fd, buffer, 0, buffer.length, null);
      if (count === 0) break;
      hash.update(buffer.subarray(0, count));
      size += count;
    }
    return { size, sha256: hash.digest("hex") };
  } finally { closeSync(fd); }
}

/**
 * Replace only output/pod-assets after every new file has been copied and hashed.
 * Callers must serialize writes to an output directory. Caught publication errors
 * restore the previous tree; the two renames do not provide crash recovery.
 */
export function syncProjectAssets(outputDirectory: string, assets: Record<string, string>): Record<string, { size: number; sha256: string }> {
  const output = resolve(outputDirectory);
  const tree = join(output, "pod-assets");
  const validated = resolveProjectAssets(assets, process.cwd()).resolved;
  const result: Record<string, { size: number; sha256: string }> = Object.create(null);
  // Legacy projects have no managed tree to write. Preserve their existing
  // behavior even if the build output is reached through a directory symlink.
  if (Object.keys(validated).length === 0 && !lstatIfPresent(tree)) return result;
  requireDirectoryPath(output);
  const hadPrevious = requireSafeTree(tree);
  const sources = safeSources(validated, tree);
  if (Object.keys(sources).length === 0 && !hadPrevious) return result;

  mkdirSync(output, { recursive: true });
  requireDirectoryPath(output);
  const staging = mkdtempSync(join(output, ".pod-assets-stage-"));
  const next = join(staging, "next");
  const previous = join(staging, "previous");
  let movedPrevious = false;
  let committed = false;
  try {
    mkdirSync(next);
    for (const [name, source] of Object.entries(sources)) {
      const destination = join(next, name);
      mkdirSync(dirname(destination), { recursive: true });
      requireDirectoryPath(dirname(destination));
      copyFileSync(source, destination, constants.COPYFILE_EXCL);
      result[name] = fingerprint(destination);
    }
    // Recheck immediately before publication; never replace a newly introduced
    // symlink or remove an original source that has moved into the managed tree.
    requireDirectoryPath(output);
    const hasPrevious = requireSafeTree(tree);
    safeSources(validated, tree);
    if (hasPrevious) {
      renameSync(tree, previous);
      movedPrevious = true;
    }
    try { renameSync(next, tree); }
    catch (error) {
      if (movedPrevious) {
        try { renameSync(previous, tree); }
        catch (restoreError) {
          throw new AggregateError([error, restoreError], `asset publication and rollback failed; previous assets preserved at ${previous}`);
        }
        movedPrevious = false;
      }
      throw error;
    }
    committed = true;
  } finally {
    // If restoring the previous tree itself failed, keep that backup for recovery.
    if (committed || !movedPrevious) {
      try { rmSync(staging, { recursive: true, force: true }); }
      catch (error) {
        throw new Error(`${committed ? "assets were committed" : "asset publication did not complete"}; could not remove staging directory: ${staging}`, { cause: error });
      }
    }
  }
  return result;
}
