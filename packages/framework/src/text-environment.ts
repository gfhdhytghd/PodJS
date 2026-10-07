import {getDisplayMetrics,getFontMetrics,hasCapability,type FontMetrics} from "./watch.ts";
/** One text configuration owns host facts, not each line. Invalidate after host, display or atlas replacement. */
export interface TextEnvironment {
  pixelScale: number;
  rasterSupported: boolean;
  fontMetrics(slot: number): FontMetrics | undefined;
  invalidate():void;
}
export function createTextEnvironment(): TextEnvironment {
  let display = getDisplayMetrics();
  const fonts = new Map<number, FontMetrics>();
  let rasterSupported=hasCapability("text.raster");
  return {
    get pixelScale(){return display.physicalWidth / display.logicalWidth;},
    get rasterSupported(){return rasterSupported;},
    invalidate(){fonts.clear();display=getDisplayMetrics();rasterSupported=hasCapability("text.raster");},
    fontMetrics(slot) {
      const cached = fonts.get(slot);
      if (cached) return cached;
      const metrics = getFontMetrics(slot);
      // A missing font can become available after an asynchronous load.
      if (metrics) fonts.set(slot, metrics);
      return metrics;
    },
  };
}
