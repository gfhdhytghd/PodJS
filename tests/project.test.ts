import { describe, expect, test } from "bun:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { POD_TARGETS } from "../packages/framework/src/targets.ts";
import { resolvePodProject } from "../packages/cli/src/project.ts";

function fixture(config: Record<string, unknown>) {
  const root = mkdtempSync(join(tmpdir(), "podjs-project-"));
  writeFileSync(join(root, "main.tsx"), "export default 1;");
  writeFileSync(join(root, "background.ts"), "export default () => 'success';");
  writeFileSync(join(root, "font.ttf"), "font");
  writeFileSync(join(root, "chars.txt"), "中文⌚");
  writeFileSync(join(root, "pod.config.json"), JSON.stringify(config));
  return root;
}

const base = { entry: "main.tsx", appId: "com.example.demo", versionName: "1.2.3", versionCode: 7, name: "Demo", capabilities: ["input.touch"] };
const defaults = { fontRegular: "font.ttf", fontBold: "font.ttf", outputRoot: "/tmp/pod-dist" };

describe("external project config", () => {
  test("background service grants are explicit and bounded", () => {
    expect(resolvePodProject(fixture(base),POD_TARGETS,defaults).config.backgroundServices).toEqual([]);
    expect(resolvePodProject(fixture({...base,backgroundServices:["kv.get","kv.set"]}),POD_TARGETS,defaults).config.backgroundServices).toEqual(["kv.get","kv.set"]);
    for (const backgroundServices of [["http.get"],["kv.get","kv.get"],"kv.get",[null]])
      expect(() => resolvePodProject(fixture({...base,backgroundServices}),POD_TARGETS,defaults)).toThrow("backgroundServices");
  });
  test("resolves separate background entries and rejects UI reuse", () => {
    const root = fixture({ ...base, background: { refresh: "background.ts" } });
    expect(resolvePodProject(root,POD_TARGETS,defaults).background.refresh).toBe(join(root,"background.ts"));
    for (const background of [{ refresh: "main.tsx" },{ refresh: "missing.ts" },{ "bad/id": "background.ts" }]) {
      expect(() => resolvePodProject(fixture({ ...base, background }),POD_TARGETS,defaults)).toThrow("background");
    }
  });
  test("loads defaults and resolves relative entry", () => {
    const root = fixture(base);
    const project = resolvePodProject(root, POD_TARGETS, defaults);
    expect(project.config.appId).toBe("com.example.demo");
    expect(project.entry).toBe(join(root, "main.tsx"));
  });

  test("rejects missing config", () => {
    expect(() => resolvePodProject(join(tmpdir(), "no-such-pod-project"), POD_TARGETS, defaults)).toThrow("config file not found");
  });

  test("rejects invalid app id and missing entry", () => {
    const root = fixture({ ...base, appId: "bad id" });
    expect(() => resolvePodProject(root, POD_TARGETS, defaults)).toThrow("appId");
    const missing = fixture({ ...base, entry: "missing.tsx" });
    expect(() => resolvePodProject(missing, POD_TARGETS, defaults)).toThrow("entry file not found");
  });

  test("rejects unknown capability", () => {
    const root = fixture({ ...base, capabilities: ["device.teleport"] });
    expect(() => resolvePodProject(root, POD_TARGETS, defaults)).toThrow("unknown capability");
  });

  test("loads optional extra character file", () => {
    const root = fixture({ ...base, resources: { extraCharsFile: "chars.txt" } });
    expect(resolvePodProject(root, POD_TARGETS, defaults).extraChars).toBe("中文⌚");
    const missing = fixture({ ...base, resources: { extraCharsFile: "missing.txt" } });
    expect(() => resolvePodProject(missing, POD_TARGETS, defaults)).toThrow("extraCharsFile");
  });
});


test("application font resources retain relative config and resolve build paths", () => {
  const font = {slot:33,px:14,path:"font.ttf",rasterAdvances:true};
  const root = fixture({...base,resources:{fontSlots:[font]}});
  const resolved = resolvePodProject(root,POD_TARGETS,defaults);
  expect(resolved.config.resources?.fontSlots).toEqual([font]);
  expect(resolved.fontSlots).toEqual([{...font,path:join(root,"font.ttf")}]);
  for (const fontSlots of [[{...font,slot:1}],[font,font],[{...font,slot:64}],[{...font,px:0}],[{...font,bold:1}],[{...font,path:"missing.ttf"}]]) {
    const bad = fixture({...base,resources:{fontSlots}});
    expect(() => resolvePodProject(bad,POD_TARGETS,defaults)).toThrow("fontSlots");
  }
});

test("packaged assets retain logical names and resolve source paths separately from font atlases", () => {
  const root = fixture({...base,resources:{assets:{"fonts/body.ttf":"font.ttf","legal/notice.txt":"chars.txt"}}});
  const resolved = resolvePodProject(root,POD_TARGETS,defaults);
  expect(resolved.config.resources?.assets).toEqual({"fonts/body.ttf":"font.ttf","legal/notice.txt":"chars.txt"});
  expect(resolved.assets).toEqual({"fonts/body.ttf":join(root,"font.ttf"),"legal/notice.txt":join(root,"chars.txt")});
  expect(resolved.fontSlots).toEqual([]);
  expect(resolvePodProject(fixture(base),POD_TARGETS,defaults).assets).toEqual({});
  for (const assets of [{"../body.ttf":"font.ttf"},{"fonts/missing.ttf":"absent.ttf"},[],null]) {
    expect(() => resolvePodProject(fixture({...base,resources:{assets}}),POD_TARGETS,defaults)).toThrow("assets");
  }
});
