package dev.podjs.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

/** Executes the production client/lifecycle on the JVM; not a Chromium crash test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodSiteChallengeLifecycleTest {
    Context context;PodSiteChallengeServices service;
    final List<TrackedView> views=new ArrayList<>();
    static class Reply implements PodSiteChallengeServices.Callback {
        boolean active=true;int calls;String code;JSONObject value;
        public boolean isActive(){return active;}
        public void complete(JSONObject value){calls++;this.value=value;}
        public void fail(String code,String message){calls++;this.code=code;}
    }
    static class TrackedView extends WebView {
        final List<String> teardown=new ArrayList<>();boolean failStop,failScript;
        TrackedView(Context context){super(context);}
        @Override public void stopLoading(){teardown.add("stop");if(failStop)throw new IllegalStateException("dead renderer");super.stopLoading();}
        @Override public void evaluateJavascript(String script,ValueCallback<String> callback){if(failScript)throw new IllegalStateException("dead renderer");super.evaluateJavascript(script,callback);}
        @Override public void destroy(){assertNull(getParent());teardown.add("destroy");super.destroy();}
    }
    @Before public void setup() {
        context=ApplicationProvider.getApplicationContext();ShadowLog.clear();
        service=new PodSiteChallengeServices(context,c->{TrackedView v=new TrackedView(c);views.add(v);return v;});
    }
    @After public void close(){service.close();idle();}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    JSONObject options(String host,int timeout) throws Exception {
        return new JSONObject().put("url","https://"+host+"/private-path?secret=query-value")
            .put("userAgent","private-agent").put("cookieNames",new JSONArray().put("n2_token")).put("timeoutMs",timeout);
    }
    Reply acquire(String host,int timeout) throws Exception {Reply r=new Reply();service.dispatch(options(host,timeout),true,r);idle();return r;}
    JSONObject log(int index,String host,String outcome,int cookies) throws Exception {
        List<ShadowLog.LogItem> lines=ShadowLog.getLogsForTag("PodSiteChallenge");
        String message=lines.get(index).msg;JSONObject value=new JSONObject(message);
        assertEquals(4,value.length());assertEquals(host,value.getString("host"));assertEquals(outcome,value.getString("result"));
        assertEquals(cookies,value.getInt("cookies"));assertTrue(value.getLong("ms")>=0);
        for(String secret:new String[]{"private-path","query-value","private-agent","n2_token","token-secret"})assertFalse(message.contains(secret));
        return value;
    }
    @Test public void defaultConstructorUsesMeasuredDetachedDrawingSuppressedViewport() throws Exception {
        service.close();idle();
        service=new PodSiteChallengeServices(context);
        Reply r=new Reply();service.dispatch(options("default.example",10),true,r);idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        assertEquals("challenge_timeout",r.code);assertEquals(1,r.calls);log(0,"default.example","timeout",0);
        // Factory path runs the same configuration and lets us inspect ownership.
        try(PodSiteChallengeServices inspected=new PodSiteChallengeServices(context,c->{TrackedView v=new TrackedView(c);views.add(v);return v;})) {
            inspected.dispatch(options("viewport.example",1000),true,new Reply());idle();
            TrackedView v=views.get(0);assertNull(v.getParent());assertEquals(240,v.getMeasuredWidth());assertEquals(240,v.getMeasuredHeight());
            // Robolectric's WebView provider doesn't implement setFrame; real
            // getWidth/getHeight assertions live in instrumentation instead.
            assertTrue(v.willNotDraw());
            assertEquals(View.LAYER_TYPE_NONE,v.getLayerType());assertFalse(v.getSettings().getOffscreenPreRaster());
        }
        idle();
    }
    @Test public void rendererDeathDetachesOnlyOwnerCompletesOnceAndStartsQueuedChallenge() throws Exception {
        Reply first=acquire("gone.example",1000),next=acquire("next.example",2000);
        TrackedView old=views.get(0);old.failStop=true;
        FrameLayout parent=new FrameLayout(context);parent.addView(old);
        WebView unrelated=new WebView(context);parent.addView(unrelated);
        WebViewClient client=old.getWebViewClient();assertTrue(client.onRenderProcessGone(old,null));
        assertEquals("challenge_render_gone",first.code);assertEquals(1,first.calls);assertNull(old.getParent());
        assertEquals(java.util.Arrays.asList("stop","destroy"),old.teardown);assertSame(parent,unrelated.getParent());
        assertTrue(client.onRenderProcessGone(old,null));client.onPageFinished(old,"https://gone.example/");
        idle();assertEquals(2,views.size());assertEquals(0,next.calls);log(0,"gone.example","renderGone",0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));
        assertEquals(1,first.calls);assertEquals("challenge_timeout",next.code);assertEquals(1,next.calls);
        assertEquals(2,ShadowLog.getLogsForTag("PodSiteChallenge").size());log(1,"next.example","timeout",0);
        parent.removeView(unrelated);unrelated.destroy();
    }
    @Test public void successLogsOnlySelectedCookieCountAndCleansUpBeforeCallback() throws Exception {
        Reply r=acquire("success.example",1000);TrackedView v=views.get(0);
        CookieManager.getInstance().setCookie("https://success.example/","n2_token=token-secret");
        v.getWebViewClient().onPageFinished(v,"https://success.example/");idle();
        assertNull(r.code);assertNotNull(r.value);assertEquals(1,r.calls);assertEquals("n2_token=token-secret",r.value.getString("cookie"));
        assertEquals(java.util.Arrays.asList("stop","destroy"),v.teardown);log(0,"success.example","ok",1);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));assertEquals(1,r.calls);
        assertEquals(1,ShadowLog.getLogsForTag("PodSiteChallenge").size());
    }
    @Test public void cancellationCloseAndQueuedTimeoutHaveOneTerminalLogEach() throws Exception {
        Reply owner=acquire("owner.example",1000),queued=acquire("queued.example",10);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        assertEquals("challenge_timeout",queued.code);assertEquals(1,views.size());log(0,"queued.example","timeout",0);
        owner.active=false;service.cancel(owner);idle();assertEquals(0,owner.calls);log(1,"owner.example","cancelled",0);
        Reply closing=acquire("closing.example",1000);service.close();idle();
        log(2,"closing.example","service_closed",0);assertEquals(0,closing.calls);
        assertEquals(3,ShadowLog.getLogsForTag("PodSiteChallenge").size());
    }
    @Test public void unavailableScriptEvaluationCannotEscapeTheMainThreadOrLeaveOwnerAlive() throws Exception {
        Reply r=acquire("script.example",1000);TrackedView v=views.get(0);v.failScript=true;
        v.getWebViewClient().onPageFinished(v,"https://script.example/");idle();
        assertEquals("unavailable",r.code);assertEquals(1,r.calls);
        assertEquals(java.util.Arrays.asList("stop","destroy"),v.teardown);log(0,"script.example","unavailable",0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));assertEquals(1,r.calls);
        assertEquals(1,ShadowLog.getLogsForTag("PodSiteChallenge").size());
    }
    @Test public void rejectedAcquisitionsLogOnceWithoutLoggingCacheLookups() throws Exception {
        Reply invalid=new Reply();service.dispatch(options("valid.example",1000).put("url","https://user:secret@invalid.example/?secret=query-value"),true,invalid);idle();
        assertEquals("invalid_argument",invalid.code);log(0,"","invalid_argument",0);assertEquals(0,views.size());
        Reply cached=new Reply();service.dispatch(options("cache.example",1000),false,cached);idle();assertEquals(1,cached.calls);
        assertEquals(1,ShadowLog.getLogsForTag("PodSiteChallenge").size());
        for(int i=0;i<8;i++)acquire("queued"+i+".example",1000);
        Reply busy=acquire("busy.example",1000);assertEquals("busy",busy.code);log(1,"busy.example","busy",0);
        assertEquals(1,views.size());
        service.close();idle();Reply closed=acquire("closed.example",1000);assertEquals("service_closed",closed.code);
        log(10,"","service_closed",0);assertEquals(11,ShadowLog.getLogsForTag("PodSiteChallenge").size());
    }
}
