/** Material3 1.3.0 ProgressIndicator.kt (indeterminate overload), as used by
 * Android WatchMaterial.kt. Source artifact:
 * https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.3.0/material3-android-1.3.0-sources.jar
 */
export const WATCH_PROGRESS_CYCLE_MS = 6660;

/** Invert the x coordinate of cubic-bezier(.4, 0, .2, 1), then sample y. */
function circularEasing(fraction: number): number {
  if (fraction <= 0) return 0;
  if (fraction >= 1) return 1;
  let low = 0, high = 1;
  for (let step = 0; step < 24; step++) {
    const t = (low + high) / 2, inverse = 1 - t;
    const x = 1.2 * inverse * inverse * t + .6 * inverse * t * t + t * t * t;
    if (x < fraction) low = t; else high = t;
  }
  const t = (low + high) / 2;
  return 3 * (1 - t) * t * t + t * t * t;
}

/** Generic PodJS arc coordinates: zero is 12 o'clock, positive is clockwise.
 * Stroke width uses the same logical units as the component's size. Material3
 * computes cap correction against its default 40dp diameter,
 * including when the network row constrains the actual indicator to 18dp.
 */
export function watchProgressArc(elapsedMs: number, strokeWidth = 3): {arcStart: number; arcSweep: number} {
  const time = (Number.isFinite(elapsedMs) ? Math.max(0, elapsedMs) : 0) % WATCH_PROGRESS_CYCLE_MS;
  const phase = time % 1332;
  const head = 290 * circularEasing(phase / 666);
  const tail = 290 * circularEasing((phase - 666) / 666);
  const rotation = (Math.floor(time / 1332) * 216) % 360;
  const capOffset = 180 / Math.PI * strokeWidth / 40;
  return {
    // Compose's -90 degree offset is cancelled by PodJS's top-origin arc.
    arcStart: tail + rotation + 286 * phase / 1332 + capOffset,
    // A zero sweep also means "ordinary background box" to the renderer.
    arcSweep: Math.max(Math.abs(head - tail), .1),
  };
}
