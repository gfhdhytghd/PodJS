package dev.podjs.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-thread WebView operations. Never clears global cookies or global storage. */
final class PodWebViewServices {
    interface Backend {
        String provider();
        void initialize();
        String cookies(String origin);
        void expire(String origin, String value);
        void flush();
        void deleteOrigin(String origin);
    }
    private final Backend backend;
    PodWebViewServices(Context context) {
        this(new Backend() {
            public String provider() {
                PackageInfo info = WebView.getCurrentWebViewPackage();
                return info == null ? null : info.packageName;
            }
            public void initialize() {
                WebView view = new WebView(context);
                try { view.getSettings(); } finally { view.destroy(); }
            }
            public String cookies(String origin) { return CookieManager.getInstance().getCookie(origin); }
            public void expire(String origin, String value) { CookieManager.getInstance().setCookie(origin, value); }
            public void flush() { CookieManager.getInstance().flush(); }
            public void deleteOrigin(String origin) { WebStorage.getInstance().deleteOrigin(origin); }
        });
    }
    PodWebViewServices(Backend backend) { this.backend = backend; }

    JSONObject availability() throws Exception {
        try {
            String provider = backend.provider();
            if (provider == null) return new JSONObject().put("status", "no_provider");
            backend.initialize();
            return new JSONObject().put("status", "available").put("provider", provider);
        } catch (RuntimeException | LinkageError error) {
            return new JSONObject().put("status", "initialization_failed").put("message", message(error));
        }
    }

    // Validate the entire request before touching any origin, even when called
    // directly through the bridge without the TypeScript wrapper.
    static List<URI> origins(JSONObject args) {
        JSONArray values = args.optJSONArray("origins");
        if (values == null || values.length() == 0 || values.length() > 64)
            throw new IllegalArgumentException("origins must contain 1 to 64 bare HTTPS origins");
        List<URI> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (!(value instanceof String)) throw new IllegalArgumentException("origin must be a string");
            URI uri;
            try { uri = new URI((String)value); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid HTTPS origin"); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"".equals(uri.getRawPath()) || uri.getPort() == 0 || uri.getPort() > 65535
                    || !uri.getRawAuthority().equals(uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())))
                throw new IllegalArgumentException("Only bare HTTPS origins are allowed");
            if (seen.add(uri.toString())) result.add(uri);
        }
        return result;
    }

    JSONArray clearSiteData(JSONObject args) throws Exception {
        List<URI> origins = origins(args);
        JSONArray results = new JSONArray();
        for (URI origin : origins) {
            String url = origin.toString();
            JSONObject cookies = new JSONObject();
            try {
                Set<String> names = cookieNames(backend.cookies(url));
                for (String name : names) {
                    String expired = name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/; Secure";
                    backend.expire(url, expired);
                    // Only explicitly listed hosts may be used as Domain targets.
                    // Listing https://douyin.com permits removal of .douyin.com
                    // cookies also observed from https://www.douyin.com.
                    for (URI domain : origins) {
                        String host = domain.getHost().toLowerCase(java.util.Locale.ROOT);
                        String source = origin.getHost().toLowerCase(java.util.Locale.ROOT);
                        if (host.indexOf(':') < 0 && (source.equals(host) || source.endsWith("." + host)))
                            backend.expire(url, expired + "; Domain=." + host);
                    }
                }
                backend.flush();
                Set<String> remaining = cookieNames(backend.cookies(url));
                cookies.put("status", remaining.isEmpty() ? "root_cleared" : "remaining")
                        .put("remainingNames", new JSONArray(remaining));
            } catch (RuntimeException | LinkageError error) {
                cookies.put("status", "failed").put("message", message(error));
            }
            JSONObject storage = new JSONObject();
            try {
                backend.deleteOrigin(url);
                storage.put("status", "deletion_requested");
            } catch (RuntimeException | LinkageError error) {
                storage.put("status", "failed").put("message", message(error));
            }
            results.put(new JSONObject().put("origin", url).put("cookies", cookies).put("webStorage", storage));
        }
        return results;
    }

    static Set<String> cookieNames(String header) {
        Set<String> names = new LinkedHashSet<>();
        if (header != null) for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String name = part.substring(0, equals).trim();
            // Never turn malformed provider output into a cookie attribute.
            if (name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) names.add(name);
        }
        return names;
    }
    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
