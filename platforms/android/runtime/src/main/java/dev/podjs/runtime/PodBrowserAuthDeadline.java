package dev.podjs.runtime;

/** Monotonic authorization deadlines, independent of page callbacks and guest frames. */
final class PodBrowserAuthDeadline {
    static final long QR_TIMEOUT_MS = 75_000;
    static final long SESSION_TIMEOUT_MS = 300_000;
    private long waitingSince;
    private final long started;
    private boolean waiting;
    private boolean scriptError;
    PodBrowserAuthDeadline(long now, boolean expectsQr) {
        started = waitingSince = now;
        waiting = expectsQr;
    }
    void state(long now, boolean hasQr, boolean verifying) {
        boolean next = !hasQr && !verifying;
        if (next && !waiting) waitingSince = now;
        waiting = next;
    }
    boolean scriptError() { boolean first = !scriptError; scriptError = true; return first; }
    String failure(long now) {
        if (now - started >= SESSION_TIMEOUT_MS) return "browser_auth_timeout";
        if (waiting && now - waitingSince >= QR_TIMEOUT_MS)
            return scriptError ? "browser_script_error" : "browser_qr_timeout";
        return null;
    }
}
