# Watch UI and navigation

`@podjs/framework/watch-ui` exports `WatchProgressIndicator` and its pure
`watchProgressArc(elapsedMs, strokeWidth = 3)` animation (6660 ms cycle).
Use `<WatchProgressIndicator size={18} active={() => visible()} color="#ff6026"
accessibilityLabel="Loading image" />`. Defaults are size 40, stroke 3, white
foreground, `#b6b6b659` track and `Loading` label. Supply localized labels and
application colors. Bind `active` to visibility/lifecycle: pausing clears the
frame clock, resuming excludes background time, disposal unregisters frames.
The component renders two axis-aligned round-cap arcs and busy accessibility.

`@podjs/framework/navigation` exports `BackMotion`. Construct `new BackMotion(width)`
in logical pixels, call `begin(dx)` / `move(dx)` while dragging, then
`release(velocity, now, cancelled?, forceCommit?)`. On each frame call `tick(now)`;
apply `offset` to the page and pop only when the result is `"commit"` (otherwise
`"cancel"` or undefined). Use the same time unit (milliseconds) throughout and
`reset()` when replacing navigation state. Distance threshold is 40%; a flick
above 500 commits from 12%. Cancellation overrides forceCommit; release uses
100–220 ms cubic easing. Routing and page ownership stay with the caller.

`@podjs/framework/services` already exports `hasTextureFileUpload()` and
`uploadTextureFile(path, width, height, psm)`. With support, transfer ownership of
a host-generated RGBA temporary file before calling upload: the host consumes
it even on rejection or exception. A negative handle means failure, not fallback.
Without support, upload returns null and the caller retains ownership: read the
bytes, upload via host ops, and clean up its temporary file. Never remove the
original source image as part of decoder-file cleanup.

## Round QR layout preset

`@podjs/framework/watch-ui` also exports `safeQrLayout(width, height)` and
`rectInsideCircle(rect, {cx, cy, radius}, margin = 1)`, plus `QrRect` and
`SafeQrLayout`. These are pure geometry helpers; they neither encode QR data nor
allocate textures. The layout is a 233-unit compatibility preset scaled to the
smaller viewport dimension, centered in the viewport. It returns title, QR,
status and action rectangles plus scale/circle metadata. The preset contains
no labels, colors or business payload. Other screen structures can supply their
own rectangles to `rectInsideCircle`; do not force them into this preset.

The containment check tests all four corners, uses a 1e-6 tolerance and interprets
margin in viewport units. Pass `layout.scale` for a one-preset-unit inset. Existing
numeric behavior (including the layout's minimum diameter of 1) is retained;
callers should provide finite positive display dimensions. Layout tests do not
establish that a rendered QR is scannable.
