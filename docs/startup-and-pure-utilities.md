# Startup overlay and pure utility APIs

These opt-in modules contain reusable mechanisms. Labels, assets, routing,
application policies, storage keys and error copy are supplied by the caller.
None changes host permissions or starts an application service.

## Startup overlay

```tsx
import {StartupOverlayTransition} from '@podjs/framework/startup-overlay';

<StartupOverlayTransition
  ready={contentReady}
  exit={mayFade}
  content={() => <Content />}
  overlay={elapsed => <LaunchSurface elapsedMs={elapsed()} />}
  durationMs={300}
  onExitStart={() => trace('fade-start')}
  onFinished={() => trace('fade-finished')}
/>
```

`ready` controls content mounting; `exit` begins the overlay clock independently.
The default duration is the existing 300ms startup compatibility preset; an
explicit duration chooses another completion boundary. Rendering/opacity/scale
belongs to the supplied overlay (the pure `startupExitFrame` preset remains
available from `startup`). The content factory is called by Solid's Show only
when ready; overlay stays mounted until finished. If exit becomes false, elapsed
and finished reset. Re-enabling exit records a fresh start; completion is emitted
once per uninterrupted fade. A clock moving backward clamps elapsed to zero.

Construct beneath a Solid owner. The production frame subscription removes
itself on owner cleanup. `clock` can override performance.now/Date.now for tests;
ordinary callers need not supply it. This is a guest overlay, not ownership of an
OS splash window or a GPU-presentation signal. No navigation or onboarding policy
is built in. Use the same owner/lifecycle as other framework UI components.
The separate JSX entry keeps pure startup trace/gate imports independent of UI.

```ts
import {restoreIfOwned} from '@podjs/framework/startup';
const cleanup = restoreIfOwned(applyTemporaryState, currentSnapshot, restoreState);
onCleanup(cleanup);
```

Apply runs before capturing its snapshot. Cleanup restores only when the current
snapshot is strictly identical to that token; a later publisher retains control.
The snapshot port must represent publications with stable identity. The helper
returns a closure and does not choose a lifecycle, state fields or restoration
values. It intentionally adds no repeated-cleanup suppression beyond the
publisher identity check; the caller's lifecycle is responsible for registration.

## Single-request HTTP reader

`@podjs/framework/http-request` exports `readHttpRequest(stream, options?)` and
`HttpReadStream`, `HttpRequest`, `HttpReadOptions`. A stream supplies
`read(max, remainingMs): Promise<Uint8Array|null>`. Options: `now` (Date.now by
default), `timeoutMs` (15000), `readBytes` (4096), `maxHeaderBytes` (16384),
`maxBodyBytes` (65536). Supply positive finite read/time limits and nonnegative
integer byte limits. Application adapters should explicitly pass their protocol
budgets. This helper neither writes responses nor closes/owns the stream.

It reads one origin-target HTTP/1.0 or HTTP/1.1 request, accepts Content-Length
or chunked framing, rejects duplicate/conflicting framing, and uses strict UTF-8
via the existing bytes module. Headers use a null-prototype object; duplicate
non-framing fields retain the last value. Missing Content-Length means zero body.
Chunk extensions are skipped; trailers are consumed without application routing.
`maxHeaderBytes` counts all line-reading bytes, including the start line, chunk
size/end lines and trailers. The whole-request deadline does not reset per read.
Empty/oversized/late reads fail. Extra bytes buffered after the request are not
returned. It is a bounded compatibility subset, not a general HTTP server,
pipelining interface or new validation policy. Stream/network errors propagate;
parser errors retain the existing Error name and English messages.

## JSON lexical numbers

`@podjs/framework/json-numbers` exports:

- `parseJsonObjectNumbers(text, {maxDepth?,objectError?,depthError?}?)` validates
  with JSON.parse, requires an object root, and records the original numeric
  tokens in a WeakMap associated with the returned objects/arrays. Default
  depth limit128; error factories allow a protocol adapter to preserve messages.
- `jsonNumberToken(parent,key)` retrieves a token such as `1.0`, `1e0` or `-0`.
- `isJsonInteger(parent,key)` requires a safe integer value and, when metadata
  exists, an integer token. For externally constructed objects it falls back to
  the numeric value; it cannot infer lexical information lost before parsing.

Duplicate fields follow the existing parsed-value/token traversal and remove
numeric metadata when the later token is nonnumeric. Metadata does not survive
cloning/serialization. This entry is pure, separate from JSON projection/text
layout and allowed in headless background bundles. No native acceleration,
streaming, cancellation or hard execution budget is claimed for this parser.
JSON.parse still validates the whole source before the depth-limited token walk.

## Text positions

`@podjs/framework/text-anchor` exports `TextPositionAnchor {character,fraction}`,
`lineStarts(text,lines)`, `anchorAtOffset(offset,lineHeight,starts)` and
`offsetForAnchor(anchor,lineHeight,starts,maximum)`. All character positions are
UTF-16 code units. CRLF/CR are normalized before matching; repeated lines match
from the previous cursor. Missing lines use that cursor; a newline after the
matched line advances it. Fractions survive line-height/width changes and are
clamped by offset restoration. The algorithm has no Markdown, document block,
media ID, persistence or layout ownership. Callers retain richer anchor types.

## Calendar day/night schedule

`@podjs/framework/theme-schedule` exports CalendarDay, SunTimes,
DailyThemeSchedule, `sunTimes`, `scheduledDark`, `scheduledDarkNow`.
DailyThemeSchedule contains mode FIXED_TIME/SUNRISE_SUNSET, lightStartMinutes,
darkStartMinutes and nullable latitude/longitude. It supplies no default schedule,
location request, OS dark-mode binding or stored location timestamp.

Solar calculations retain the existing approximation and event rounding.
`offsetAt(utcMillis)` uses Date.getTimezoneOffset's sign and is resolved separately
at sunrise/sunset; its default reads the local Date timezone. Invalid dates,
invalid coordinates and unavailable polar events return null, causing the
configured fixed interval fallback. Reversed intervals cross midnight; equal
switching times yield an empty light interval (always dark). scheduledDarkNow
uses a default new Date, or the supplied date, without timers or host polling.

## Bounded lines

`logging.BoundedLineBuffer({maxLines,maxChars,maxLineChars})` offers append,
size, dump and clear. Instances are independent; limits count UTF-16 units rather
than UTF-8 bytes. Each appended line retains its tail up to maxLineChars, then
oldest lines are removed until both total-char and line bounds hold. Joining
adds newlines only at dump time, not to accounting. Zero total capacity is valid;
invalid/noninteger/negative limits and nonpositive maxLineChars are rejected.
The caller must redact before append and enforce any build/debug access gate.
No host reads, disk writes or credential policy are introduced.

## Validation boundary

Framework tests exercise default dependency paths, frame-owner cleanup, limits,
metadata typing and headless execution. Consumer frozen-source tests can compare
business wrappers. Host tests and bundles do not establish visual, gesture,
process-restoration, physical device or GPU parity.
