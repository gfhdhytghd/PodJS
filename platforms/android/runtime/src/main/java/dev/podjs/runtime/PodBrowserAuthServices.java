package dev.podjs.runtime;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.os.SystemClock;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-visible, manual HTTP(S) authorization in an ordinary WebView. */
public final class PodBrowserAuthServices implements AutoCloseable {
    public interface Callback {
        default boolean isActive() { return true; }
        default void status(JSONObject value) {}
        void complete(JSONObject value);
        void fail(String code, String message);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService qrWorker = Executors.newSingleThreadExecutor();
    private Dialog dialog;
    private WebView webView;
    private ImageView qrOverlay;
    private ViewGroup qrMask;
    private PodBrowserWaitView qrWaitView;
    private Runnable qrPoll;
    private Runnable deadlinePoll;
    private PodBrowserAuthDeadline deadline;
    private boolean verifying;
    private String displayedQrData;
    private Callback pending;
    private PodBrowserAuthStatus.Stream statusStream;
    private TextView statusLabel;
    private int qrGeneration;
    private int pageGeneration;
    private PodBrowserLoginCompletion loginCompletion;
    private Uri completionOrigin;
    private Set<String> completionRequired;
    private volatile boolean closed;

    public PodBrowserAuthServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context;
    }

    public void authorize(JSONObject args, Callback callback) {
        if (args == null || callback == null) throw new IllegalArgumentException("args_callback_required");
        authorize(args, callback, false);
    }

    public void authorize(JSONObject args, Callback callback, boolean withStatus) {
        if (args == null || callback == null) throw new IllegalArgumentException("args_callback_required");
        Log.i("PodBrowserAuth", "code=requested");
        main.post(() -> { if (callback.isActive()) open(args, callback, withStatus); });
    }

    private static void reject(Callback callback, String code, String message) {
        Log.w("PodBrowserAuth", "code=" + code);
        callback.fail(code, message);
    }

