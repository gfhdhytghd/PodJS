import {expect, test} from "bun:test";
import {fileURLToPath} from "node:url";

test("system text status public API through an isolated native service bridge", async () => {
  const suite = fileURLToPath(new URL("./system-text-status.isolated.ts", import.meta.url));
  const child = Bun.spawn([process.execPath, "test", "--conditions=browser", suite], {
    stdout: "pipe", stderr: "pipe",
  });
  const [exitCode, stdout, stderr] = await Promise.all([
    child.exited, new Response(child.stdout).text(), new Response(child.stderr).text(),
  ]);
  expect(exitCode, `${stdout}\n${stderr}`).toBe(0);
}, 30_000);
