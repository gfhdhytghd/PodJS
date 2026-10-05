package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodWebViewServicesTest {
    private static class Fake implements PodWebViewServices.Backend {
        String provider = "test.webview";
        String header = "sid=one; token=two; sid=three";
        boolean failInit, failCookies, failStorage, retain;
        int flushes, reads, initialized;
        final List<String> expired = new ArrayList<>(), deleted = new ArrayList<>();
        public String provider() { return provider; }
        public void initialize() { initialized++; if (failInit) throw new UnsatisfiedLinkError("missing provider library"); }
        public String cookies(String origin) {
            if (failCookies) throw new IllegalStateException("no cookies provider");
            return retain || reads++ == 0 ? header : null;
        }
        public void expire(String origin, String value) { expired.add(origin + " " + value); }
        public void flush() { flushes++; }
        public void deleteOrigin(String origin) {
            if (failStorage) throw new IllegalStateException("no storage provider");
            deleted.add(origin);
        }
    }
    private JSONObject args(String... origins) throws Exception { return new JSONObject().put("origins", new JSONArray(origins)); }

    @Test public void invalidBatchNeverMutatesEvenWithValidFirstOrigin() throws Exception {
        for (Object invalid : new Object[]{"http://douyin.com", "https://a.com/", "https://a.com/path", "https://u@a.com", "https://a.com?q", "https://a.com#x", "https://*.a.com", "https://a.com:0", "https://a.com:65536", "https://a.com:", 42, JSONObject.NULL}) {
            Fake fake = new Fake();
            JSONObject input = new JSONObject().put("origins", new JSONArray().put("https://douyin.com").put(invalid));
            try { new PodWebViewServices(fake).clearSiteData(input); fail("accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
            assertEquals(0, fake.reads); assertTrue(fake.expired.isEmpty()); assertTrue(fake.deleted.isEmpty());
        }
        for (JSONObject input : new JSONObject[]{new JSONObject(), args(), new JSONObject().put("origins", "*")}) {
            try { new PodWebViewServices(new Fake()).clearSiteData(input); fail("accepted invalid origins"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void clearsOnlyListedHostAndExplicitParentWithExpiryAndFlush() throws Exception {
        Fake fake = new Fake();
        JSONArray result = new PodWebViewServices(fake).clearSiteData(args("https://www.douyin.com", "https://douyin.com", "https://www.douyin.com"));
        assertEquals(2, result.length()); assertEquals(2, fake.flushes);
        assertEquals(java.util.Arrays.asList("https://www.douyin.com", "https://douyin.com"), fake.deleted);
        assertEquals(6, fake.expired.size()); // Two unique names, host-only + two listed domains.
        assertTrue(fake.expired.stream().allMatch(s -> s.contains("Max-Age=0; Path=/; Secure")));
        assertTrue(fake.expired.stream().anyMatch(s -> s.endsWith("Domain=.douyin.com")));
        assertEquals("root_cleared", result.getJSONObject(0).getJSONObject("cookies").getString("status"));
        assertEquals("deletion_requested", result.getJSONObject(0).getJSONObject("webStorage").getString("status"));
        Fake narrow = new Fake();
        new PodWebViewServices(narrow).clearSiteData(args("https://www.douyin.com"));
        assertFalse(narrow.expired.stream().anyMatch(s -> s.endsWith("Domain=.douyin.com")));
    }

    @Test public void partialFailuresAndRemainingCookiesAreReportedPerOrigin() throws Exception {
        Fake fake = new Fake(); fake.retain = true; fake.failStorage = true;
        JSONObject result = new PodWebViewServices(fake).clearSiteData(args("https://douyin.com")).getJSONObject(0);
        assertEquals("remaining", result.getJSONObject("cookies").getString("status"));
        assertEquals(2, result.getJSONObject("cookies").getJSONArray("remainingNames").length());
        assertEquals("failed", result.getJSONObject("webStorage").getString("status"));
        fake = new Fake(); fake.failCookies = true;
        result = new PodWebViewServices(fake).clearSiteData(args("https://douyin.com", "https://example.org")).getJSONObject(0);
        assertEquals("failed", result.getJSONObject("cookies").getString("status"));
        assertEquals(2, fake.deleted.size());
    }

    @Test public void providerAbsenceAndNativeInitializationFailureBecomeStatuses() throws Exception {
        Fake fake = new Fake(); fake.provider = null;
        assertEquals("no_provider", new PodWebViewServices(fake).availability().getString("status"));
        assertEquals(0, fake.initialized);
        fake.provider = "test.webview"; fake.failInit = true;
        assertEquals("initialization_failed", new PodWebViewServices(fake).availability().getString("status"));
        fake.failInit = false;
        assertEquals("available", new PodWebViewServices(fake).availability().getString("status"));
    }

    @Test public void realCookieManagerKeepsUnlistedSiteAndClearsListedRootCookie() throws Exception {
        // These reserved domains are confined to the instrumentation target's cookie store.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                PodWebViewServices service = new PodWebViewServices(InstrumentationRegistry.getInstrumentation().getTargetContext());
                org.junit.Assume.assumeTrue("WebView provider required", "available".equals(service.availability().getString("status")));
                android.webkit.CookieManager manager = android.webkit.CookieManager.getInstance();
                boolean accepted = manager.acceptCookie();
                try {
                    manager.setAcceptCookie(true);
                    manager.setCookie("https://f1-listed.example", "f1_session=yes; Path=/; Secure");
                    manager.setCookie("https://f1-unlisted.example", "f1_sentinel=keep; Path=/; Secure");
                    manager.flush();
                    assertTrue(manager.getCookie("https://f1-listed.example").contains("f1_session=yes"));
                    JSONObject result = service.clearSiteData(args("https://f1-listed.example")).getJSONObject(0);
                    assertEquals("root_cleared", result.getJSONObject("cookies").getString("status"));
                    assertTrue(manager.getCookie("https://f1-unlisted.example").contains("f1_sentinel=keep"));
                } finally {
                    manager.setCookie("https://f1-listed.example", "f1_session=; Max-Age=0; Path=/; Secure");
                    manager.setCookie("https://f1-unlisted.example", "f1_sentinel=; Max-Age=0; Path=/; Secure");
                    manager.flush(); manager.setAcceptCookie(accepted);
                }
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof org.junit.AssumptionViolatedException)
            throw (org.junit.AssumptionViolatedException)failure.get();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test public void bridgeRejectsInvalidOriginsAndReturnsRealProviderStatus() throws Exception {
        AtomicReference<PodServices> services = new AtomicReference<>();
        AtomicReference<JSONObject> response = new AtomicReference<>();
        AtomicReference<CountDownLatch> latch = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> services.set(new PodServices(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), line -> {
                try { response.set(new JSONObject(line)); latch.get().countDown(); }
                catch (Exception error) { throw new AssertionError(error); }
            })));
        try {
            for (String method : new String[]{"browser.clearSiteData", "browser.availability"}) {
                latch.set(new CountDownLatch(1));
                JSONObject command = new JSONObject().put("t", "service.request").put("version", 1).put("id", 1)
                    .put("method", method).put("args", args("http://invalid.test"));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try { services.get().dispatch(command); } catch (Exception error) { throw new AssertionError(error); }
                });
                assertTrue(latch.get().await(10, TimeUnit.SECONDS));
                if (method.equals("browser.clearSiteData")) assertEquals("invalid_argument", response.get().getString("code"));
                else {
                    assertTrue(response.get().getBoolean("ok"));
                    assertTrue(java.util.Arrays.asList("available", "no_provider", "initialization_failed")
                        .contains(response.get().getJSONObject("value").getString("status")));
                }
            }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> services.get().close()); }
    }
}
