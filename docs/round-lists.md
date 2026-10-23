# Round lists

`VariableList` accepts `round: {width, height, margin?, viewport?}`. Rectangular,
`round: null`, fixed, and externally controlled lists retain their existing behavior.
The application's `ScrollEntries` wrapper can supply the display geometry once;
individual pages do not need top/bottom spacers.

Each `ScrollEntry` may describe geometry relative to its padded row origin:

```ts
{
  height: 48,
  visibleBounds: {x: 58.5, y: 14, width: 80, height: 20},
  interactionBounds: {x: -18, y: 0, width: 233, height: 48},
  render: () => /* an existing centered label and its transparent touch target */
}
```

`bounds` remains a compatible alias for visible bounds. If both are present,
`visibleBounds` wins. Omitted visible bounds conservatively use the whole padded
row. `interactionBounds` describes existing child hit targets; it does not add a
press handler. It may extend beyond the safe circle. The viewport clips it to
the display rectangle; `handle.interactionBounds(index)` returns that clipped
rectangle in display coordinates at the current scroll offset (or `undefined`
when outside the viewport). The circle solver reports impossible visible bounds
in `roundInsets().unfit` (and development warnings) rather than resizing content.

The safe vertical interval follows from the visible rectangle's farthest
horizontal corner: `dy = sqrt((radius - margin)^2 - dx^2)`. Interaction bounds do
not participate in this calculation. Start/end whitespace is derived from
these intervals, not from a fixed pixel constant. Every row must have a reachable
safe scroll position, the first row must be safe at the top endpoint, and the
last row must be safe at the bottom endpoint. This does not require a scrolled-away
header to stay inside the circle when the user reaches the bottom.
Already-safe short pages keep their layout and zero scroll range. Undeclared
spacer rows retain legacy reachability handling and are not rendered to infer
whether they contain content.

Partially visible rows passing through the circle's edge during scrolling are
normal. Oversized visible rows still need bounds/splitting; endpoint whitespace
cannot make an intrinsically oversized rectangle fit.
