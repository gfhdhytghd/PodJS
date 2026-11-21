export interface DockPoint { x: number; y: number }

const clamp = (value: number, low: number, high: number) =>
  high < low ? (low + high) / 2 : Math.max(low, Math.min(high, value));

function roundCenter(point: DockPoint, width: number, height: number, inset: number, project: boolean): DockPoint {
  const cx = width / 2, cy = height / 2;
  const radius = Math.max(0, Math.min(cx, cy) - inset);
  const dx = point.x - cx, dy = point.y - cy, distance = Math.hypot(dx, dy);
  if (!project && distance <= radius) return point;
  if (distance === 0) return { x: cx + radius, y: cy };
  return { x: cx + dx * radius / distance, y: cy + dy * radius / distance };
}

/** Keep the entire circular touch target in the display during motion. */
export function clampDockCenter(point: DockPoint, width: number, height: number, round: boolean, inset: number): DockPoint {
  if (round) return roundCenter(point, width, height, inset, false);
  return { x: clamp(point.x, inset, width - inset), y: clamp(point.y, inset, height - inset) };
}

/** Project to the nearest point on a round edge, or the nearest rectangular corner. */
export function edgeDockCenter(point: DockPoint, width: number, height: number, round: boolean, inset: number): DockPoint {
  if (round) return roundCenter(point, width, height, inset, true);
  const corner = {
    x: point.x <= width / 2 ? 0 : width,
    y: point.y <= height / 2 ? 0 : height,
  };
  return clampDockCenter(corner, width, height, false, inset);
}
