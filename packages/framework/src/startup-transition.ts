export const STARTUP_EXIT_DURATION_MS = 300;

export interface StartupExitFrame {
  opacity: number;
  scale: number;
  finished: boolean;
}

/** 300 ms smoothstep fade + 1.08x exit compatibility preset. */
export function startupExitFrame(elapsedMs: number): StartupExitFrame {
  const progress = Math.min(
    1,
    Math.max(0, Number.isFinite(elapsedMs) ? elapsedMs / STARTUP_EXIT_DURATION_MS : 0),
  );
  const eased = progress * progress * (3 - 2 * progress);
  return {
    opacity: 1 - eased,
    scale: 1 + 0.08 * eased,
    finished: progress >= 1,
  };
}
