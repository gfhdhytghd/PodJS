import { kv } from "@podjs/framework/watch";
import { createFileCache } from "@podjs/framework/cache";
import { createImageLoader } from "@podjs/framework/image";
import { createLogRedactor, FileLogger } from "@podjs/framework/logging";
import { stringToUtf8 } from "@podjs/framework/bytes";

/** Small persistent redacted log. A real app can supply native file ports. */
export function createResources() {
  const logKey = "notebook.log";
  const logger = new FileLogger({
    async read() { return kv.get<string>(logKey) ?? null; },
    async size() { return stringToUtf8(kv.get<string>(logKey) ?? "").length; },
    async replace(text) { kv.set(logKey, text); },
    async append(text) { kv.set(logKey, (kv.get<string>(logKey) ?? "") + text); },
  }, { redact: createLogRedactor({ sensitivePattern: "token|password" }), maxBytes: 8192,
    trim: lines => lines.slice(-20).join("\n") + "\n", writeFailureMessage: "Notebook log unavailable" },
    undefined, (_level, line) => console.log(line));
  void logger.init().catch(() => logger.w("notebook", "Log initialization failed"));
  const cache = createFileCache({
    root: "notebook-cache", buckets: ["images"], limitBytes: 4 * 1024 * 1024,
    namespace: () => ({ filename: key => `${key}.bin`, ownsFile: name => /^[a-f0-9]{64}\.bin$/.test(name),
      stagedTarget: name => /^([a-f0-9]{64}\.bin)\.download-[0-9]+$/.exec(name)?.[1],
      traverseDirectory: () => false, cleanupStaged: true }),
    evictionPolicy(files, limit, pinned) {
      let bytes = files.reduce((sum, file) => sum + file.size, 0);
      return [...files].sort((a, b) => a.modified - b.modified).filter(file => {
        if (bytes <= limit || pinned.has(file.path)) return false;
        bytes -= file.size; return true;
      });
    },
  });
  const loader = createImageLoader({ sourceProvider: { acquire: source => cache.acquire(source.url, source.headers) },
    logger: { info: message => logger.i("image", message), warn: message => logger.w("image", message) },
    maxConcurrent: 1, retainedBytes: 512 * 1024 });
  return { logger, cache, loader };
}
