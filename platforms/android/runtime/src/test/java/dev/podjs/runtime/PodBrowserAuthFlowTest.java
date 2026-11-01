package dev.podjs.runtime;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.view.View;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;


/** Headless Android lifecycle proof. WebView is a shadow; this makes no vendor requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30, manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodBrowserAuthFlowTest {
    private static Object field(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static final class Reply implements PodBrowserAuthServices.Callback {
        final List<String> codes=new ArrayList<>();int success;
        public void complete(JSONObject value){success++;}
        public void fail(String code,String message){codes.add(code);assertFalse(message.contains("PRIVATE"));}
    }
    private static JSONObject args()throws Exception {
        return new JSONObject().put("url","https://www.douyin.com/chat?isPopup=1")
            .put("cookieOrigin","https://www.douyin.com")
            .put("requiredCookieNames",new JSONArray().put("sessionid"))
            .put("qrExtractionScript","null")
            .put("qrWait",new JSONObject().put("message","Loading login QR"));
    }
    private static WebResourceRequest request(boolean main) {
        return new WebResourceRequest() {
            public android.net.Uri getUrl(){return android.net.Uri.parse("https://www.douyin.com/");}
            public boolean isForMainFrame(){return main;}
            public boolean isRedirect(){return false;}
            public boolean hasGesture(){return false;}
            public String getMethod(){return "GET";}
            public java.util.Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}
        };
    }
    @Test public void noPageCallbacksStillTimesOutAndRetryOwnsItsDialog()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply first=new Reply();service.authorize(args(),first,true);shadowOf(Looper.getMainLooper()).idle();
            Dialog dialog=(Dialog)field(service,"dialog");assertTrue(dialog.isShowing());
            assertEquals(View.VISIBLE,((View)field(service,"qrWaitView")).getVisibility());
            shadowOf(Looper.getMainLooper()).idleFor(75_000,TimeUnit.MILLISECONDS);
            assertEquals(java.util.Collections.singletonList("browser_qr_timeout"),first.codes);
            assertFalse(dialog.isShowing());assertNull(field(service,"webView"));assertNull(field(service,"deadlinePoll"));
            Reply retry=new Reply();service.authorize(args(),retry,true);shadowOf(Looper.getMainLooper()).idle();
            service.cancel(first);shadowOf(Looper.getMainLooper()).idle();assertTrue(retry.codes.isEmpty());
            assertTrue(((Dialog)field(service,"dialog")).isShowing());
            service.cancel(retry);shadowOf(Looper.getMainLooper()).idle();assertEquals(java.util.Collections.singletonList("cancelled"),retry.codes);
            shadowOf(Looper.getMainLooper()).idleFor(300_000,TimeUnit.MILLISECONDS);
            assertEquals(1,retry.codes.size());assertEquals(0,first.success+retry.success);
        }
    }
    @Test public void mainFrameErrorsSurfaceInLegacyAndStatusModesWithoutLoginCompletion()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        for(boolean status:new boolean[]{false,true})try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args(),reply,status);shadowOf(Looper.getMainLooper()).idle();
            WebView view=(WebView)field(service,"webView");
            WebResourceRequest request=request(true);
            WebResourceError error=null;
            view.getWebViewClient().onReceivedError(view,request,error);
            assertEquals(java.util.Collections.singletonList("webview_load_-1"),reply.codes);
            assertNull(field(service,"webView"));
        }
    }
    @Test public void httpFailureClosesAndSubresourceFailureDoesNot()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args(),reply,true);shadowOf(Looper.getMainLooper()).idle();
            WebView view=(WebView)field(service,"webView");WebResourceRequest request=request(false);
            WebResourceResponse response=new WebResourceResponse("text/html","UTF-8",403,"Forbidden",java.util.Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0]));
            view.getWebViewClient().onReceivedHttpError(view,request,response);assertTrue(reply.codes.isEmpty());
            request=request(true);view.getWebViewClient().onReceivedHttpError(view,request,response);
            assertEquals(java.util.Collections.singletonList("webview_http_403"),reply.codes);assertEquals(0,reply.success);
        }
    }
    @Test public void consoleErrorUsesClosedCodeAtDeadlineWithoutLoggingPageText()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args(),reply,true);shadowOf(Looper.getMainLooper()).idle();
            WebView view=(WebView)field(service,"webView");
            shadowOf(view).getWebChromeClient().onConsoleMessage(new ConsoleMessage("PRIVATE sessionid=secret", "PRIVATE-url", 1, ConsoleMessage.MessageLevel.ERROR));
            assertTrue(reply.codes.isEmpty());shadowOf(Looper.getMainLooper()).idleFor(75_000,TimeUnit.MILLISECONDS);
            assertEquals(java.util.Collections.singletonList("browser_script_error"),reply.codes);
            assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("PodBrowserAuth").stream().noneMatch(log->log.msg.contains("PRIVATE")));
        }
    }
    @Test(timeout=10000) public void browserDispatchStartsWhileGeneralWorkerAndSecureStoreAreBusy()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        List<String> replies=new java.util.concurrent.CopyOnWriteArrayList<>();
        PodServices services=new PodServices(activity,replies::add);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch holding=new java.util.concurrent.CountDownLatch(1);
        Field lockField=PodDeviceServices.class.getDeclaredField("SECURE_LOCK");lockField.setAccessible(true);
        Object lock=lockField.get(null);
        Thread restore=new Thread(()->{synchronized(lock){holding.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}});
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(services,"worker");
        java.util.concurrent.Future<?> busy=worker.submit(()->{try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        restore.start();assertTrue(holding.await(2,TimeUnit.SECONDS));
        try {
            services.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",77)
                .put("method","browser.authorizeWithStatus").put("args",args()));
            shadowOf(Looper.getMainLooper()).idle();
            PodBrowserAuthServices auth=(PodBrowserAuthServices)field(services,"browserAuth");
            assertTrue(((Dialog)field(auth,"dialog")).isShowing());assertFalse(busy.isDone());
            services.dispatch(new JSONObject().put("t","service.cancel").put("id",77));
            shadowOf(Looper.getMainLooper()).idle();assertNull(field(auth,"dialog"));
        } finally {release.countDown();restore.join(2000);busy.get(2,TimeUnit.SECONDS);services.close();}
    }

    private static void setCookies(String value) {
        android.webkit.CookieManager jar=android.webkit.CookieManager.getInstance();
        jar.removeAllCookies(null);if(value!=null)jar.setCookie("https://www.douyin.com",value);
    }
    @Test public void nativeCookiePollCompletesWithoutDomOrPageCallbacks()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();setCookies(null);
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args().put("completeOnRequiredCookies",true).put("lowMemory",true),reply,true);
            shadowOf(Looper.getMainLooper()).idle();WebView view=(WebView)field(service,"webView");
            assertTrue(view.getSettings().getMediaPlaybackRequiresUserGesture());assertFalse(view.getSettings().getUseWideViewPort());assertFalse(view.getSettings().getLoadsImagesAutomatically());
            setCookies("sessionid=SYNTHETIC");shadowOf(Looper.getMainLooper()).idleFor(250,TimeUnit.MILLISECONDS);
            assertEquals(1,reply.success);assertTrue(reply.codes.isEmpty());assertNull(field(service,"webView"));
            shadowOf(Looper.getMainLooper()).idleFor(300000,TimeUnit.MILLISECONDS);assertEquals(1,reply.success);
        }finally{setCookies(null);}
    }
    @Test public void nativeCookieStopsPostLoginHomepageNavigation()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();setCookies(null);
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args().put("completeOnRequiredCookies",true),reply,true);shadowOf(Looper.getMainLooper()).idle();
            WebView view=(WebView)field(service,"webView");setCookies("sessionid=SYNTHETIC");
            assertTrue(view.getWebViewClient().shouldOverrideUrlLoading(view,request(true)));
            assertEquals(1,reply.success);assertTrue(reply.codes.isEmpty());assertNull(field(service,"webView"));
        }finally{setCookies(null);}
    }
    @Test public void rendererExitPreservesCompletedCookieOtherwiseRetryHasIndependentOwnership()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        for(boolean cookie:new boolean[]{false,true}) {setCookies(null);
            try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
                Reply reply=new Reply();service.authorize(args().put("completeOnRequiredCookies",true),reply,true);shadowOf(Looper.getMainLooper()).idle();
                WebView old=(WebView)field(service,"webView");android.webkit.WebViewClient oldClient=old.getWebViewClient();if(cookie)setCookies("sessionid=SYNTHETIC");
                assertTrue(oldClient.onRenderProcessGone(old,null));
                assertEquals(cookie?1:0,reply.success);assertEquals(cookie?java.util.Collections.emptyList():java.util.Collections.singletonList("webview_renderer_gone"),reply.codes);
                assertNull(field(service,"webView"));
                setCookies(null);Reply retry=new Reply();service.authorize(args().put("completeOnRequiredCookies",true),retry,true);shadowOf(Looper.getMainLooper()).idle();
                WebView current=(WebView)field(service,"webView");oldClient.onRenderProcessGone(old,null);
                assertSame(current,field(service,"webView"));assertTrue(retry.codes.isEmpty());service.cancel(retry);shadowOf(Looper.getMainLooper()).idle();
            }finally{setCookies(null);}
        }
    }
    @Test public void navigationConsoleErrorReinjectsAfterFinishedWithoutExtendingDeadline()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();setCookies(null);
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args(),reply,true);shadowOf(Looper.getMainLooper()).idle();
            WebView view=(WebView)field(service,"webView");
            view.getWebViewClient().onPageStarted(view,"https://www.douyin.com/",null);
            shadowOf(view).getWebChromeClient().onConsoleMessage(new ConsoleMessage("PRIVATE", "PRIVATE", 1, ConsoleMessage.MessageLevel.ERROR));
            assertTrue(reply.codes.isEmpty());assertNull(shadowOf(view).getLastEvaluatedJavascript());
            view.getWebViewClient().onPageFinished(view,"https://www.douyin.com/");shadowOf(Looper.getMainLooper()).idle();
            assertEquals("null",shadowOf(view).getLastEvaluatedJavascript());assertTrue(reply.codes.isEmpty());
            shadowOf(Looper.getMainLooper()).idleFor(75000,TimeUnit.MILLISECONDS);assertEquals(java.util.Collections.singletonList("browser_script_error"),reply.codes);
        }
    }

    @Test public void extractorFailureRetriesOnNextFinishedPageAndOldCallbacksCannotAlterVerification()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();setCookies(null);
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args().put("lowMemory",true),reply,true);shadowOf(Looper.getMainLooper()).idle();
            WebView view=(WebView)field(service,"webView");
            view.getWebViewClient().onPageFinished(view,"https://www.douyin.com/");shadowOf(Looper.getMainLooper()).idle();
            android.webkit.ValueCallback<String> old=shadowOf(view).getLastEvaluatedJavascriptCallback();
            old.onReceiveValue("{\"scriptError\":true}");assertTrue(reply.codes.isEmpty());
            view.getWebViewClient().onPageStarted(view,"https://www.douyin.com/",null);
            view.getWebViewClient().onPageFinished(view,"https://www.douyin.com/");shadowOf(Looper.getMainLooper()).idle();
            android.webkit.ValueCallback<String> fresh=shadowOf(view).getLastEvaluatedJavascriptCallback();assertNotSame(old,fresh);
            fresh.onReceiveValue("{\"verifying\":true,\"loading\":false,\"loginPanelVisible\":true}");
            assertTrue(view.getSettings().getLoadsImagesAutomatically());
            old.onReceiveValue("null");assertTrue((Boolean)field(service,"verifying"));assertTrue(reply.codes.isEmpty());
            shadowOf(Looper.getMainLooper()).idleFor(300000,TimeUnit.MILLISECONDS);
            assertEquals(java.util.Collections.singletonList("browser_auth_timeout"),reply.codes);
        }
    }

    @Test public void benchmarkCookieCompletionUsesSameAttemptAtNativeObservationWithoutCredentialText()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();setCookies(null);
        long seq=PodBenchmark.begin("login_entry",new JSONObject().put("attempt",7));assertTrue(seq>0);
        try(PodBrowserAuthServices service=new PodBrowserAuthServices(activity)) {
            Reply reply=new Reply();service.authorize(args().put("completeOnRequiredCookies",true).put("benchmark",new JSONObject().put("seq",seq).put("attempt",7)),reply,true);
            shadowOf(Looper.getMainLooper()).idle();setCookies("sessionid=PRIVATE_FIXTURE");shadowOf(Looper.getMainLooper()).idleFor(250,TimeUnit.MILLISECONDS);
            assertEquals(1,reply.success);
            List<org.robolectric.shadows.ShadowLog.LogItem> logs=org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH");
            assertTrue(logs.stream().anyMatch(x->x.msg.contains("code=scan_accepted seq="+seq)&&x.msg.contains(" attempt=7")&&x.msg.contains(" mono_ms=")));
            assertTrue(logs.stream().noneMatch(x->x.msg.contains("PRIVATE")||x.msg.contains("sessionid")||x.msg.contains("https")));
        }finally{setCookies(null);}
    }
    @Test public void benchmarkAuthClearUsesActualSuccessfulSecureDeleteAndNoCredentials()throws Exception {
        java.io.File root=java.nio.file.Files.createTempDirectory("dyui-bench").resolve("files").toFile();
        javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        long seq=PodBenchmark.begin("logout_start",new JSONObject());
        try(PodDeviceServices service=new PodDeviceServices(root,()->key)) {
            service.execute("secure.set",new JSONObject().put("key","fixture").put("value","PRIVATE"));
            int before=org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").size();
            service.execute("secure.delete",new JSONObject().put("key","fixture").put("benchmark",new JSONObject().put("seq",seq)));
            assertFalse(service.execute("secure.get",new JSONObject().put("key","fixture")).getBoolean("exists"));
            List<org.robolectric.shadows.ShadowLog.LogItem> logs=org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH");
            assertEquals(before+1,logs.size());assertTrue(logs.get(logs.size()-1).msg.startsWith("code=auth_cleared seq="+seq+" mono_ms="));assertFalse(logs.get(logs.size()-1).msg.contains("fixture"));
        }
    }
    @Test public void qrBenchmarkWaitsForFrameCommitAndHonorsCurrentAttemptGuard()throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        class Image extends android.widget.ImageView {
            Image(){super(activity);}public boolean isAttachedToWindow(){return true;}public boolean isHardwareAccelerated(){return true;}public boolean isShown(){return true;}public boolean hasWindowFocus(){return true;}
        }
        long seq=PodBenchmark.begin("login_entry",new JSONObject().put("attempt",9));
        PodBrowserAuthBenchmark trace=PodBrowserAuthBenchmark.read(new JSONObject().put("benchmark",new JSONObject().put("seq",seq).put("attempt",9)));assertNotNull(trace);
        Image image=new Image();java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
        trace.qrPresented(image,current::get);assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").stream().noneMatch(x->x.msg.startsWith("code=qr_ready")));
        java.lang.reflect.Method capture=android.view.ViewTreeObserver.class.getDeclaredMethod("captureFrameCommitCallbacks");capture.setAccessible(true);
        @SuppressWarnings("unchecked") List<Runnable> callbacks=(List<Runnable>)capture.invoke(image.getViewTreeObserver());assertNotNull(callbacks);for(Runnable callback:callbacks)callback.run();
        assertEquals(1,org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").stream().filter(x->x.msg.startsWith("code=qr_ready seq="+seq)).count());
        long old=PodBenchmark.begin("login_entry",new JSONObject().put("attempt",10));
        PodBrowserAuthBenchmark stale=PodBrowserAuthBenchmark.read(new JSONObject().put("benchmark",new JSONObject().put("seq",old).put("attempt",10)));
        stale.qrPresented(image,()->false);
        @SuppressWarnings("unchecked") List<Runnable> abandoned=(List<Runnable>)capture.invoke(image.getViewTreeObserver());for(Runnable callback:abandoned)callback.run();
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").stream().noneMatch(x->x.msg.startsWith("code=qr_ready seq="+old)));
    }

}
