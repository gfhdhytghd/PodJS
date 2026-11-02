package dev.podjs.runtime;

import java.util.ArrayDeque;

/** Process-wide cooperative admission between foreground and headless guests.
 * Reserve on dispatch (never block main); enter on the service worker. A started
 * native unit runs to completion. Foreground bypasses the queued restore
 * backlog rather than waiting for an entire background window.
 * Outside foreground media, after eight foreground units admit one waiting
 * background unit for progress. Media suspends new background admissions.
 * The encrypted store's OS lock still supplies cross-process atomicity. */
final class HostWorkPriority {
    static final HostWorkPriority DEVICE = new HostWorkPriority();
    private final ArrayDeque<Ticket> foreground = new ArrayDeque<>(), background = new ArrayDeque<>();
    private Ticket active;
    private int foregroundBurst;
    private final java.util.Set<Object> mediaOwners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    synchronized boolean mediaActive() { return !mediaOwners.isEmpty(); }
    /** Foreground media holds admission across buffering and silent prewarms.
     * Already-started units finish atomically; queued background units wait. */
    synchronized void foregroundMedia(Object owner, boolean active) {
        if(active) mediaOwners.add(owner); else mediaOwners.remove(owner);
        notifyAll();
    }
    synchronized Ticket reserve(boolean headless) {
        Ticket ticket = new Ticket(headless);
        (headless ? background : foreground).addLast(ticket);
        notifyAll();
        return ticket;
    }
    private Ticket next() {
        if (!mediaActive() && !background.isEmpty() && (foreground.isEmpty() || foregroundBurst >= 8)) return background.peekFirst();
        return foreground.peekFirst();
    }
    synchronized void awaitBackground() throws InterruptedException {
        while(mediaActive())wait();
    }
    final class Ticket implements AutoCloseable {
        private final boolean headless;
        private boolean cancelled, entered;
        Ticket(boolean headless) { this.headless = headless; }
        /** Shared pumps must yield a queued background unit instead of blocking
         * the worker that also serves foreground media. Caller closes the ticket. */
        boolean tryEnter() {
            synchronized (HostWorkPriority.this) {
                if(cancelled||active!=null||next()!=this)return false;
                (headless?background:foreground).remove(this);
                entered=true;active=this;foregroundBurst=headless?0:foregroundBurst+1;
                return true;
            }
        }
        void enter() throws InterruptedException { enter(() -> true); }
        void enter(java.util.function.BooleanSupplier current) throws InterruptedException {
            synchronized (HostWorkPriority.this) {
                while (!cancelled && current.getAsBoolean() && (active != null || next() != this)) HostWorkPriority.this.wait(100);
                if (cancelled || !current.getAsBoolean() || Thread.currentThread().isInterrupted()) { cancel(); throw new InterruptedException("Host work cancelled"); }
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
