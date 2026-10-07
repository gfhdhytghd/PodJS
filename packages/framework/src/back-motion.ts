/** Follow-finger navigation in logical pixels; only release is animated. */
export class BackMotion {
  offset = 0;
  phase: "idle" | "dragging" | "settling" = "idle";
  private start = 0;
  private from = 0;
  private target = 0;
  private duration = 0;
  constructor(readonly width: number) {}
  begin(dx: number) { this.phase = "dragging"; this.move(dx); }
  move(dx: number) { if (this.phase === "dragging") this.offset = Math.max(0, Math.min(this.width, dx)); }
  release(velocity: number, now: number, cancelled = false, forceCommit = false) {
    const commit = !cancelled && (forceCommit || this.offset >= this.width * 0.4 || (this.offset >= this.width * 0.12 && velocity > 500));
    this.from = this.offset;
    this.target = commit ? this.width : 0;
    this.start = now;
    this.duration = Math.max(100, Math.min(220, Math.abs(this.target - this.from) / this.width * 260));
    this.phase = "settling";
  }
  tick(now: number): "commit" | "cancel" | undefined {
    if (this.phase !== "settling") return;
    const t = Math.max(0, Math.min(1, (now - this.start) / this.duration));
    this.offset = this.from + (this.target - this.from) * (1 - (1 - t) ** 3);
    if (t < 1) return;
    this.phase = "idle";
    return this.target ? "commit" : "cancel";
  }
  reset() { this.offset = 0; this.phase = "idle"; }
}
