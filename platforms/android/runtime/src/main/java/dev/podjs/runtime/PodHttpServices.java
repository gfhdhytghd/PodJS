package dev.podjs.runtime;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streaming HTTP download service. This helper is intentionally not wired into the bridge. */
public final class PodHttpServices implements AutoCloseable {
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private final File root;
    private final Map<Integer, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final Map<Integer, HttpURLConnection> connections = new ConcurrentHashMap<>();
    private final Object cancellationLock = new Object();
    private final java.util.Set<File> temporaryFiles = new java.util.HashSet<>();
    private volatile boolean closed;

    public PodHttpServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        File base = PodTakeover.filesRoot(context);
        if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("service_root_unavailable");
        try { root = base.getCanonicalFile(); } catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
    }

    public JSONObject execute(String method, JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException("service_closed");
        if (!"http.download".equals(method) || args == null) throw new IllegalArgumentException("unsupported_method");
        int requestId = args.optInt("requestId", 0);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (requestId != 0 && cancellations.putIfAbsent(requestId, cancelled) != null) throw new IllegalArgumentException("duplicate_request_id");
        try { return download(args, cancelled); }
        finally { if (requestId != 0) { cancellations.remove(requestId, cancelled); connections.remove(requestId); } }
    }

    /** Cancels a currently running download with the matching request id. */
    public boolean cancel(int requestId) {
        AtomicBoolean flag = cancellations.get(requestId);
        if (flag == null) return false;
        boolean changed;
        synchronized (cancellationLock) {
            changed = flag.compareAndSet(false, true);
            HttpURLConnection connection = connections.get(requestId);
            if (connection != null) connection.disconnect();
        }
        return changed;
    }

    @Override public void close() { synchronized (cancellationLock) { closed = true; for (File file : temporaryFiles) file.delete(); temporaryFiles.clear(); for (Map.Entry<Integer, AtomicBoolean> entry : cancellations.entrySet()) { entry.getValue().set(true); HttpURLConnection connection = connections.get(entry.getKey()); if (connection != null) connection.disconnect(); } } }

    void discard(JSONObject result) {
        synchronized (cancellationLock) {
            try { File file=safeFile(result.getString("path")); if(temporaryFiles.remove(file))file.delete(); }
            catch(Exception ignored) { }
        }
    }

    private JSONObject download(JSONObject args, AtomicBoolean cancelled) throws Exception {
        String method = args.optString("method", "GET").toUpperCase(Locale.US);
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) throw new IllegalArgumentException("unsupported_download_method");
        String encodedBody=args.optString("bodyBase64", "");
        if(encodedBody.length()>1_400_000)throw new IllegalArgumentException("request_body_too_large");
        byte[] requestBody=android.util.Base64.decode(encodedBody,android.util.Base64.DEFAULT);
        if(requestBody.length>1024*1024)throw new IllegalArgumentException("request_body_too_large");
        String rawUrl = required(args, "url");
        File target = safeFile(required(args, "path"));
        long maxBytes = args.has("maxBytes") ? args.getLong("maxBytes") : DEFAULT_MAX_BYTES;
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("invalid_max_bytes");
        int timeout = args.optInt("timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeout < 1 || timeout > 120_000) throw new IllegalArgumentException("invalid_timeout_ms");
        int readTimeout = args.optInt("readTimeoutMs", timeout);
        if (readTimeout < 1 || readTimeout > 600_000) throw new IllegalArgumentException("invalid_read_timeout_ms");
        Map<String, String> headers = requestHeaders(args.optJSONObject("headers"));
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("parent_unavailable");
        File tmp = new File(parent, target.getName() + ".download-" + System.nanoTime());
        HttpURLConnection connection = null;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            URL current = checkedUrl(rawUrl);
            int redirects = 0;
            while (true) {
                if (closed || cancelled.get()) throw new IOException("download_cancelled");
                connection = (HttpURLConnection) current.openConnection();
                int requestId = args.optInt("requestId", 0);
                if (requestId != 0) connections.put(requestId, connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(timeout); connection.setReadTimeout(readTimeout);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if("POST".equals(method)){connection.setDoOutput(true);connection.setFixedLengthStreamingMode(requestBody.length);try(java.io.OutputStream request=connection.getOutputStream()){request.write(requestBody);}}
                int status = connection.getResponseCode();
                if (status < 300 || status > 399) {
                    boolean success = status >= 200 && status < 300;
                    if (!success && !args.optBoolean("acceptHttpErrors", false)) throw new IOException("http_status:" + status);
                    long length = 0;
                    if (!"HEAD".equals(method)) {
                        java.io.InputStream responseStream = success ? connection.getInputStream() : connection.getErrorStream();
                        try (java.io.InputStream in = responseStream == null ? new java.io.ByteArrayInputStream(new byte[0]) : responseStream) {
                            if(args.optBoolean("text",false)) length=copyText(in,out,connection.getContentType(),maxBytes,cancelled);
                            else {
                            byte[] buffer = new byte[BUFFER_SIZE]; int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (closed || cancelled.get()) throw new IOException("download_cancelled");
                                length += n; if (length > maxBytes) throw new IOException("download_too_large");
                                out.write(buffer, 0, n);
                            }
                            }
                        }
                    }
                    synchronized (cancellationLock) {
                        if (closed || cancelled.get()) throw new IOException("download_cancelled");
                        boolean temporary=args.optBoolean("temporary",false);
                        if(temporary && target.exists())throw new IOException("temporary_target_exists");
                        out.getFD().sync();
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        temporaryFiles.removeIf(file -> !file.exists());
                        if(temporary)temporaryFiles.add(target);else temporaryFiles.remove(target);
                    }
                    return result(status, connection, current, target, length);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || ++redirects > MAX_REDIRECTS) throw new IOException("redirect_limit");
                URL next = checkedUrl(current.toURI().resolve(location).toURL().toString());
                if (!origin(current).equals(origin(next))) {
                    if("POST".equals(method))throw new IOException("cross_origin_post_redirect");
                    headers.remove("authorization"); headers.remove("cookie"); headers.remove("proxy-authorization");
                }
                if("POST".equals(method)&&(status==301||status==302||status==303)){method="GET";requestBody=new byte[0];headers.remove("content-type");}
                current = next;
                connection.disconnect(); connection = null;
            }
        } finally { if (connection != null) connection.disconnect(); if (tmp.exists()) tmp.delete(); }
    }

    /** Decode response text using its BOM/declared charset and store bounded UTF-8 bytes. */
    private long copyText(java.io.InputStream input,FileOutputStream output,String contentType,long maximum,AtomicBoolean cancelled) throws Exception {
        java.io.PushbackInputStream stream=new java.io.PushbackInputStream(new java.io.FilterInputStream(input){
            long read=0;
            private void check(int n)throws IOException{if(closed||cancelled.get())throw new IOException("download_cancelled");if(n>0&&(read+=n)>maximum)throw new IOException("download_too_large");}
            @Override public int read()throws IOException{int b=super.read();check(b<0?0:1);return b;}
            @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);check(n);return n;}
        },4);
        java.nio.charset.Charset charset=java.nio.charset.StandardCharsets.UTF_8;
        if(contentType!=null){java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").matcher(contentType);if(match.find())try{charset=java.nio.charset.Charset.forName(match.group(1));}catch(IllegalArgumentException ignored){}}
        byte[] prefix=new byte[4];int count=0;while(count<4){int n=stream.read(prefix,count,4-count);if(n<0)break;count+=n;}
        int skip=0;
        if(count>=4&&prefix[0]==0&&prefix[1]==0&&(prefix[2]&255)==254&&(prefix[3]&255)==255){charset=java.nio.charset.Charset.forName("UTF-32BE");skip=4;}
        else if(count>=4&&(prefix[0]&255)==255&&(prefix[1]&255)==254&&prefix[2]==0&&prefix[3]==0){charset=java.nio.charset.Charset.forName("UTF-32LE");skip=4;}
        else if(count>=3&&(prefix[0]&255)==239&&(prefix[1]&255)==187&&(prefix[2]&255)==191){charset=java.nio.charset.StandardCharsets.UTF_8;skip=3;}
        else if(count>=2&&(prefix[0]&255)==254&&(prefix[1]&255)==255){charset=java.nio.charset.StandardCharsets.UTF_16BE;skip=2;}
        else if(count>=2&&(prefix[0]&255)==255&&(prefix[1]&255)==254){charset=java.nio.charset.StandardCharsets.UTF_16LE;skip=2;}
        if(count>skip)stream.unread(prefix,skip,count-skip);
        final long[] written={0};
        java.io.OutputStream bounded=new java.io.OutputStream(){
            @Override public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
            @Override public void write(byte[] b,int off,int len)throws IOException{if(closed||cancelled.get())throw new IOException("download_cancelled");if((written[0]+=len)>maximum)throw new IOException("download_too_large");output.write(b,off,len);}
        };
        try(java.io.Reader reader=new java.io.InputStreamReader(stream,charset);java.io.Writer writer=new java.io.OutputStreamWriter(bounded,java.nio.charset.StandardCharsets.UTF_8)){
            char[] chars=new char[8192];int n;while((n=reader.read(chars))!=-1)writer.write(chars,0,n);
        }
        return written[0];
    }

    private JSONObject result(int status, HttpURLConnection c, URL url, File target, long size) throws Exception {
        JSONObject headers = new JSONObject();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) headers.put(e.getKey(), e.getValue() == null ? "" : String.join(", ", e.getValue()));
        }
        return new JSONObject().put("status", status).put("headers", headers).put("url", url.toString()).put("path", relative(target)).put("size", size);
    }

    private String relative(File file) throws IOException { return root.toPath().relativize(file.getCanonicalFile().toPath()).toString(); }
    private File safeFile(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_path");
        File f = new File(root, path).getCanonicalFile(); String base = root.getPath() + File.separator;
        if (!f.getPath().startsWith(base)) throw new SecurityException("path_outside_root"); return f;
    }
    private static String required(JSONObject a, String key) { String value = a.optString(key, null); if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing_" + key); return value; }
    private static URL checkedUrl(String value) throws Exception { URI uri = URI.create(value); String scheme = uri.getScheme(); if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getUserInfo() != null) throw new IllegalArgumentException("invalid_http_url"); return uri.toURL(); }
    private static String origin(URL url) { return url.getProtocol().toLowerCase(Locale.US) + "://" + url.getAuthority().toLowerCase(Locale.US); }
    private static Map<String, String> requestHeaders(JSONObject object) throws Exception {
        if (object == null) return new HashMap<>();
        Map<String, String> result = new HashMap<>();
        org.json.JSONArray names = object.names();
        if (names == null) return result;
        for (int i = 0; i < names.length(); i++) { String key = names.getString(i); if (object.isNull(key)) continue; result.put(key.toLowerCase(Locale.US), object.getString(key)); }
        return result;
    }
}
