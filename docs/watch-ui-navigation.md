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
