import type { LayoutOptions } from "./text-wrap.ts";
import {lazyRegExpTest} from "./regexp.ts";

// Android StaticLayout-like line breaking for UI text (cards, titles, dialogs):
// Latin words stay whole, CJK breaks between characters, closing punctuation
// never starts a line, opening punctuation never ends one, and URLs may break
// before "/". A unit wider than the line falls back to grapheme splitting.

const CJK = lazyRegExpTest('[\\p{Script=Han}\\p{Script=Hiragana}\\p{Script=Katakana}\\p{Script=Hangul}　-〿＀-￯]','u');
/** Characters that must not begin a line (they attach to the preceding unit). */
const NO_START = new Set(Array.from("）〕］｝〉》」』】〙〗〟’”｠»、。，．：；？！…‥・ー々〻ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ%％)]},.:;?!"));
/** Characters that must not end a line (they attach to the following unit). */
const NO_END = new Set(Array.from("（〔［｛〈《「『【〘〖〝‘“｟«([{"));
const WORD = lazyRegExpTest("[\\p{L}\\p{N}\\p{M}'’\\-_.@#&=+~*$^|\\\\]",'u');

function* graphemeClusters(value: string): Generator<string> {
  // Without joiners or extenders every code point is already a cluster.
  if (!/[\u200d\ufe0f\u0300-\u036f]/u.test(value)) {
    yield* value;
    return;
  }
  let cluster = "";
  for (const cp of value) {
    const code = cp.codePointAt(0)!;
    if (cluster && (code === 0x200d || code === 0xfe0f || (code >= 0x300 && code <= 0x36f) || cluster.endsWith("‍"))) {
      cluster += cp;
    } else {
      if (cluster) yield cluster;
      cluster = cp;
    }
  }
  if (cluster) yield cluster;
}

function* rawUnits(paragraph: string): Generator<string> {
  let word = "";
  for (const cluster of graphemeClusters(paragraph)) {
    const cjk = CJK(cluster);
    const separate = cjk ? !NO_START.has(cluster) && !NO_END.has(cluster) : /^\s+$/u.test(cluster);
    if (!separate && cluster !== "/" && WORD(cluster) && !NO_START.has(cluster)) {
      word += cluster;
      continue;
    }
    if (word) yield word;
    word = "";
    if (cluster === "/") word = cluster; // break opportunity before a slash
    else yield cluster;
  }
  if (word) yield word;
}

// Kinsoku needs one unit of lookahead in each direction. Keep only the units
// that can still change, so a caller reading one line never tokenizes the tail.
function* glueClosing(units: Iterable<string>): Generator<string> {
  let previous = "";
  for (const unit of units) {
    if (previous && NO_START.has(unit[0]) && !/^\s+$/u.test(previous)) previous += unit;
    else {
      if (previous) yield previous;
      previous = unit;
    }
  }
  if (previous) yield previous;
}

function* lineUnits(paragraph: string): Generator<string> {
  let previous = "";
  for (const unit of glueClosing(rawUnits(paragraph))) {
    if (previous && NO_END.has(previous[previous.length - 1]) && !/^\s+$/u.test(unit)) previous += unit;
    else {
      if (previous) yield previous;
      previous = unit;
    }
  }
  if (previous) yield previous;
}

/** Splits one paragraph into unbreakable units; whitespace is kept as its own unit. */
export function breakUnits(paragraph: string): string[] {
  return Array.from(lineUnits(paragraph));
}

function fitsUnit(line: string, unit: string, width: number, measure: LayoutOptions["measure"], clusters?: string[]): boolean {
  if (clusters) {
    // Measuring a run can synchronously load every missing glyph. Grow the
    // probe only while it fits, so a long word does not load its entire tail
    // before the line iterator can yield.
    for (let end = 16; end < clusters.length; end *= 2) {
      if (measure(line + clusters.slice(0, end).join("")) > width) return false;
    }
  }
  return measure(line + unit) <= width;
}

/** Yield complete lines so a frame-driven caller can pause before measuring more. */
export function* layoutUiTextLines(text: string, { width, measure }: Omit<LayoutOptions, "maxLines">): Generator<string> {
  let emitted = false;
  for (const paragraph of text.replace(/\r\n?/g, "\n").split("\n")) {
    if (paragraph === "") {
      emitted = true;
      yield "";
      continue;
    }
    let line = "";
    for (const unit of lineUnits(paragraph)) {
      const space = /^\s+$/u.test(unit);
      if (!line && space) continue;
      const clusters = unit.length > 16 ? Array.from(graphemeClusters(unit)) : undefined;
      if (fitsUnit(line, unit, width, measure, clusters)) { line += unit; continue; }
      if (line || space) {
        emitted = true;
        yield line.trimEnd();
        line = "";
        if (!space && fitsUnit(line, unit, width, measure, clusters)) { line = unit; continue; }
      }
      if (space) continue;
      for (const g of clusters ?? graphemeClusters(unit)) {
        if (line && measure(line + g) > width) {
          emitted = true;
          yield line.trimEnd();
          line = "";
        }
        line += g;
      }
    }
    if (line || !emitted) {
      emitted = true;
      yield line.trimEnd();
    }
  }
}

export function layoutUiText(text: string, options: LayoutOptions): string[] {
  const lines: string[] = [];
  const max = options.maxLines;
  for (const line of layoutUiTextLines(text, options)) {
    lines.push(line);
    if (max && max > 0 && lines.length >= Math.trunc(max)) break;
  }
  return max ? lines.slice(0, max) : lines;
}
