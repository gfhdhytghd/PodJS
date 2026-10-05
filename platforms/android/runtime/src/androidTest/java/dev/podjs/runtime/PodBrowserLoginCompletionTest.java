package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class PodBrowserLoginCompletionTest {
    private static PodBrowserAuthStatus state(boolean visible, boolean verifying) {
        return PodBrowserAuthStatus.parse("{\"loading\":false,\"loginPanelVisible\":" + visible + ",\"verifying\":" + verifying + "}");
    }
    @Test public void requiresObservedPanelThenCookieAndCompletedVerification() {
        PodBrowserLoginCompletion flow = new PodBrowserLoginCompletion();
        assertFalse(flow.accept(state(false, false), true));
        assertFalse(flow.accept(state(true, false), true));
        assertFalse(flow.accept(state(false, true), true));
        assertFalse(flow.accept(state(false, false), false));
        assertTrue(flow.accept(state(false, false), true));
        assertFalse(flow.accept(state(false, false), true));
    }
    @Test public void malformedLoadingAndSecondQrCannotCompleteAndRetryStartsFresh() {
        PodBrowserLoginCompletion flow = new PodBrowserLoginCompletion();
        assertFalse(flow.accept(state(true, false), true));
        assertFalse(flow.accept(PodBrowserAuthStatus.parse("null"), true));
        assertFalse(flow.accept(PodBrowserAuthStatus.parse("{\"loading\":true,\"loginPanelVisible\":false}"), true));
        assertFalse(flow.accept(PodBrowserAuthStatus.parse("{\"loading\":false,\"loginPanelVisible\":false,\"qr\":{\"kind\":\"secondVerify\",\"mime\":\"image/jpeg\",\"imageBase64\":\"cXI=\"}}"), true));
        assertTrue(flow.accept(state(false, false), true));
        assertFalse(new PodBrowserLoginCompletion().accept(state(false, false), true));
    }
}
