package dev.podjs.runtime;

import java.util.ArrayDeque;

/** Process-wide cooperative admission between foreground and headless guests.
 * Reserve on dispatch (never block main); enter on the service worker. A started
 * native unit runs to completion. Foreground bypasses the queued restore
 * backlog rather than waiting for an entire background window.
 * After eight foreground units admit one waiting background unit for progress.
 * The encrypted store's OS lock still supplies cross-process atomicity. */
final class HostWorkPriority {
    static final HostWorkPriority DEVICE = new HostWorkPriority();
    private final ArrayDeque<Ticket> foreground = new ArrayDeque<>(), background = new ArrayDeque<>();
    private Ticket active;
    private int foregroundBurst;
    synchronized Ticket reserve(boolean headless) {
        Ticket ticket = new Ticket(headless);
        (headless ? background : foreground).addLast(ticket);
        notifyAll();
        return ticket;
    }
    private Ticket next() {
        if (!background.isEmpty() && (foreground.isEmpty() || foregroundBurst >= 8)) return background.peekFirst();
        return foreground.peekFirst();
    }
    final class Ticket implements AutoCloseable {
        private final boolean headless;
        private boolean cancelled, entered;
        Ticket(boolean headless) { this.headless = headless; }
        void enter() throws InterruptedException {
            synchronized (HostWorkPriority.this) {
                while (!cancelled && (active != null || next() != this)) HostWorkPriority.this.wait();
                if (cancelled || Thread.currentThread().isInterrupted()) { cancel(); throw new InterruptedException("Host work cancelled"); }
                (headless ? background : foreground).remove(this);
                entered = true; active = this;
                foregroundBurst = headless ? 0 : foregroundBurst + 1;
            }
        }
        void cancel() {
            synchronized (HostWorkPriority.this) {
                cancelled = true;
                if (!entered) { foreground.remove(this); background.remove(this); }
                HostWorkPriority.this.notifyAll();
            }
        }
        public void close() {
            synchronized (HostWorkPriority.this) {
                if (active == this) active = null;
                entered = false; cancel();
            }
        }
    }
}
