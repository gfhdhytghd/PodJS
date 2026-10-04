import { describe, expect, test } from "bun:test";
import { POD_HOST_ABI, POD_MIN_HOST_ABI, POD_TARGETS } from "../packages/framework/src/targets.ts";

describe("PodJS target profiles", () => {
  test("pin one ABI and one logical viewport", () => {
    expect(POD_HOST_ABI).toBe(2);
    expect(POD_MIN_HOST_ABI).toBe(1);
    for (const target of Object.values(POD_TARGETS)) {
      expect(target.hostAbi).toBe(POD_HOST_ABI);
      expect(target.logicalViewport).toEqual({ width: 240, height: 240 });
      expect(new Set(target.capabilities).size).toBe(target.capabilities.length);
    }
  });

  test("select only renderer APIs available on each watch family", () => {
    expect(POD_TARGETS["android-watch"].renderer).toBe("vulkan-1.1");
    expect(POD_TARGETS["wearos-watch"].renderer).toBe("vulkan-1.1");
    expect(POD_TARGETS["watchos-watch"].renderer).toBe("spritekit");
    expect(POD_TARGETS["harmonyos-watch"].renderer).toBe("gles3");
  });

  test("does not claim back-button support on watchOS", () => {
    expect(POD_TARGETS["watchos-watch"].capabilities).not.toContain("input.back");
  });
});

test("Android package capability order matches the JNI boot contract",async()=>{
  const source=await Bun.file(new URL("../platforms/android/runtime/src/main/cpp/jni_bridge.cpp",import.meta.url)).text();
  const literal=source.match(/const char\* capabilities = ("[^\n]+");/);
  expect(literal).not.toBeNull();
  const capabilities=JSON.parse(JSON.parse(literal![1]));
  expect(capabilities).toEqual(POD_TARGETS["android-watch"].capabilities);
  expect(capabilities).toEqual(POD_TARGETS["wearos-watch"].capabilities);
});
