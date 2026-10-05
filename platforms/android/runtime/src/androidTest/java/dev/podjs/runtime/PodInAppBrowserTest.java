package dev.podjs.runtime;

import android.app.Activity;
import android.content.Intent;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodInAppBrowserTest {
    private JSONObject args(String url) throws Exception { return new JSONObject().put("url",url).put("mode","inApp"); }
    @Test public void localPathsAreEncodedAndDoNotRequireInternet() throws Exception {
        String url = new PodInAppBrowser.Options(args(" /data/local/a b#c.html ")).url;
        assertEquals("file:///data/local/a%20b%23c.html",url);
        assertFalse(PodInAppBrowser.needsInternet(url));
        assertTrue(PodInAppBrowser.needsInternet("HTTPS://example.org"));
        for (Object invalid : new Object[]{"javascript:alert(1)", "data:text/html,test", "content://private/path", "file://remote/path", "https://", "", 42}) {
            try { PodInAppBrowser.resolveUrl(invalid); fail("accepted " + invalid); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void scaleModesUseNativeViewportGeometryForRoundAndSquareScreens() {
        assertArrayEquals(new int[]{430,430},PodInAppBrowser.viewport(466,466,18,18,"standard"));
        assertArrayEquals(new int[]{466,466},PodInAppBrowser.viewport(466,466,18,18,"expanded"));
        assertArrayEquals(new int[]{330,330},PodInAppBrowser.viewport(466,466,18,18,"shrunk"));
        assertArrayEquals(new int[]{364,484},PodInAppBrowser.viewport(400,500,18,8,"standard"));
        assertEquals("expanded",PodInAppBrowser.nextScale("standard"));
        assertEquals("shrunk",PodInAppBrowser.nextScale("expanded"));
        assertEquals("standard",PodInAppBrowser.nextScale("shrunk"));
    }
    @Test public void bridgeOptionsAreValidatedWithoutProviderOrNetworkSideEffects() throws Exception {
        for (JSONObject options : new JSONObject[]{args("https://example.org").put("scaleMode","huge"),args("https://example.org").put("theme",new JSONObject().put("dark","yes")),args("https://example.org").put("theme",new JSONObject().put("dark",true).put("textZoom",0)),args("https://example.org").put("unexpected",true)}) {
            try {new PodInAppBrowser.Options(options);fail("accepted invalid options");} catch (IllegalArgumentException expected) { }
        }
        PodInAppBrowser.Options options = new PodInAppBrowser.Options(args("file:///android_asset/local.html"));
        assertTrue(options.dark); assertEquals(100,options.textZoom);assertEquals("",options.font);
    }
    @Test public void serviceBridgeRoutesEmbeddedRequestsAndRejectsInvalidArguments() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<PodServices> service = new AtomicReference<>();
        AtomicReference<JSONObject> response = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> service.set(new PodServices(instrumentation.getTargetContext(), line -> {
            try {response.set(new JSONObject(line));done.countDown();} catch (Exception error) {throw new AssertionError(error);}
        })));
        try {
            JSONObject request = new JSONObject().put("t","service.request").put("version",1).put("id",31)
                .put("method","browser.openInApp").put("args",args("javascript:alert(1)"));
            instrumentation.runOnMainSync(() -> {try {service.get().dispatch(request);} catch(Exception error) {throw new AssertionError(error);} });
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(response.get().getBoolean("ok"));
            assertEquals("invalid_argument",response.get().getString("code"));
        } finally {instrumentation.runOnMainSync(() -> service.get().close());}
    }
    @Test public void actualWebViewSettingsAndRepeatedDestroyAreSafe() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getContext(), VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            PodInAppBrowser browser = null;
            try {
                org.junit.Assume.assumeTrue(WebView.getCurrentWebViewPackage() != null);
                JSONObject theme = new JSONObject().put("dark",true).put("textZoom",125).put("fontFamily","serif");
                browser = new PodInAppBrowser(activity,new PodInAppBrowser.Options(args("file:///android_asset/local.html").put("theme",theme)));
                browser.open();
                assertTrue(browser.isShowing());
                assertTrue(browser.web.getSettings().getJavaScriptEnabled());
                assertTrue(browser.web.getSettings().getDomStorageEnabled());
                assertEquals(125,browser.web.getSettings().getTextZoom());
                assertEquals("serif",browser.web.getSettings().getStandardFontFamily());
                assertFalse(browser.web.getSettings().getAllowUniversalAccessFromFileURLs());
                browser.onActivityPaused(activity);browser.onActivityResumed(activity);
                browser.back();assertFalse(browser.isShowing());assertNull(browser.web);
                browser.dismiss();assertNull(browser.web);
            } catch (Throwable error) {failure.set(error);}
            finally {if(browser!=null)browser.dismiss();activity.finish();}
        });
        if (failure.get() instanceof org.junit.AssumptionViolatedException) throw (org.junit.AssumptionViolatedException)failure.get();
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    @Test public void actualHistoryReturnsToPreviousPageBeforeClosing() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getContext(), VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.io.File first = java.io.File.createTempFile("f3-first", ".html", activity.getCacheDir());
        java.io.File second = java.io.File.createTempFile("f3-second", ".html", activity.getCacheDir());
        java.nio.file.Files.write(first.toPath(), "<html><body>first</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(second.toPath(), "<html><body>second</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<PodInAppBrowser> reference = new AtomicReference<>();
        AtomicReference<java.util.concurrent.CountDownLatch> loaded = new AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String firstUrl = android.net.Uri.fromFile(first).toString();
        try {
            instrumentation.runOnMainSync(() -> {
                try {
                    org.junit.Assume.assumeTrue(WebView.getCurrentWebViewPackage() != null);
                    PodInAppBrowser browser = new PodInAppBrowser(activity,new PodInAppBrowser.Options(args(firstUrl)));
                    reference.set(browser);browser.open();
                    browser.web.setWebViewClient(new android.webkit.WebViewClient() {
                        @Override public void onPageFinished(WebView view, String url) { loaded.get().countDown(); }
                    });
                } catch (Throwable error) {failure.set(error);}
            });
            if (failure.get() instanceof org.junit.AssumptionViolatedException) throw (org.junit.AssumptionViolatedException)failure.get();
            if (failure.get()!=null) throw new AssertionError(failure.get());
            assertTrue(loaded.get().await(10,java.util.concurrent.TimeUnit.SECONDS));
            loaded.set(new java.util.concurrent.CountDownLatch(1));
            instrumentation.runOnMainSync(() -> reference.get().web.loadUrl(android.net.Uri.fromFile(second).toString()));
            assertTrue(loaded.get().await(10,java.util.concurrent.TimeUnit.SECONDS));
            loaded.set(new java.util.concurrent.CountDownLatch(1));
            instrumentation.runOnMainSync(() -> { assertTrue(reference.get().web.canGoBack()); reference.get().onBackPressed(); assertTrue(reference.get().isShowing()); });
            assertTrue(loaded.get().await(10,java.util.concurrent.TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> {
                assertEquals(firstUrl,reference.get().web.getUrl());
                reference.get().back(); assertFalse(reference.get().isShowing()); assertNull(reference.get().web);
            });
        } finally {
            instrumentation.runOnMainSync(() -> { if(reference.get()!=null)reference.get().dismiss(); activity.finish(); });
            first.delete();second.delete();
        }
    }

}
