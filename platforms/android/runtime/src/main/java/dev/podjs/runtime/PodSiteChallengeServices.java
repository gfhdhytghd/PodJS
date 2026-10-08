package dev.podjs.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Executes only the site's own scripts in a hidden WebView. No UI, injected
 * solver, JS bridge, unrelated account-cookie export, or global cookie deletion. */
final class PodSiteChallengeServices implements AutoCloseable {
    static final int VIEWPORT_PX=240;
    interface WebViewFactory { WebView create(Context context); }
    interface Callback {
        boolean isActive();
        void complete(JSONObject value);
        void fail(String code,String message);
    }
    static final class Options {
        final String url, origin, host, userAgent, key;
        final List<String> names;
        final int timeoutMs;
        Options(JSONObject args) throws Exception {
            url=args.getString("url");URI uri=new URI(url);
            String host=uri.getHost();
            boolean loopback="127.0.0.1".equals(host)||"localhost".equals(host)||"[::1]".equals(host);
            if(host==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535
                ||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())&&loopback))
                throw new IllegalArgumentException("Challenge requires HTTPS or loopback HTTP");
            origin=origin(uri);
            this.host=host.toLowerCase(java.util.Locale.ROOT);
            userAgent=args.getString("userAgent");
            if(userAgent.isEmpty()||userAgent.length()>2048||userAgent.indexOf('\r')>=0||userAgent.indexOf('\n')>=0)
                throw new IllegalArgumentException("Invalid challenge user agent");
            JSONArray values=args.getJSONArray("cookieNames");
            if(values.length()==0||values.length()>16)throw new IllegalArgumentException("Invalid challenge cookie names");
            TreeSet<String> selected=new TreeSet<>();
            for(int i=0;i<values.length();i++) {
                String name=values.getString(i);
                if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"))throw new IllegalArgumentException("Invalid challenge cookie name");
                selected.add(name);
            }
            names=new ArrayList<>(selected);key=origin+"\n"+String.join(";",names);
            timeoutMs=args.optInt("timeoutMs",60000);
            if(timeoutMs<1||timeoutMs>120000)throw new IllegalArgumentException("Invalid challenge deadline");
        }
        boolean sameOrigin(String value) {
            try {return origin.equals(origin(new URI(value)));}catch(Exception invalid){return false;}
        }
    }
    static String origin(URI uri) {
        String scheme=uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        int port=uri.getPort();boolean standard=port==-1||port==443&&"https".equals(scheme)||port==80&&"http".equals(scheme);
        return scheme+"://"+uri.getHost().toLowerCase(java.util.Locale.ROOT)+(standard?"":":"+port);
    }
    static String selectCookies(String header,List<String> names) {
        Map<String,String> values=new LinkedHashMap<>();
        if(header!=null)for(String part:header.split(";")) {
            int eq=part.indexOf('=');if(eq<=0)continue;
            String name=part.substring(0,eq).trim(),value=part.substring(eq+1).trim();
            if(names.contains(name)&&!value.isEmpty()&&value.length()<=8192&&value.indexOf('\r')<0&&value.indexOf('\n')<0)values.put(name,value);
        }
        List<String> result=new ArrayList<>();
        for(String name:names){if(!values.containsKey(name))return "";result.add(name+"="+values.get(name));}
        String cookie=String.join("; ",result);return cookie.length()<=16384?cookie:"";
    }
    static String fingerprint(String userAgent,String cookie) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest((userAgent+"\n"+cookie).getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private final Context context;
    private final WebViewFactory webViews;
    private final SharedPreferences cache;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Session> queue=new ArrayList<>();
    private volatile boolean closed;
    PodSiteChallengeServices(Context context) {
        this(context,WebView::new);
    }
    PodSiteChallengeServices(Context context,WebViewFactory webViews) {
        this.context=context;this.webViews=webViews;cache=context.getSharedPreferences("pod-site-challenge",Context.MODE_PRIVATE);
    }
    void dispatch(JSONObject args,boolean acquire,Callback callback) {
        long requested=SystemClock.elapsedRealtime();
        main.post(()->{
            if(!callback.isActive())return;
            if(closed){if(acquire)logTerminal(null,requested,"service_closed",0);callback.fail("service_closed","Site challenge service is closed");return;}
            Options options=null;
            try {
                options=new Options(args);
                if(!acquire) {
                    String cookie=selectCookies(CookieManager.getInstance().getCookie(options.url),options.names);
                    // Match both UA and token. Unrelated WebViews may replace a token.
                    if(cookie.isEmpty()||!fingerprint(options.userAgent,cookie).equals(cache.getString(options.key,"")))cookie="";
                    callback.complete(result(options,cookie));return;
                }
                if(queue.size()>=8){logTerminal(options,requested,"busy",0);callback.fail("busy","Too many site challenges");return;}
                Session session=new Session(options,callback);queue.add(session);
                main.postDelayed(session.timeout,options.timeoutMs);
                if(queue.size()==1)session.start();
            }catch(Exception|LinkageError error){
                String code=(error instanceof IllegalArgumentException||error instanceof org.json.JSONException||error instanceof java.net.URISyntaxException)?"invalid_argument":"unavailable";
                if(acquire)logTerminal(options,requested,code,0);
                callback.fail(code,"Site challenge unavailable");
            }
        });
    }
    private static JSONObject result(Options options,String cookie) throws Exception {
        return new JSONObject().put("cookie",cookie).put("origin",options.origin);
    }
    static void configureHiddenView(WebView view) {
        // Detached script-only browser: keep the existing small viewport, but
        // measure it explicitly. Never allocate a software/hardware view layer
        // or request offscreen tile pre-rasterization for this invisible owner.
        view.setLayerType(View.LAYER_TYPE_NONE,null);
        view.setWillNotDraw(true);
        view.getSettings().setOffscreenPreRaster(false);
        int spec=View.MeasureSpec.makeMeasureSpec(VIEWPORT_PX,View.MeasureSpec.EXACTLY);
        view.measure(spec,spec);view.layout(0,0,VIEWPORT_PX,VIEWPORT_PX);
    }
    private static void destroyView(WebView old) {
        // Renderer death can make stopLoading fail. Still detach and destroy,
        // without touching global timers, cookies or any other WebView.
        try {old.stopLoading();}catch(RuntimeException|LinkageError ignored){}
        try {if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);}
        finally {old.destroy();}
    }
    private static void logTerminal(Options options,long started,String outcome,int cookieCount) {
        // Intentionally present in release. Never include URL/path/query, UA,
        // cookie names/values, exception messages or renderer diagnostic text.
        try {Log.i("PodSiteChallenge",new JSONObject().put("host",options==null?"":options.host).put("result",outcome)
            .put("ms",Math.max(0,SystemClock.elapsedRealtime()-started)).put("cookies",cookieCount).toString());}
        catch(org.json.JSONException impossible){throw new AssertionError(impossible);}
    }
    private final class Session {
        final Options options;final Callback callback;final long started,deadline;
        final Runnable timeout=()->finish(null,"challenge_timeout","Site challenge timed out");
        final Runnable poll=this::poll;
        WebView view;String previous="";boolean loaded,done,checking;
        Session(Options options,Callback callback){this.options=options;this.callback=callback;started=SystemClock.elapsedRealtime();deadline=started+options.timeoutMs;}
        void start() {
            if(done||view!=null)return;
            if(!callback.isActive()){finish(null,null,null);return;}
            if(SystemClock.elapsedRealtime()>=deadline){timeout.run();return;}
            try {
                previous=selectCookies(CookieManager.getInstance().getCookie(options.url),options.names);
                view=webViews.create(context);configureHiddenView(view);WebSettings settings=view.getSettings();
                settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setUserAgentString(options.userAgent);
                settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
                CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);
                view.setWebViewClient(new WebViewClient(){
                    @Override public boolean onRenderProcessGone(WebView web,RenderProcessGoneDetail detail) {
                        // Each callback owns exactly its given WebView. Never
                        // iterate browser/login instances sharing the renderer.
                        if(web==view)finish(null,"challenge_render_gone","Site challenge renderer exited");
                        return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView web,WebResourceRequest request) {
                        if(!options.sameOrigin(request.getUrl().toString())){finish(null,"challenge_redirect","Site challenge left its origin");return true;}return false;
                    }
                    @Override public WebResourceResponse shouldInterceptRequest(WebView web,WebResourceRequest request) {
                        // Also covers redirects that skip shouldOverrideUrlLoading.
                        if(request.isForMainFrame()&&!options.sameOrigin(request.getUrl().toString())){
                            main.post(()->finish(null,"challenge_redirect","Site challenge left its origin"));
                            return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));
                        }return null;
                    }
                    @Override public void onPageStarted(WebView web,String url,android.graphics.Bitmap favicon){loaded=false;if(!options.sameOrigin(url))finish(null,"challenge_redirect","Site challenge left its origin");}
                    @Override public void onPageFinished(WebView web,String url){if(options.sameOrigin(url)){loaded=true;poll();}}
                    @Override public void onReceivedError(WebView web,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())finish(null,"challenge_load_failed","Site challenge failed to load");}
                    @Override public void onReceivedHttpError(WebView web,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame()&&response.getStatusCode()>=500)finish(null,"challenge_load_failed","Site challenge failed to load");}
                    @Override public void onReceivedSslError(WebView web,SslErrorHandler handler,SslError error){handler.cancel();finish(null,"challenge_tls_failed","Site challenge TLS failed");}
                });
                view.onResume();view.loadUrl(options.url);
                if(!done)main.postDelayed(poll,250);
            }catch(RuntimeException|LinkageError unavailable){finish(null,"unavailable","Site challenge WebView unavailable");}
        }
        void poll() {
            main.removeCallbacks(poll);if(done)return;
            if(!callback.isActive()){finish(null,null,null);return;}
            if(SystemClock.elapsedRealtime()>=deadline){timeout.run();return;}
            try {
                String cookie=selectCookies(CookieManager.getInstance().getCookie(options.url),options.names);
                // Never call an unchanged stale cookie a refreshed challenge.
                if(loaded&&!cookie.isEmpty()&&!cookie.equals(previous)){
                    CookieManager.getInstance().flush();cache.edit().putString(options.key,fingerprint(options.userAgent,cookie)).apply();
                    finish(result(options,cookie),null,null);return;
                }
            }catch(Exception|LinkageError unavailable){finish(null,"unavailable","Site challenge cookie store unavailable");return;}
            if(loaded&&view!=null&&!checking){
                checking=true;
                // Read only visible controls; never click or submit verification.
                try {
                    view.evaluateJavascript("(function(){return Array.from(document.querySelectorAll('iframe[src*=captcha],.captcha_verify_container,.g-recaptcha,.h-captcha,.cf-turnstile,[data-sitekey]')).some(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0})})()", value->{
                        checking=false;if(!done&&"true".equals(value))finish(null,"interaction_required","Site requires interactive verification");
                    });
                }catch(RuntimeException|LinkageError unavailable){finish(null,"unavailable","Site challenge WebView unavailable");return;}
            }
            if(!done)main.postDelayed(poll,250);
        }
        void finish(JSONObject value,String code,String message) {
            if(done)return;done=true;main.removeCallbacks(timeout);main.removeCallbacks(poll);
            boolean first=!queue.isEmpty()&&queue.get(0)==this;queue.remove(this);
            if(view!=null){WebView old=view;view=null;try {destroyView(old);}catch(RuntimeException|LinkageError ignored){}}
            String outcome=value!=null?"ok":"challenge_timeout".equals(code)?"timeout":"challenge_render_gone".equals(code)?"renderGone":code==null?(closed?"service_closed":"cancelled"):code;
            logTerminal(options,started,outcome,value!=null?options.names.size():0);
            try {if(callback.isActive()){if(value!=null)callback.complete(value);else if(code!=null)callback.fail(code,message);}}
            finally {if(first&&!queue.isEmpty()&&!closed)main.post(()->{if(!closed&&!queue.isEmpty())queue.get(0).start();});}
        }
    }
    void cancel(Callback callback){main.post(()->{for(Session session:new ArrayList<>(queue))if(session.callback==callback)session.finish(null,null,null);});}
    @Override public void close(){closed=true;main.post(()->{for(Session session:new ArrayList<>(queue))session.finish(null,null,null);});}
}
