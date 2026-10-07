import ts from "typescript";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { PROP } from "../../../vendor/pocketjs/contracts/spec/spec.ts";

export interface UnknownStyleProp { file: string; line: number; key: string }

/** Native PocketJS primitives whose `style` object is forwarded verbatim to spec PROP ids.
 * Other components (e.g. MediaSurface) define their own `style` contracts and are skipped. */
const PRIMITIVES = new Set(["View", "Text", "Image", "Sprite", "Screen", "Focusable"]);

/** Static guard: every key of an inline JSX `style={{...}}` object on a primitive must be a PocketJS spec PROP.
 * The renderer throws "unknown style prop" at runtime, which aborts the whole frame on device. */
export function findUnknownStyleProps(roots: readonly string[]): UnknownStyleProp[] {
  const known = new Set(Object.keys(PROP)), found: UnknownStyleProp[] = [];
  const visitFile = (file: string) => {
    const source = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const walk = (node: ts.Node) => {
      const tag = ts.isJsxAttribute(node) && (ts.isJsxOpeningElement(node.parent.parent) || ts.isJsxSelfClosingElement(node.parent.parent)) ? node.parent.parent.tagName.getText(source) : "";
      if (ts.isJsxAttribute(node) && PRIMITIVES.has(tag) && node.name.getText(source) === "style" && node.initializer && ts.isJsxExpression(node.initializer)
        && node.initializer.expression && ts.isObjectLiteralExpression(node.initializer.expression)) {
        for (const property of node.initializer.expression.properties) {
          if (!ts.isPropertyAssignment(property) && !ts.isShorthandPropertyAssignment(property)) continue;
          const name = property.name;
          const key = ts.isIdentifier(name) || ts.isStringLiteral(name) ? name.text : undefined;
          if (key && !known.has(key)) found.push({ file, line: source.getLineAndCharacterOfPosition(property.getStart(source)).line + 1, key });
        }
      }
      ts.forEachChild(node, walk);
    };
    walk(source);
  };
  const visit = (path: string) => {
    if (statSync(path).isDirectory()) { for (const entry of readdirSync(path)) if (entry !== "node_modules") visit(join(path, entry)); }
    else if (path.endsWith(".tsx")) visitFile(path);
  };
  for (const root of roots) visit(root);
  return found;
}
