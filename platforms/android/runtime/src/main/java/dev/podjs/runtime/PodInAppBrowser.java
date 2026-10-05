package dev.podjs.runtime;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.util.Iterator;
import org.json.JSONObject;

/** Owned by the runtime Activity, with no JS interface exposed to untrusted pages. */
final class PodInAppBrowser extends Dialog implements Application.ActivityLifecycleCallbacks {
    static final String UNAVAILABLE = "系统未安装或已禁用 WebView 组件，无法打开此页面，请先安装或启用 Android System WebView 后重试";
    static final String OFFLINE = "未连接网络";
    static final class Options {
        final String url, scale, font;
        final boolean dark;
        final int background, textZoom;
        Options(JSONObject args) {
            keys(args, "url", "mode", "scaleMode", "theme");
            if (!"inApp".equals(args.opt("mode"))) throw new IllegalArgumentException("mode must be inApp");
            url = resolveUrl(args.opt("url"));
            scale = string(args, "scaleMode", "standard");
            if (!scale.equals("standard") && !scale.equals("expanded") && !scale.equals("shrunk")) throw new IllegalArgumentException("Invalid scale mode");
            JSONObject theme = args.optJSONObject("theme");
            if (args.has("theme") && theme == null) throw new IllegalArgumentException("Invalid theme");
            if (theme == null) theme = new JSONObject();
            keys(theme, "dark", "backgroundColor", "textZoom", "fontFamily");
            if (args.has("theme") && !(theme.opt("dark") instanceof Boolean)) throw new IllegalArgumentException("theme.dark must be boolean");
            dark = theme.optBoolean("dark", true);
            String color = string(theme, "backgroundColor", dark ? "#000000" : "#ffffff");
            if (!color.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new IllegalArgumentException("Invalid background color");
            background = Color.parseColor(color);
            Object zoom = theme.opt("textZoom");
            if (theme.has("textZoom") && (!(zoom instanceof Number) || ((Number)zoom).doubleValue() != ((Number)zoom).intValue() || ((Number)zoom).intValue() < 50 || ((Number)zoom).intValue() > 200)) throw new IllegalArgumentException("textZoom must be 50..200");
            textZoom = theme.optInt("textZoom", 100);
            font = string(theme, "fontFamily", "");
            if (theme.has("fontFamily") && !font.equals("sans-serif") && !font.equals("serif") && !font.equals("monospace")) throw new IllegalArgumentException("Invalid font family");
        }
        private static String string(JSONObject args, String key, String fallback) {
            if (!args.has(key)) return fallback;
            if (!(args.opt(key) instanceof String)) throw new IllegalArgumentException(key + " must be a string");
            return (String)args.opt(key);
        }
        private static void keys(JSONObject args, String... allowed) {
            for (Iterator<String> it = args.keys(); it.hasNext();) {
                if (!java.util.Arrays.asList(allowed).contains(it.next())) throw new IllegalArgumentException("Unknown browser option");
            }
        }
    }
    static String resolveUrl(Object raw) {
        if (!(raw instanceof String)) throw new IllegalArgumentException("URL must be a string");
        String value = (String)raw;
        if (value.length() > 8192 || value.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new IllegalArgumentException("Invalid URL");
        value = value.trim();
        if (value.startsWith("/")) return Uri.fromFile(new File(value)).toString();
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme) && (uri.getAuthority() == null || uri.getAuthority().isEmpty()) && uri.getPath() != null && uri.getPath().startsWith("/")) return value;
        if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null && !value.contains(" ") && !value.contains("\\")) return value;
        throw new IllegalArgumentException("URL must be HTTP(S), file:/// or an absolute local path");
    }
    static boolean needsInternet(String url) {
        String scheme = Uri.parse(url).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
    static boolean online(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    static void checkAvailable(Context context, String url) {
        if (needsInternet(url) && !online(context)) {
            Toast.makeText(context, OFFLINE, Toast.LENGTH_LONG).show();
            throw new PodBrowserServices.BrowserFailure("offline", OFFLINE);
        }
        boolean available;
        try { available = WebView.getCurrentWebViewPackage() != null; }
        catch (RuntimeException | LinkageError error) { available = false; }
        if (!available) {
            Toast.makeText(context, UNAVAILABLE, Toast.LENGTH_LONG).show();
            throw new PodBrowserServices.BrowserFailure("webview_unavailable", UNAVAILABLE);
        }
    }
    private final Activity owner;
    private final Options options;
    WebView web;
    private FrameLayout root;
    private ProgressRing ring;
    private ScaleControl toggle;
    private String scale;
    private boolean registered, disposed;
    private ValueAnimator progressAnimator;
    private android.window.OnBackInvokedCallback backCallback;

    PodInAppBrowser(Activity owner, Options options) {
        super(owner, options.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        this.owner = owner; this.options = options; scale = options.scale;
        setOwnerActivity(owner);
        setOnDismissListener(dialog -> dispose());
    }
    void open() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new FrameLayout(owner);
        root.setBackgroundColor(options.background);
        web = new WebView(owner);
        configure();
        root.addView(web, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        ring = new ProgressRing(owner);
        root.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        ring.setVisibility(View.GONE);
        toggle = new ScaleControl(owner);
        FrameLayout toolbar = new FrameLayout(owner);
        toolbar.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{(options.background & 0x00ffffff) | 0xcc000000, Color.TRANSPARENT}));
        FrameLayout.LayoutParams control = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        control.topMargin = verticalPadding();
        toolbar.addView(toggle, control);
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, dp(48) + verticalPadding() * 2, Gravity.TOP));
        toggle.setOnClickListener(v -> { scale = nextScale(scale); updateViewport(); });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (r-l != or-ol || b-t != ob-ot) updateViewport(); });
        setContentView(root);
        setCanceledOnTouchOutside(false);
        show();
        getWindow().setLayout(-1, -1);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::back;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        owner.getApplication().registerActivityLifecycleCallbacks(this); registered = true;
        updateViewport();
        web.loadUrl(options.url);
    }
    @SuppressWarnings("deprecation")
    private void configure() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true); settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSupportZoom(true); settings.setBuiltInZoomControls(false); settings.setDisplayZoomControls(false);
        settings.setTextZoom(options.textZoom);
        if (!options.font.isEmpty()) settings.setStandardFontFamily(options.font);
        web.setBackgroundColor(options.background);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) WebSettingsCompat.setForceDark(settings, options.dark ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, options.dark);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // A remote page cannot navigate into local files or external apps.
                String scheme = request.getUrl().getScheme();
                boolean allowed = needsInternet(request.getUrl().toString()) || ("file".equalsIgnoreCase(Uri.parse(options.url).getScheme()) && "file".equalsIgnoreCase(scheme));
                if (!allowed) return true;
                if (request.isForMainFrame() && needsInternet(request.getUrl().toString()) && !online(owner)) { Toast.makeText(owner, OFFLINE, Toast.LENGTH_LONG).show(); return true; }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { setProgress(0, false); }
            @Override public void onPageFinished(WebView view, String url) { setProgress(1, true); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (!online(owner) && needsInternet(request.getUrl().toString())) Toast.makeText(owner, OFFLINE, Toast.LENGTH_LONG).show();
                    if (progressAnimator != null) progressAnimator.cancel();
                    ring.setVisibility(View.GONE);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) { setProgress(progress / 100f, progress >= 100); }
        });
    }
    static String nextScale(String value) { return value.equals("standard") ? "expanded" : value.equals("expanded") ? "shrunk" : "standard"; }
    static int[] viewport(int width, int height, float padding, float verticalPadding, String mode) {
        double w = Math.max(width - 2 * padding, width * .1), h = Math.max(height - 2 * verticalPadding, height * .1);
        double factor = mode.equals("expanded") ? Math.max(1, width / w) : mode.equals("shrunk") ? Math.min(1, width / Math.hypot(w, h)) : 1;
        return new int[]{(int)Math.round(w * factor), (int)Math.round(h * factor)};
    }
    private int verticalPadding() { return dp(owner.getResources().getConfiguration().isScreenRound() ? 18 : 8); }
    private int dp(float value) { return Math.round(value * owner.getResources().getDisplayMetrics().density); }
    private void updateViewport() {
        if (disposed || web == null || root.getWidth() == 0) return;
        int[] size = viewport(root.getWidth(), root.getHeight(), dp(18), verticalPadding(), scale);
        web.setLayoutParams(new FrameLayout.LayoutParams(size[0], size[1], Gravity.CENTER));
        String action = scale.equals("standard") ? "放大" : scale.equals("expanded") ? "缩小" : "标准";
        toggle.setContentDescription(action); toggle.invalidate();
    }
    private void setProgress(float value, boolean hide) {
        if (disposed || ring == null) return;
        if (progressAnimator != null) progressAnimator.cancel();
        float target = Math.max(0, Math.min(1, value));
        ring.setVisibility(View.VISIBLE);
        if (target <= ring.progress) { ring.progress = target; ring.invalidate(); if (hide) ring.setVisibility(View.GONE); return; }
        progressAnimator = ValueAnimator.ofFloat(ring.progress, target);
        progressAnimator.setDuration((long)Math.max(150, Math.min(500, (target-ring.progress)*700)));
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.addUpdateListener(a -> { ring.progress = (float)a.getAnimatedValue(); ring.invalidate(); if (hide && ring.progress >= 1) ring.setVisibility(View.GONE); });
        progressAnimator.start();
    }
    private float swipeX, swipeY;
    private boolean swipeCandidate, swipeIntercepted;
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            swipeX = event.getX(); swipeY = event.getY();
            swipeCandidate = root != null && swipeX <= root.getWidth() * .65f;
            swipeIntercepted = false;
        }
        if (event.getPointerCount() > 1) swipeCandidate = false;
        float dx = event.getX() - swipeX, dy = Math.abs(event.getY() - swipeY);
        int slop = android.view.ViewConfiguration.get(owner).getScaledTouchSlop();
        if (action == android.view.MotionEvent.ACTION_MOVE && swipeCandidate && !swipeIntercepted) {
            if (dy > Math.max(slop * 3, dp(48))) swipeCandidate = false;
            else if (dx > slop && dx > dy) {
                swipeIntercepted = true;
                android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel); cancel.recycle();
            }
        }
        if (swipeIntercepted) {
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                boolean commit = action == android.view.MotionEvent.ACTION_UP && swipeCandidate
                    && dx > Math.max(Math.max(slop * 2, dp(48)), root.getWidth() * .35f)
                    && dy < Math.max(slop * 3, dp(48));
                swipeCandidate = swipeIntercepted = false;
                if (commit) back();
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
    private int pendingStem = -1;
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        if (key == android.view.KeyEvent.KEYCODE_STEM_1 || key == android.view.KeyEvent.KEYCODE_STEM_2) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) pendingStem = key;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) { boolean go = pendingStem == key && !event.isCanceled(); pendingStem = -1; if (go) back(); }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    void back() { if (web != null && web.canGoBack()) web.goBack(); else dismiss(); }
    @Override public void onBackPressed() { back(); }
    @Override public void dismiss() { try { super.dismiss(); } finally { dispose(); } }
    private void dispose() {
        if (disposed) return;
        disposed = true;
        if (registered) { owner.getApplication().unregisterActivityLifecycleCallbacks(this); registered = false; }
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback); backCallback = null; }
        if (progressAnimator != null) { progressAnimator.cancel(); progressAnimator.removeAllUpdateListeners(); progressAnimator = null; }
        if (web != null) {
            WebView old = web; web = null;
            old.stopLoading(); old.setWebChromeClient(null); old.setWebViewClient(new WebViewClient());
            if (old.getParent() instanceof ViewGroup) ((ViewGroup)old.getParent()).removeView(old);
            old.destroy();
        }
    }
    @Override public void onActivityPaused(Activity activity) { if (activity == owner && web != null) web.onPause(); }
    @Override public void onActivityResumed(Activity activity) { if (activity == owner && web != null) web.onResume(); }
    @Override public void onActivityDestroyed(Activity activity) { if (activity == owner) dismiss(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    private final class ScaleControl extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScaleControl(Context context) { super(context); setClickable(true); setFocusable(true); paint.setColor(options.dark ? Color.WHITE : Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.translate(getWidth()/2f, getHeight()/2f);
            float unit = dp(24)/24f;
            canvas.scale(unit, unit);
            paint.setStrokeWidth(2);
            if (scale.equals("expanded")) canvas.drawCircle(0,0,9,paint);
            else {
                boolean inward = scale.equals("shrunk");
                for (int x : new int[]{-1,1}) for (int y : new int[]{-1,1}) {
                    float corner = inward ? 3 : 8, end = inward ? 9 : 3;
                    canvas.drawLine(x*corner,y*end,x*corner,y*corner,paint);
                    canvas.drawLine(x*corner,y*corner,x*end,y*corner,paint);
                }
            }
            canvas.restore();
        }
    }
    private static final class ProgressRing extends View {
        float progress;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ProgressRing(Context context) { super(context); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(12); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(0xff476cff); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            if (progress <= 0) return;
            float radius = Math.min(getWidth(),getHeight())/2f - paint.getStrokeWidth()/2f;
            float x = getWidth()/2f, y = getHeight()/2f;
            canvas.drawArc(x-radius,y-radius,x+radius,y+radius,-90,360*progress,false,paint);
        }
    }
}
