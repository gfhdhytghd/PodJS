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

function graphemes(value: string): string[] {
  // Without joiners or extenders every code point is already a cluster.
  if (!/[\u200d\ufe0f\u0300-\u036f]/u.test(value)) return Array.from(value);
  const out: string[] = [];
  for (const cp of Array.from(value)) {
    const code = cp.codePointAt(0)!;
    const last = out[out.length - 1];
    if (last && (code === 0x200d || code === 0xfe0f || (code >= 0x300 && code <= 0x36f) || last.endsWith("‍"))) out[out.length - 1] = last + cp;
    else out.push(cp);
  }
  return out;
}

/** Splits one paragraph into unbreakable units; whitespace is kept as its own unit. */
export function breakUnits(paragraph: string): string[] {
  const units: string[] = [];
  let word = "";
  const flush = () => { if (word) { units.push(word); word = ""; } };
  for (const g of graphemes(paragraph)) {
    if (/^\s+$/u.test(g) && !CJK(g)) { flush(); units.push(g); continue; }
    if (CJK(g) && !NO_START.has(g) && !NO_END.has(g)) { flush(); units.push(g); continue; }
    if (g === "/") { flush(); word = g; continue; } // break opportunity before a slash
    if (WORD(g) && !NO_START.has(g)) { word += g; continue; }
    flush(); units.push(g);
  }
  flush();
  // Kinsoku: glue closing punctuation backwards and opening punctuation forwards.
  const glued: string[] = [];
  for (let i = 0; i < units.length; i++) {
    const unit = units[i];
    // Both punctuation sets contain BMP characters only.
    const first = unit[0];
    if (glued.length && NO_START.has(first) && !/^\s+$/u.test(glued[glued.length - 1])) glued[glued.length - 1] += unit;
    else glued.push(unit);
  }
  const result: string[] = [];
  for (let i = 0; i < glued.length; i++) {
    let unit = glued[i];
    while (i + 1 < glued.length && NO_END.has(unit[unit.length - 1]) && !/^\s+$/u.test(glued[i + 1])) unit += glued[++i];
    result.push(unit);
  }
  return result;
}

export function layoutUiText(text: string, options: LayoutOptions): string[] {
  const lines: string[] = [];
  for (const paragraph of text.replace(/\r\n?/g, "\n").split("\n")) {
    if (paragraph === "") { lines.push(""); continue; }
    let line = "";
    const push = () => { lines.push(line.replace(/\s+$/u, "")); line = ""; };
    for (const unit of breakUnits(paragraph)) {
      const space = /^\s+$/u.test(unit);
      if (!line && space) continue; // no leading whitespace on a wrapped line
      if (options.measure(line + unit) <= options.width) { line += unit; continue; }
      if (space) { push(); continue; }
      if (line) push();
      if (options.measure(unit) <= options.width) { line = unit; continue; }
      for (const g of graphemes(unit)) { // overlong unit: grapheme fallback
        if (line && options.measure(line + g) > options.width) push();
        line += g;
      }
    }
    if (line || !lines.length) lines.push(line.replace(/\s+$/u, ""));
  }
  return options.maxLines ? lines.slice(0, options.maxLines) : lines;
}
