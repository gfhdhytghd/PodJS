/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Compatibility with Android Compose 1.7.1, independent of Android's
 * platform SP conversion. Both Density(context) and Density(2f, fontScale) use
 * this converter on Android, including API 30.
 *
 * Ported from the Apache-2.0 AndroidX 1.7.1 sources:
 * https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.7.1/ui-unit-android-1.7.1-sources.jar
 * androidMain/androidx/compose/ui/unit/{FontScaling.android.kt, fontscaling/*}
 * https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.7.1/ui-text-android-1.7.1-sources.jar
 * androidMain/androidx/compose/ui/text/platform/extensions/SpannableExtensions.android.kt
 *
 * Keep Float arithmetic and the factory's scale-key cache: arbitrary scales in
 * the same integer key can share a previously generated table. In particular,
 * Float(1.05) * 100 rounds below 105, so it shares key 104 with 1.04.
 */

/** Kotlin/JVM Char.isWhitespace = Character.isWhitespace || isSpaceChar.
 * Includes U+001C..U+001F, excludes U+0085, U+180E and U+FEFF. */
function kotlinWhitespace(code:number):boolean {
  return code>=0x09&&code<=0x0d||code>=0x1c&&code<=0x20||code===0xa0||code===0x1680||code>=0x2000&&code<=0x200a||code===0x2028||code===0x2029||code===0x202f||code===0x205f||code===0x3000;
}
export function kotlinTrim(value: string): string {
  let start=0,end=value.length;
  while(start<end&&kotlinWhitespace(value.charCodeAt(start)))start++;
  while(end>start&&kotlinWhitespace(value.charCodeAt(end-1)))end--;
  return start===0&&end===value.length?value:value.slice(start,end);
}
/** A blank check can stop at the first non-space without copying a long body. */
export function kotlinIsBlank(value:string):boolean {
  for(let i=0;i<value.length;i++)if(!kotlinWhitespace(value.charCodeAt(i)))return false;
  return true;
}

const f32 = Math.fround;
const COMMON_SP = [8, 10, 12, 14, 18, 20, 24, 30, 100];
const BASE_TABLES: ReadonlyArray<readonly [number, readonly number[]]> = [
  [1.15, [9.2, 11.5, 13.8, 16.4, 19.8, 21.8, 25.2, 30, 100]],
  [1.3, [10.4, 13, 15.6, 18.8, 21.6, 23.6, 26.4, 30, 100]],
  [1.5, [12, 15, 18, 22, 24, 26, 28, 30, 100]],
  [1.8, [14.4, 18, 21.6, 24.4, 27.6, 30.8, 32.8, 34.8, 100]],
  [2, [16, 20, 24, 26, 30, 34, 36, 38, 100]],
];

interface Table { sp: readonly number[]; dp: readonly number[] }

function lerp(start: number, stop: number, amount: number): number {
  return f32(start + f32(f32(stop - start) * amount));
}

function constrainedMap(start: number, stop: number, from: number, to: number, value: number): number {
  const amount = from === to ? 0 : f32(f32(value - from) / f32(to - from));
  return lerp(start, stop, Math.max(0, Math.min(1, amount)));
}

function lookup(value: number, source: readonly number[], target: readonly number[]): number {
  const magnitude = Math.abs(value), sign = Math.sign(value);
  let low = 0, high = source.length - 1;
  while (low <= high) {
    const middle = (low + high) >>> 1;
    if (source[middle]! < magnitude) low = middle + 1;
    else if (source[middle]! > magnitude) high = middle - 1;
    else return f32(sign * target[middle]!);
  }
  const lower = low - 1;
  if (lower >= source.length - 1) {
    const lastSource = source[source.length - 1]!;
    return lastSource === 0 ? 0 : f32(value * f32(target[target.length - 1]! / lastSource));
  }
  const startSource = lower < 0 ? 0 : source[lower]!;
  const startTarget = lower < 0 ? 0 : target[lower]!;
  return f32(sign * constrainedMap(startTarget, target[lower + 1]!, startSource, source[lower + 1]!, magnitude));
}

const scaleKey = (scale: number) => Math.trunc(f32(scale * 100));

/** A separate instance models a fresh Compose process, including its table
 * cache. The exported convenience functions below share one application cache.
 * Inputs have the same units as Compose: density is PX/DP and fontScale is the
 * configuration's dimensionless Float, not scaledDensity.
 */
export function createComposeFontScale() {
  const tables = new Map<number, Table>(BASE_TABLES.map(([scale, dp]) => [
    scaleKey(f32(scale)), { sp: COMMON_SP, dp: dp.map(f32) },
  ]));

  function converter(scale: number): Table | undefined {
    if (scale < f32(1.03)) return undefined;
    const key = scaleKey(scale), cached = tables.get(key);
    if (cached) return cached;
    const keys = [...tables.keys()].sort((a, b) => a - b);
    const higher = keys.findIndex(candidate => candidate > key);
    let table: Table;
    if (higher < 0) {
      // Beyond the currently cached maximum, the AndroidX factory is linear.
      table = { sp: [1], dp: [scale] };
    } else {
      const lowerKey = keys[higher - 1], higherKey = keys[higher]!;
      const start = lowerKey === undefined ? { sp: COMMON_SP, dp: COMMON_SP } : tables.get(lowerKey)!;
      const startScale = lowerKey === undefined ? 1 : f32(lowerKey / 100);
      const end = tables.get(higherKey)!;
      const amount = constrainedMap(0, 1, startScale, f32(higherKey / 100), scale);
      table = {
        sp: COMMON_SP,
        dp: COMMON_SP.map(sp => lerp(lookup(sp, start.sp, start.dp), lookup(sp, end.sp, end.dp), amount)),
      };
    }
    tables.set(key, table);
    return table;
  }

  function spToDp(sp: number, scale: number): number {
    const table = converter(scale);
    return table ? lookup(sp, table.sp, table.dp) : f32(sp * scale);
  }

  function dpToSp(dp: number, fontScale: number): number {
    const value = f32(dp), scale = f32(fontScale), table = converter(scale);
    return table ? lookup(value, table.dp, table.sp) : f32(value / scale);
  }

  function spToPx(sp: number, density: number, fontScale: number): number {
    return f32(spToDp(f32(sp), f32(fontScale)) * f32(density));
  }

  /** Resolves an SP line height against the actual TextPaint size in PX. EM
   * line heights instead multiply fontSizePx by their EM value directly.
   * This is the requested span height; final line metrics still apply trimming.
   */
  function lineHeightPx(lineHeightSp: number, fontSizePx: number, density: number, fontScale: number): number {
    const scale = f32(fontScale), sizePx = f32(fontSizePx);
    // Compose compares the Float configuration to a Double 1.05 literal.
    if (scale <= 1.05) return spToPx(lineHeightSp, density, scale);
    const fontSizeSp = dpToSp(f32(sizePx / f32(density)), scale);
    return f32(f32(f32(lineHeightSp) / fontSizeSp) * sizePx);
  }

  return { spToPx, dpToSp, lineHeightPx };
}

const applicationFontScale = createComposeFontScale();
export const composeSpToPx = applicationFontScale.spToPx;
export const composeDpToSp = applicationFontScale.dpToSp;
export const composeLineHeightPx = applicationFontScale.lineHeightPx;
