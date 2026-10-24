package dev.podjs.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import org.json.JSONObject;

/** Opens user-requested HTTP(S) URLs in the system browser. */
final class PodBrowserServices {
    private final Context context;
    private PodInAppBrowser session;
    private JSONObject pendingRestoration;
    String savedState() { return session == null ? (pendingRestoration == null ? null : PodModalState.encode(pendingRestoration)) : session.snapshot(); }
    void restoreState(String saved) {
        pendingRestoration = null;
        try {
            JSONObject state = PodBrowserPageState.decode(saved);
            if (state == null) return;
            PodInAppBrowser.Options options = new PodInAppBrowser.Options(state.getJSONObject("options"));
            if (!options.restoreActivity) return;
            String url = PodInAppBrowser.resolveUrl(state.opt("url"));
            if (!PodInAppBrowser.needsInternet(url) && !("file".equalsIgnoreCase(Uri.parse(options.url).getScheme()) && "file".equalsIgnoreCase(Uri.parse(url).getScheme()))) return;
            pendingRestoration = state;
        } catch (Exception invalid) { pendingRestoration = null; }
    }
    void resumeRestored() {
        JSONObject state = pendingRestoration; pendingRestoration = null;
        if (state == null || session != null || !(context instanceof Activity)) return;
        try {
            PodInAppBrowser.Options options = new PodInAppBrowser.Options(state.getJSONObject("options"));
            PodInAppBrowser.checkAvailable(context,state.getString("url"));
            session = new PodInAppBrowser((Activity)context,options); session.restore(state); session.open();
        } catch (Exception | LinkageError unavailable) { close(); }
    }
    static final class BrowserFailure extends IllegalStateException {
        final String code;
        BrowserFailure(String code, String message) { super(message); this.code = code; }
    }
    void openInApp(JSONObject args) {
        pendingRestoration = null;
        PodInAppBrowser.Options options = new PodInAppBrowser.Options(args);
        if (!(context instanceof Activity)) throw new BrowserFailure("unavailable", "Browser opening requires an Activity");
        if (session != null && session.isShowing()) throw new BrowserFailure("busy", "An embedded browser is already open");
        PodInAppBrowser.checkAvailable(context, options.url);
        session = new PodInAppBrowser((Activity)context, options);
        try { session.open(); }
        catch (RuntimeException | LinkageError error) {
            close();
            String message = PodInAppBrowser.UNAVAILABLE;
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
            throw new BrowserFailure("webview_unavailable", message);
        }
    }
    boolean updateTheme(JSONObject args) throws org.json.JSONException {
        // Validate even when no page is open. Never open a page as a side effect.
        PodInAppBrowser.Options checked = new PodInAppBrowser.Options(new JSONObject().put("url","https://validation.invalid").put("mode","inApp").put("theme",args.getJSONObject("theme")));
        if(pendingRestoration!=null){pendingRestoration.getJSONObject("options").put("theme",checked.source.getJSONObject("theme"));return true;}
        return session != null && session.updateTheme(checked);
    }
    void close() { pendingRestoration = null; if (session != null) { session.dismiss(); session = null; } }
    PodBrowserServices(Context context) { this.context = context; }

    void open(JSONObject args) {
        String value = args.optString("url", "");
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null)
            throw new IllegalArgumentException("Browser URL must use HTTP or HTTPS");
        if (!(context instanceof Activity)) throw new IllegalStateException("Browser opening requires an Activity");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager packages = context.getPackageManager();
        if (intent.resolveActivity(packages) == null) throw new IllegalStateException("no_browser_handler");
        context.startActivity(intent);
    }
}