    private void open(JSONObject args, Callback callback, boolean withStatus) {
        if (closed) { reject(callback, "service_closed", "Browser authorization service is closed"); return; }
        if (!(context instanceof Activity)) { reject(callback, "unavailable", "Browser authorization requires an Activity"); return; }
        if (pending != null) { reject(callback, "busy", "A browser authorization dialog is already open"); return; }

        final Uri initial;
        final Uri cookieOrigin;
        final Set<String> required;
        final String qrExtractionScript;
        try {
            initial = requireWebUri(args.optString("url", ""), false);
            cookieOrigin = requireWebUri(args.optString("cookieOrigin", ""), true);
            if (!sameOrigin(initial, cookieOrigin)) throw new IllegalArgumentException("url_and_cookie_origin_must_match");
            required = requiredCookieNames(args.optJSONArray("requiredCookieNames"));
            qrExtractionScript = args.optString("qrExtractionScript", "");
            if (args.optBoolean("completeOnLoginPanelHidden", false) && (!withStatus || qrExtractionScript.isEmpty()))
                throw new IllegalArgumentException("login_panel_completion_requires_status_script");
            if (qrExtractionScript.length() > 16 * 1024) throw new IllegalArgumentException("qr_extraction_script_too_large");
        } catch (IllegalArgumentException error) {
            reject(callback, "invalid_argument", message(error));
            return;
        }

        try {
            WebView view = new WebView(context);
            view.setBackgroundColor(Color.BLACK);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setLoadWithOverviewMode(true);
            view.getSettings().setUseWideViewPort(true);
            String userAgent = args.optString("userAgent", "").trim();
            if (!userAgent.isEmpty()) view.getSettings().setUserAgentString(userAgent);
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(view, false);
            view.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage message) {
                    if (pending == callback && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        // Page messages/URLs can include credentials. Log one closed code per session.
                        if (deadline != null && deadline.scriptError()) Log.w("PodBrowserAuth", "code=browser_script_error");
                    }
                    return true;
                }
            });
            view.setWebViewClient(new WebViewClient() {
                private void page(boolean loading) {
                    if (webView != view || pending != callback) return;
                    if (statusLabel != null) statusLabel.setText(loading ? "正在加载登录页…" : "正在等待登录二维码…");
                    if (statusStream == null) return;
                    try { statusStream.emit(PodBrowserAuthStatus.event("pageState").put("loading", loading)); }
                    catch (org.json.JSONException error) { throw new IllegalStateException(error); }
                }
                @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                    if (webView != view || pending != callback) return;
                    Log.i("PodBrowserAuth", "code=page_started");
                    pageGeneration++;
                    if (qrOverlay != null) hideQrOverlay(qrOverlay);
                    if (statusStream != null) statusStream.accept(PodBrowserAuthStatus.parse("null"));
                    page(true);
                }
                @Override public void onPageFinished(WebView v, String url) {
                    if (webView == view && pending == callback) Log.i("PodBrowserAuth", "code=page_finished");
                    page(false);
                }
                @Override public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                    if (webView == view && pending == callback && request != null && request.isForMainFrame())
                        finishFailure("webview_http_" + response.getStatusCode(), "WebView main frame HTTP failure");
                }
                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    if (webView == view && pending == callback)
                        finishFailure("webview_renderer_gone", "WebView renderer stopped");
                    return true;
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    if (webView != view || pending != callback || request == null || !request.isForMainFrame()) return;
                    // Numeric WebView code only: never forward URLs, page text or vendor descriptions.
                    int code = error == null ? WebViewClient.ERROR_UNKNOWN : error.getErrorCode();
                    finishFailure("webview_load_" + code, "WebView main frame failed");
                }
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    Uri target = request == null ? null : request.getUrl();
                    if (target != null && sameOrigin(initial, target)) return false;
                    return true;
                }
            });

            float density = context.getResources().getDisplayMetrics().density;
            boolean round = (context.getResources().getConfiguration().screenLayout
                    & Configuration.SCREENLAYOUT_ROUND_MASK) == Configuration.SCREENLAYOUT_ROUND_YES;
            LayoutSpec layout = layoutSpec(
                    context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels,
                    density,
                    round);

            TextView status = new TextView(context);
            status.setText("正在加载登录页…");
            status.setTextColor(Color.WHITE);
            status.setTextSize(TypedValue.COMPLEX_UNIT_PX, layout.chromeTextPx);
            status.setGravity(Gravity.CENTER);
            status.setSingleLine(true);
            status.setPadding(layout.statusInsetPx, 0, layout.statusInsetPx, 0);

            TextView done = actionButton("完成", layout);
            TextView cancel = actionButton("取消", layout);
            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            actions.setPadding(layout.actionInsetPx, 0, layout.actionInsetPx, 0);
            LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, layout.actionHeightPx, 1f);
            doneParams.setMarginEnd(3);
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, layout.actionHeightPx, 1f);
            cancelParams.setMarginStart(3);
            actions.addView(done, doneParams);
            actions.addView(cancel, cancelParams);

            FrameLayout browserArea = new FrameLayout(context);
            browserArea.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            JSONObject qrWait = args.optJSONObject("qrWait");
            PodBrowserWaitView waitView = withStatus && qrWait != null ? new PodBrowserWaitView(context) : null;
            if (!qrExtractionScript.isEmpty()) {
                FrameLayout mask = new FrameLayout(context);
                mask.setBackgroundColor(Color.BLACK);
                mask.setVisibility(ViewGroup.GONE);
                ImageView overlay = new ImageView(context);
                overlay.setBackgroundColor(Color.WHITE);
                overlay.setScaleType(ImageView.ScaleType.CENTER);
                FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                        layout.qrSizePx, layout.qrSizePx, Gravity.CENTER);
                mask.addView(overlay, overlayParams);
                browserArea.addView(mask, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                qrOverlay = overlay;
                qrMask = mask;
            }

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(Color.BLACK);
            content.setPadding(layout.horizontalInsetPx, layout.verticalInsetPx,
                    layout.horizontalInsetPx, layout.verticalInsetPx);
            content.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, layout.statusHeightPx));
            content.addView(browserArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, layout.webViewHeightPx));
            content.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, layout.actionHeightPx));

            // The waiting ring traces the screen edge: a centred square of the shorter screen side, over the whole chrome.
            FrameLayout root = new FrameLayout(context);
            root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (waitView != null) {
                int ringSize = Math.min(context.getResources().getDisplayMetrics().widthPixels, context.getResources().getDisplayMetrics().heightPixels);
                root.addView(waitView, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));
            }

            Dialog created = new Dialog(context);
            created.requestWindowFeature(Window.FEATURE_NO_TITLE);
            created.setContentView(root);
            created.setOnShowListener(ignored -> {
                Window window = created.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                    window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                }
                done.setOnClickListener(button -> {
                    String raw = cookies.getCookie(originString(cookieOrigin));
                    if (!containsRequiredCookies(raw, required)) {
                        status.setText("尚未登录");
                        return;
                    }
                    cookies.flush();
                    finishSuccess(new JSONObjectResult(raw == null ? "" : raw, originString(cookieOrigin)).json);
                });
                cancel.setOnClickListener(button -> cancel(callback));
            });
            created.setOnCancelListener(ignored -> cancel(callback));
            created.setOnDismissListener(ignored -> {
                if (dialog == created && pending == callback) finishFailure("cancelled", "Browser authorization dismissed");
            });
            pending = callback;
            loginCompletion = args.optBoolean("completeOnLoginPanelHidden", false) ? new PodBrowserLoginCompletion() : null;
            completionOrigin = cookieOrigin;
            completionRequired = required;
            statusStream = withStatus ? new PodBrowserAuthStatus.Stream(callback::status, qrWait) : null;
            qrWaitView = waitView;
            if (statusStream != null && waitView != null) {
                PodBrowserAuthStatus.Stream stream = statusStream;
                stream.changed = () -> {
                    if (qrWaitView != waitView) return;
                    JSONObject wait = stream.qrWaitStatus();
                    waitView.apply(wait.optBoolean("visible"), wait.optString("message"));
                };
                stream.changed.run();
            }
            statusLabel = status;
            dialog = created;
            webView = view;
            created.show();
            deadline = new PodBrowserAuthDeadline(SystemClock.elapsedRealtime(), !qrExtractionScript.isEmpty());
            verifying = false;
            startDeadlinePolling(callback);
            Log.i("PodBrowserAuth", "code=opened");
            view.loadUrl(initial.toString());
            if (qrOverlay != null) startQrPolling(view, qrOverlay, qrExtractionScript);
        } catch (Throwable error) {
            endStatus(false);
            pending = null;
            dismissAndRelease();
            Log.w("PodBrowserAuth", "code=webview_unavailable");
            callback.fail("webview_unavailable", "WebView is unavailable");
        }
    }

    private void startDeadlinePolling(Callback owner) {
        deadlinePoll = new Runnable() {
            @Override public void run() {
                if (pending != owner || deadline == null) return;
                String code = deadline.failure(SystemClock.elapsedRealtime());
                if (code != null) { finishFailure(code, "Browser authorization deadline exceeded"); return; }
                main.postDelayed(this, 250);
            }
        };
        main.post(deadlinePoll);
    }

    public void cancel() {
        main.post(() -> finishFailure("cancelled", "Browser authorization cancelled"));
    }

    public void cancel(Callback owner) {
        main.post(() -> { if (owner != null && pending == owner) finishFailure("cancelled", "Browser authorization cancelled"); });
    }

    private void endStatus(boolean cancelled) {
        if (statusStream != null) statusStream.end(cancelled);
        statusStream = null;
    }

    private void finishSuccess(JSONObject result) {
        Log.i("PodBrowserAuth", "code=completed");
        endStatus(false);
        Callback callback = pending;
        pending = null;
        dismissAndRelease();
        if (callback != null) callback.complete(result);
    }

    private void finishFailure(String code, String message) {
        Log.w("PodBrowserAuth", "code=" + code);
        endStatus("cancelled".equals(code));
        Callback callback = pending;
        pending = null;
        dismissAndRelease();
        if (callback != null) callback.fail(code, message);
    }

    private void dismissAndRelease() {
        Dialog current = dialog;
        dialog = null;
        if (current != null && current.isShowing()) current.dismiss();
        releaseWebView();
    }

    private void releaseWebView() {
        if (deadlinePoll != null) main.removeCallbacks(deadlinePoll);
        deadlinePoll = null;
        deadline = null;
        if (qrPoll != null) main.removeCallbacks(qrPoll);
        qrPoll = null;
        loginCompletion = null;
        completionOrigin = null;
        completionRequired = null;
        qrGeneration++;
        displayedQrData = null;
        statusLabel = null;
        if (qrWaitView != null) qrWaitView.release();
        qrWaitView = null;
        ImageView overlay = qrOverlay;
        qrOverlay = null;
        ViewGroup mask = qrMask;
        qrMask = null;
        if (overlay != null) {
            recycleOverlayBitmap(overlay);
            overlay.setVisibility(ImageView.GONE);
        }
        if (mask != null) mask.setVisibility(ViewGroup.GONE);
        WebView current = webView;
        webView = null;
        if (current == null) return;
        current.stopLoading();
        current.onPause();
        current.loadUrl("about:blank");
        current.clearHistory();
        ViewGroup parent = (ViewGroup) current.getParent();
        if (parent != null) parent.removeView(current);
        current.destroy();
    }

    private void startQrPolling(WebView view, ImageView overlay, String extractionScript) {
        final Runnable[] task = new Runnable[1];
        task[0] = () -> {
            if (closed || pending == null || webView != view || qrOverlay != overlay) return;
            int page = pageGeneration;
            view.evaluateJavascript(extractionScript, result -> {
                if (!isCurrentQrSession(view, overlay)) return;
                if (page != pageGeneration) { scheduleQrPoll(task[0]); return; }
                if (statusStream == null) showQrOverlay(overlay, javascriptString(result));
                else {
                    PodBrowserAuthStatus snapshot = PodBrowserAuthStatus.parse(result);
                    verifying = snapshot.verifying;
                    if (deadline != null) deadline.state(SystemClock.elapsedRealtime(), qrMask != null && qrMask.getVisibility() == ViewGroup.VISIBLE, verifying);
                    statusStream.accept(snapshot);
                    if (!isCurrentQrSession(view, overlay)) return;
                    if (loginCompletion != null) {
                        String raw = CookieManager.getInstance().getCookie(originString(completionOrigin));
                        if (loginCompletion.accept(snapshot, containsRequiredCookies(raw, completionRequired))) {
                            CookieManager.getInstance().flush();
                            finishSuccess(new JSONObjectResult(raw == null ? "" : raw, originString(completionOrigin)).json);
                            return;
                        }
                    }
                    if (statusLabel != null) statusLabel.setText(snapshot.verifying ? "请完成网页验证"
                            : snapshot.qr == null ? "正在等待登录二维码…"
                            : "secondVerify".equals(snapshot.qr.optString("kind")) ? "请扫码完成二步验证" : "请扫码登录");
                    showQrOverlay(overlay, snapshot.dataImage());
                }
                scheduleQrPoll(task[0]);
            });
        };
        qrPoll = task[0];
        main.post(task[0]);
    }

    private boolean isCurrentQrSession(WebView view, ImageView overlay) {
        return !closed && pending != null && pending.isActive() && webView == view && qrOverlay == overlay;
    }

    private void scheduleQrPoll(Runnable task) {
        if (qrPoll == task) main.postDelayed(task, 750);
    }

    private void showQrOverlay(ImageView overlay, String dataImage) {
        if (dataImage != null && dataImage.equals(displayedQrData)) return;
        byte[] bytes = dataImageBytes(dataImage);
        if (bytes == null) { hideQrOverlay(overlay); return; }
        // Clear the previous code immediately while the replacement is decoded.
        hideQrOverlay(overlay);
        displayedQrData = dataImage;
        int generation = ++qrGeneration;
        qrWorker.execute(() -> {
            Bitmap normalized = decodeAndEncodeQr(bytes, overlay.getLayoutParams().width);
            main.post(() -> {
                if (generation != qrGeneration || qrOverlay != overlay || pending == null) {
                    if (normalized != null) normalized.recycle();
                    return;
                }
                if (normalized == null) { hideQrOverlay(overlay); return; }
                recycleOverlayBitmap(overlay);
                overlay.setImageBitmap(normalized);
                overlay.setVisibility(ImageView.VISIBLE);
                if (qrMask != null) qrMask.setVisibility(ViewGroup.VISIBLE);
                if (deadline != null) deadline.state(SystemClock.elapsedRealtime(), true, verifying);
                Log.i("PodBrowserAuth", "code=qr_ready");
            });
        });
    }

    private void hideQrOverlay(ImageView overlay) {
        displayedQrData = null;
        qrGeneration++;
        recycleOverlayBitmap(overlay);
        overlay.setVisibility(ImageView.GONE);
        if (qrMask != null) qrMask.setVisibility(ViewGroup.GONE);
        if (deadline != null) deadline.state(SystemClock.elapsedRealtime(), false, verifying);
    }

    private static void recycleOverlayBitmap(ImageView overlay) {
        if (overlay.getDrawable() instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) overlay.getDrawable()).getBitmap();
            overlay.setImageDrawable(null);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        } else overlay.setImageDrawable(null);
    }

    static String javascriptString(String result) {
        if (result == null || "null".equals(result)) return null;
        try {
            Object value = new JSONTokener(result).nextValue();
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) { return null; }
    }

    static byte[] dataImageBytes(String value) {
        if (value == null || value.length() > 4 * 1024 * 1024) return null;
        String png = "data:image/png;base64,";
        String jpeg = "data:image/jpeg;base64,";
        int prefix = value.startsWith(png) ? png.length() : value.startsWith(jpeg) ? jpeg.length() : -1;
        if (prefix < 0) return null;
        try { return Base64.decode(value.substring(prefix), Base64.DEFAULT); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    static Bitmap decodeAndEncodeQr(byte[] bytes, int maxOutputPx) {
        if (maxOutputPx < 1) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 4096 || bounds.outHeight > 4096) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2;
        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (source == null) return null;
        try {
            int[] pixels = new int[source.getWidth() * source.getHeight()];
            source.getPixels(pixels, 0, source.getWidth(), 0, 0, source.getWidth(), source.getHeight());
            Map<DecodeHintType,Object> decodeHints = new EnumMap<>(DecodeHintType.class);
            decodeHints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            decodeHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(source.getWidth(), source.getHeight(), pixels))), decodeHints);
            Map<EncodeHintType,Object> encodeHints = new EnumMap<>(EncodeHintType.class);
            encodeHints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(decoded.getText(), BarcodeFormat.QR_CODE, 0, 0, encodeHints);
            int scale = Math.max(1, maxOutputPx / matrix.getWidth());
            int outputSize = matrix.getWidth() * scale;
            Bitmap output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) {
                int color = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                for (int py = y * scale; py < (y + 1) * scale; py++)
                    for (int px = x * scale; px < (x + 1) * scale; px++) output.setPixel(px, py, color);
            }
            return output;
        } catch (Exception ignored) {
            return null;
        } finally {
            source.recycle();
        }
    }

    static Uri requireWebUri(String value, boolean originOnly) {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null)
            throw new IllegalArgumentException("url_must_use_http_or_https");
        if (uri.getUserInfo() != null || uri.getHost().isEmpty()) throw new IllegalArgumentException("invalid_url");
        if (originOnly && (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()) || uri.getQuery() != null || uri.getFragment() != null))
            throw new IllegalArgumentException("cookie_origin_must_be_an_origin");
        return uri;
    }

    private TextView actionButton(String text, LayoutSpec layout) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, layout.chromeTextPx);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(48, 48, 48));
        background.setCornerRadius(layout.actionHeightPx / 2f);
        button.setBackground(background);
        return button;
    }

    static boolean sameOrigin(Uri left, Uri right) {
        if (left == null || right == null || left.getScheme() == null || right.getScheme() == null || left.getHost() == null || right.getHost() == null) return false;
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost()) && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(Uri uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static Set<String> requiredCookieNames(JSONArray array) {
        if (array == null) throw new IllegalArgumentException("required_cookie_names_required");
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i, "");
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) throw new IllegalArgumentException("invalid_cookie_name");
            names.add(name);
        }
        return names;
    }

    static boolean containsRequiredCookies(String raw, Set<String> required) {
        if (raw == null || raw.isEmpty()) return false;
        Set<String> present = new LinkedHashSet<>();
        for (String part : raw.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0 && !part.substring(equals + 1).trim().isEmpty()) present.add(part.substring(0, equals).trim());
        }
        return present.containsAll(required);
    }

    static LayoutSpec layoutSpec(int widthPx, int heightPx, float density, boolean round) {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0) throw new IllegalArgumentException("invalid_display_metrics");
        // Wear displays are physically small but often report a high density. Cap chrome in
        // physical pixels so it cannot consume the QR viewport (as dp-only sizing did).
        int status = Math.max(1, Math.round(heightPx * .047f));
        int actions = Math.max(1, Math.round(heightPx * .086f));
        int horizontal = Math.max(0, Math.min(Math.round((round ? 18 : 8) * density), Math.round(widthPx * (round ? .04f : .02f))));
        int vertical = round ? Math.round(heightPx * .047f) : Math.round(heightPx * .015f);
        int chromeText = Math.max(12, Math.round(Math.min(widthPx, heightPx) * .035f));
        int web = heightPx - status - actions - vertical * 2;
        if (web < 1) throw new IllegalArgumentException("display_too_small");
        double browserCenterY = vertical + status + web / 2.0;
        int qrSize = round
                ? Math.min(web, safeSquareSize(widthPx, heightPx, browserCenterY, 3))
                : Math.min(web, widthPx - horizontal * 2);
        int statusInset = round ? safeBandInset(widthPx, heightPx, vertical, vertical + status) : horizontal;
        int actionTop = vertical + status + web;
        int actionInset = round ? safeBandInset(widthPx, heightPx, actionTop, actionTop + actions) : horizontal;
        return new LayoutSpec(horizontal, vertical, status, web, actions, statusInset, actionInset, chromeText, qrSize);
    }

    static int safeBandInset(int width, int height, int top, int bottom) {
        double radius = Math.min(width, height) / 2.0;
        double centerY = height / 2.0;
        double farthestY = Math.max(Math.abs(top - centerY), Math.abs(bottom - centerY));
        double halfChord = Math.sqrt(Math.max(0, radius * radius - farthestY * farthestY));
        return Math.max(0, (int) Math.ceil(width / 2.0 - halfChord) + 2);
    }

    static int safeSquareSize(int width, int height, double squareCenterY, int marginPx) {
        double circleCenterY = height / 2.0;
        double radius = Math.min(width, height) / 2.0 - Math.max(0, marginPx);
        double offsetY = Math.abs(squareCenterY - circleCenterY);
        // h^2 + (h + offsetY)^2 <= radius^2, where h is the square half-size.
        double discriminant = 2 * radius * radius - offsetY * offsetY;
        if (discriminant <= 0) return 1;
        return Math.max(1, (int) Math.floor(-offsetY + Math.sqrt(discriminant)));
    }

    static final class LayoutSpec {
        final int horizontalInsetPx;
        final int verticalInsetPx;
        final int statusHeightPx;
        final int webViewHeightPx;
        final int actionHeightPx;
        final int statusInsetPx;
        final int actionInsetPx;
        final int chromeTextPx;
        final int qrSizePx;
        LayoutSpec(int horizontalInsetPx, int verticalInsetPx, int statusHeightPx,
                   int webViewHeightPx, int actionHeightPx, int statusInsetPx, int actionInsetPx, int chromeTextPx,
                   int qrSizePx) {
            this.horizontalInsetPx = horizontalInsetPx;
            this.verticalInsetPx = verticalInsetPx;
            this.statusHeightPx = statusHeightPx;
            this.webViewHeightPx = webViewHeightPx;
            this.actionHeightPx = actionHeightPx;
            this.statusInsetPx = statusInsetPx;
            this.actionInsetPx = actionInsetPx;
            this.chromeTextPx = chromeTextPx;
            this.qrSizePx = qrSizePx;
        }
    }

    private static String originString(Uri uri) {
        String value = uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase();
        int port = uri.getPort();
        return port < 0 ? value : value + ":" + port;
    }

    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    private static final class JSONObjectResult {
        final JSONObject json;
        JSONObjectResult(String cookie, String origin) {
            try { json = new JSONObject().put("cookie", cookie).put("origin", origin); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    @Override public void close() {
        closed = true;
        qrWorker.shutdownNow();
        main.post(() -> finishFailure("service_closed", "Browser authorization service is closed"));
    }
}
