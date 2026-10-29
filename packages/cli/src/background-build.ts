import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { isAbsolute, join } from "node:path";

export interface BackgroundArtifact { file: string; sha256: string; bytes: number }
/** Each handler gets its own IIFE, never the UI entry, assets or frame bootstrap. */
export async function buildBackground(entries: Record<string, string>, output: string): Promise<Record<string, BackgroundArtifact>> {
  const artifacts: Record<string, BackgroundArtifact> = Object.create(null);
  for (const [id, entry] of Object.entries(entries)) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(id) || !isAbsolute(entry)) throw new Error("Invalid background build entry");
    const result = await Bun.build({
      entrypoints: ["pod-background:entry"], format: "iife", target: "browser", splitting: false, minify: false,
      plugins: [{ name: "pod-headless-entry", setup(build) {
        build.onResolve({ filter: /.*/ }, args => {
          if (args.path === "pod-background:entry") return { path: "entry", namespace: "pod-background" };
          if (args.kind === "dynamic-import") throw new Error("Background bundles cannot dynamically import code");
          // These helpers are query-port/algorithm-only: no UI, services or host bootstrap.
          if (["@podjs/framework/background-handler", "@podjs/framework/bytes", "@podjs/framework/html", "@podjs/framework/digest-cache", "@podjs/framework/work-receipts", "@podjs/framework/crypto", "@podjs/framework/frame-stream", "@podjs/framework/sql", "@podjs/framework/sql-changes", "@podjs/framework/regexp", "@podjs/framework/json-numbers", "@podjs/framework/json-string", "@podjs/framework/json-raw", "@podjs/framework/record-tuples", "@podjs/framework/build-gate", "@podjs/framework/text-compat", "@podjs/framework/async"].includes(args.path)) return undefined;
          if (/^(node:|bun:|solid-js(?:\/|$)|react(?:\/|$)|@pocketjs\/framework(?:\/|$)|@podjs\/framework(?:\/|$))/.test(args.path)) {
            throw new Error(`Background entry cannot import UI or unrestricted host module: ${args.path}`);
          }
          if (args.namespace === "pod-background" && isAbsolute(args.path)) return { path: args.path, namespace: "file" };
          return undefined;
        });
        build.onLoad({ filter: /.*/, namespace: "pod-background" }, () => ({ loader: "js", contents:
          `import handler from ${JSON.stringify(entry)};\nif (typeof handler !== "function") throw new Error("Background entry must export a default handler");\nglobalThis.backgroundHandler = handler;` }));
      }}],
    }).catch(error => {
      const detail = error instanceof AggregateError ? error.errors.map(String).join("\n") : String(error);
      throw new Error(`Background bundle failed: ${detail}`);
    });
    if (!result.success) throw new Error(result.logs.map(item => item.message).join("\n"));
    if (result.outputs.length !== 1) throw new Error("Background handler must produce exactly one script");
    const bytes = new Uint8Array(await result.outputs[0]!.arrayBuffer());
    if (bytes.length > 1024 * 1024) throw new Error("Background bundle exceeds 1 MiB");
    const name = createHash("sha256").update(id).digest("hex") + ".js";
    const file = `background/${name}`;
    mkdirSync(join(output, "background"), { recursive: true });
    writeFileSync(join(output, file), bytes);
    artifacts[id] = { file, sha256: createHash("sha256").update(bytes).digest("hex"), bytes: bytes.length };
  }
  return artifacts;
}
