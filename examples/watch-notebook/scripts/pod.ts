import {resolve} from "node:path";
process.argv.push(`--project=${resolve(import.meta.dir, "..")}`);
const cli = "../../../packages/cli/src/cli.ts";
await import(cli);
export {};
