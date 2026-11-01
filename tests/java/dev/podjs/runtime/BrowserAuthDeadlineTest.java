package dev.podjs.runtime;

public final class BrowserAuthDeadlineTest {
    public static void main(String[] args) {
        PodBrowserAuthDeadline wait = new PodBrowserAuthDeadline(10, true);
        assert wait.failure(75_009) == null;
        assert "browser_qr_timeout".equals(wait.failure(75_010));
        wait.scriptError();
        assert "browser_script_error".equals(wait.failure(75_010));
        PodBrowserAuthDeadline ready = new PodBrowserAuthDeadline(0, true);
        ready.state(1000, true, false);
        assert ready.failure(100_000) == null;
        ready.state(100_000, false, true);
        assert ready.failure(200_000) == null;
        ready.state(200_000, false, false);
        // Repeated page-finished/empty snapshots must not extend acquisition.
        ready.state(250_000, false, false);
        assert ready.failure(274_999) == null;
        assert "browser_qr_timeout".equals(ready.failure(275_000));
        assert "browser_auth_timeout".equals(ready.failure(300_000));
        PodBrowserAuthDeadline manual = new PodBrowserAuthDeadline(0, false);
        assert manual.failure(299_999) == null;
        assert "browser_auth_timeout".equals(manual.failure(300_000));
        // Fresh retries own a new clock; an old timed-out session cannot reset it.
        PodBrowserAuthDeadline retry = new PodBrowserAuthDeadline(300_000, true);
        assert retry.failure(374_999) == null;
        assert "browser_qr_timeout".equals(retry.failure(375_000));
        System.out.println("BrowserAuthDeadlineTest passed");
    }
}
