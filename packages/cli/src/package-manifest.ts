import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { syncProjectAssets, type PackagedAsset, type PakFontAlias } from "./project-assets.ts";

// Whole UTF-8 file, including the final newline. Matches PodManifest and the
// native package validator; font sharing must not expand a valid package past it.
export const PACKAGE_MANIFEST_MAX_BYTES = 128 * 1024;

export function encodePackageManifest(value: unknown): Buffer {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

class SharedManifestTooLarge extends Error {}

/** Publish assets only after their actual metadata fits every manifest reader. */
export function writeProjectManifest(output: string, base: Record<string, unknown>, sources: Record<string, string>, fonts: readonly PakFontAlias[] = []): void {
  if (base.assets !== undefined) throw new Error("manifest base must not contain assets");
  const compose = (assets: Record<string, PackagedAsset>) => ({ ...base, ...(Object.keys(assets).length ? { assets } : {}) });
  let encoded: Buffer | undefined;
  const accept = (assets: Record<string, PackagedAsset>) => {
    const bytes = encodePackageManifest(compose(assets));
    if (bytes.byteLength > PACKAGE_MANIFEST_MAX_BYTES) {
      if (Object.values(assets).some(asset => asset.pakFont)) {
        const physical = Object.fromEntries(Object.entries(assets).map(([name, { size, sha256 }]) => [name, { size, sha256 }]));
        // Fail before copying potentially thousands of large fonts when even
        // legacy metadata cannot fit. Recheck real copied metadata on fallback.
        if (encodePackageManifest(compose(physical)).byteLength <= PACKAGE_MANIFEST_MAX_BYTES) throw new SharedManifestTooLarge();
      }
      throw new Error(`pod.manifest.json exceeds ${PACKAGE_MANIFEST_MAX_BYTES} bytes including the final newline (${bytes.byteLength} bytes)`);
    }
    encoded = bytes;
  };
  try { syncProjectAssets(output, sources, fonts, accept); }
  catch (error) {
    if (!(error instanceof SharedManifestTooLarge)) throw error;
    syncProjectAssets(output, sources, [], accept);
  }
  if (!encoded) throw new Error("manifest metadata was not validated");
  // Write exactly the buffer which passed the budget, without reserializing it.
  writeFileSync(join(output, "pod.manifest.json"), encoded);
}
