# Watch action components

Import `SwipeActionCard` and `WatchConfirmDialog` from `@podjs/framework/watch-ui`.
These are compatibility presets: fixed pill and dialog geometry is intentional.
Neither component owns application routing, deletion, favorites, or assets.

## SwipeActionCard

Pass `actions: {label, icon, right, onPress, accessibilityLabel}[]`, `revealWidth`,
`open`, `swipeEnabled`, `onOpen`, `onStartSwipe`, `onOpenSwipe`, `onCloseSwipe`,
and children. `onActions`, `cardClass`, and `edgeSwipe` are optional. The caller
controls which row is open. `right` positions a 72-unit pill; labels, icon paths
and accessible names are supplied by the application. The default card and pill
colors are neutral watch UI presets.

The recognizer retains x-axis arbitration, 8-unit slop, half-reveal threshold,
180 ms quadratic easing, cancellation close, and edge forwarding. An edge
candidate does not move the card. Open action pills remain focusable according
to `open`; disabling swiping animates the card closed without changing that
historical accessibility rule. Frame time uses Date.now through the existing
scoped onFrame hook. The component adds no alternate clock or gesture service.

## WatchConfirmDialog

Pass viewport `width`/`height`, `title`, `message`, `busy`, `measureLines`,
`confirm`/`cancel` objects `{icon, label, class}`, and `onConfirm`/`onCancel`.
The 466-unit dialog preset scales geometry by min(width, height)/466. Its text
uses a 193-unit layout width and existing two-times text scaling. The caller
supplies line breaking, so font measurement and compatibility policies stay
outside the component. Both button callbacks and focusability honor `busy`;
the full-screen backdrop consumes presses. `class` styles the button container
and should include absolute positioning and the desired shape/color.

## Startup exit frame

`@podjs/framework/startup` exports `startupExitFrame(elapsedMs)`,
`StartupExitFrame`, and `STARTUP_EXIT_DURATION_MS` (300). This pure preset returns
opacity, scale and finished with smoothstep interpolation and 1.08 final scale.
Nonfinite times map to the initial frame; negative times clamp to zero.
Overlay mounting, readiness, onboarding and navigation remain caller concerns.

## Verification boundary

Framework contract tests use caller-supplied assets/labels, alternate reveal
width, busy transitions and the default Date clock. App migration tests compare
frozen source trees, styles, accessibility and event traces. Drawing primitives
and gesture dispatch are mocked in the component harness; real-device gesture
arbitration, visual appearance and frame presentation require device acceptance.
