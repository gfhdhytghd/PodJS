export const items = [
  { id: 1, title: "Welcome", description: "Your first watch app." },
  { id: 2, title: "Navigation", description: "Press Back to return." },
  { id: 3, title: "Storage", description: "Selection is saved in KV." },
];

/** A centered square inside the round screen, intersected with host safe insets. */
export function contentRect(width: number, height: number, insets: {left: number; right: number; top: number; bottom: number}) {
  const side = Math.min(width, height) * 0.70;
  const left = Math.max((width - side) / 2, insets.left);
  const top = Math.max((height - side) / 2, insets.top);
  return { left, top, width: Math.max(0, Math.min((width + side) / 2, width - insets.right) - left),
    height: Math.max(0, Math.min((height + side) / 2, height - insets.bottom) - top) };
}
