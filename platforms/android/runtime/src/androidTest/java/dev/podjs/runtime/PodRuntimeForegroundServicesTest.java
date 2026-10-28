package dev.podjs.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Surface;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real Java completion jobs and JNI without Choreographer assistance.
 * The raw guest observes frame/event entry, not the framework's virtual clock. */
public final class PodRuntimeForegroundServicesTest {
    @Test public void saveStateBarrierCompletesPriorWritesBeyondOrdinaryEffectBatchLimit() throws Exception {
        try(Harness h=new Harness("checkpoint-effect-boundary-"+UUID.randomUUID())){
            onMain(()->{
                h.install("globalThis.frame=()=>{};globalThis.__pocketPumpServices=()=>pod.takeEvents();"
                    +"for(let i=0;i<130;i++)pod.emit(JSON.stringify({t:'service.request',version:1,id:i+1,"
                    +"method:'app.activityState.write',args:{saved:{marker:i},retained:{}}}));");
                // This is Android's real pre-save durability barrier, not a test
                // rescue pump. A normal bounded effects batch cannot stop it at64.
                JSONObject saved=new JSONObject(h.view.savedActivityState());
                assertEquals("last prior write must be durable before returning",129,saved.getInt("marker"));
                return null;
            });
        }
    }
    @Test public void longJsonPromiseChainYieldsToRealMainTouchBeforeCompletionWithoutFrames() throws Exception {
        String nonce=UUID.randomUUID().toString();
        try(Harness h=new Harness("bounded-jobs-"+nonce)){
            String source=fixture(nonce,"(async function(){"
                +"await request('runtime.delay',{milliseconds:20});"
                +"report('started',{});const chunk=JSON.stringify(Array.from({length:1024},(_,i)=>i));let checksum=0;"
                +"for(let i=0;i<30000;i++){checksum+=JSON.parse(chunk)[i%1024];await Promise.resolve();}"
                +"await report('done',{checksum});})().catch(failed);");
            onMain(()->{h.install(source);h.postNonResultSentinels();h.startOnce();return null;});
            assertEquals("old drain-to-empty completes before this state can be observed","started",h.awaitReport(nonce).getString("status"));
            long queued=System.nanoTime();
            onMain(()->{
                long handle=(Long)field(h.view,"host");
                assertTrue("touch must interleave with unfinished JS work",(Boolean)nativeCall("nativeHasPendingJobs",new Class<?>[]{long.class},handle));
                long time=android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent event=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,16,16,0);
                try{assertTrue(h.view.onTouchEvent(event));}finally{event.recycle();}
                event=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_UP,16,16,0);
                try{assertTrue(h.view.onTouchEvent(event));}finally{event.recycle();}
                assertTrue("main touch dispatch exceeded fixed 50ms budget",System.nanoTime()-queued<50_000_000L);
                return null;
            });
            JSONObject result=h.awaitReport(nonce,"done");assertCleanCompletion(result);
            long expected=0;for(int i=0;i<30000;i++)expected+=i%1024;
            assertEquals(expected,result.getLong("checksum"));
        }
    }
    @Test public void foregroundCompletionsDriveFileHttpReadAndDeleteWithoutFramesOrInput() throws Exception {
        String nonce = UUID.randomUUID().toString();
        String prefix = "foreground-services-" + nonce;
        byte[] body = "A service completion can start the next IO operation.\n".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.encodeToString(body, Base64.NO_WRAP);
        try (OneResponseServer server = new OneResponseServer(body);
             Harness harness = new Harness(prefix)) {
            String source = fixture(nonce,
                    "(async function(){"
                    + "await request('runtime.delay',{milliseconds:20});"
                    + "await request('file.write',{path:" + quote(prefix + "/seed.bin") + ",dataBase64:" + quote(encoded) + "});"
                    + "const downloaded=await request('http.download',{url:" + quote(server.url())
                    + ",path:" + quote(prefix + "/download.bin") + ",timeoutMs:3000,maxBytes:1024});"
                    + "const read=await request('file.read',{path:downloaded.path,maxBytes:1024});"
                    + "if(read.dataBase64!==" + quote(encoded) + ")throw Error('downloaded bytes changed');"
                    + "await request('file.delete',{path:" + quote(prefix + "/seed.bin") + "});"
                    + "await request('file.delete',{path:downloaded.path});"
                    + "await report('done',{body:read.dataBase64,deletedPath:downloaded.path});"
                    + "})().catch(failed);");
            onMain(() -> {
                harness.install(source);
                harness.postNonResultSentinels();
                harness.startOnce();
                return null;
            });

            JSONObject report = harness.awaitReport(nonce);
            assertCleanCompletion(report);
            assertEquals(encoded, report.getString("body"));
            assertEquals(prefix + "/download.bin", report.getString("deletedPath"));
            assertFalse(new File(harness.serviceDirectory, "seed.bin").exists());
            assertFalse(new File(harness.serviceDirectory, "download.bin").exists());
            assertEquals("each completion advanced the next operation", 6, report.getJSONArray("received").length());
            server.assertServed();
            onMain(() -> {
                assertEquals("foreground lifecycle", 0, field(harness.view, "lifecycleState"));
                assertFalse("no Choreographer frame ran", (Boolean) field(harness.view, "firstFrameLogged"));
                return null;
            });
        }
    }

    @Test public void cancellationRemovesPendingTimerWhileIndependentCompletionContinues() throws Exception {
        String nonce = UUID.randomUUID().toString();
        try (Harness harness = new Harness("foreground-cancel-" + nonce)) {
            String source = fixture(nonce,
                    "(async function(){let resolved=0,rejected=0;"
                    + "const cancelled=request('runtime.delay',{milliseconds:120000});"
                    + "const cancelledId=cancelled.requestId;"
                    + "cancelled.then(()=>resolved++,()=>rejected++);"
                    + "await request('runtime.delay',{milliseconds:20});"
                    + "cancelRequest(cancelledId);"
                    + "await request('runtime.delay',{milliseconds:20});"
                    + "await report('done',{cancelledId,resolved,rejected,"
                    + "cancelledResults:received.filter(id=>id===cancelledId).length});"
                    + "})().catch(failed);");
            AtomicReference<Runnable> cancelledTimer = new AtomicReference<>();
            onMain(() -> {
                harness.install(source);
                harness.postNonResultSentinels();
                harness.startOnce();
                Object request = ((Map<?, ?>) field(harness.services, "requests")).get(1);
                assertNotNull("the long timer was dispatched", request);
                cancelledTimer.set((Runnable) field(request, "timer"));
                assertNotNull(cancelledTimer.get());
                assertTrue(((Handler) field(harness.services, "main")).hasCallbacks(cancelledTimer.get()));
                return null;
            });
            JSONObject report = harness.awaitReport(nonce);
            assertCleanCompletion(report);
            assertEquals(0, report.getInt("resolved"));
            assertEquals(1, report.getInt("rejected"));
            assertEquals("cancelled timer must produce no host result", 0, report.getInt("cancelledResults"));
            assertEquals(2, report.getJSONArray("received").length());
            onMain(() -> {
                assertFalse("cancel removes the request identity", ((Map<?, ?>) field(harness.services, "requests")).containsKey(1));
                assertFalse("cancel removes the actual pending timer", ((Handler) field(harness.services, "main")).hasCallbacks(cancelledTimer.get()));
                return null;
            });
        }
    }

    @Test public void queuedRealCompletionCannotCrossDestroyedAndReplacedHostLifetime() throws Exception {
        String oldNonce = "old-" + UUID.randomUUID();
        String newNonce = "new-" + UUID.randomUUID();
        try (Harness harness = new Harness("foreground-owner-" + UUID.randomUUID())) {
            String oldSource = fixture(oldNonce,
                    "(async function(){await request('app.activityState.read',{});"
                    + "await report('old-continuation',{});})().catch(failed);");
            String newSource = fixture(newNonce,
                    "(async function(){const value=await request('runtime.delay',{milliseconds:120});"
                    + "await report('done',{firstRequestId:1,valueWasNull:value===null});})().catch(failed);");
            CountDownLatch replaced = new CountDownLatch(1);
            AtomicReference<Throwable> replacementFailure = new AtomicReference<>();
            onMain(() -> {
                harness.services.activityState.restore("{\"marker\":\"old-owner-read\"}", "{}");
                harness.install(oldSource);
                Object oldLifetime = field(harness.view, "hostLifetime");
                harness.startOnce();
                // app.activityState.read completes synchronously but PodServices
                // posts its sink delivery. That delivery posts the real view job
                // behind this replacement, so the stale completion is already
                // captured with the old lifetime when it eventually executes.
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        invoke(harness.view, "destroyHost", new Class<?>[0]);
                        assertEquals(0L, field(harness.view, "host"));
                        harness.install(newSource);
                        assertNotSame(oldLifetime, field(harness.view, "hostLifetime"));
                        harness.startOnce();
                    } catch (Throwable error) { replacementFailure.set(error); }
                    finally { replaced.countDown(); }
                });
                return null;
            });
            assertTrue("host replacement timed out", replaced.await(5, TimeUnit.SECONDS));
            if (replacementFailure.get() != null) throw new AssertionError("host replacement failed", replacementFailure.get());
            JSONObject report = harness.awaitReport(newNonce);
            assertCleanCompletion(report);
            assertEquals(1, report.getInt("firstRequestId"));
            // Both guests start at request 1. The old read's object would settle
            // the new delay incorrectly if ownership were checked by ID alone.
            assertTrue("only the replacement's real timer may settle request 1", report.getBoolean("valueWasNull"));
            assertEquals(1, report.getJSONArray("received").length());
        }
    }

    private static String fixture(String nonce, String body) {
        return "let nextId=0,frameCalls=0;const pending=new Map(),received=[],nonResults=[];"
                + "function request(method,args){const id=++nextId;"
                + "const promise=new Promise((resolve,reject)=>pending.set(id,{resolve,reject}));"
                + "promise.requestId=id;pod.emit(JSON.stringify({t:'service.request',version:1,id,method,args}));return promise;}"
                + "function cancelRequest(id){const entry=pending.get(id);if(!entry)return;pending.delete(id);"
                + "pod.emit(JSON.stringify({t:'service.cancel',id}));entry.reject(Error('cancelled'));}"
                + "function report(status,extra){return request('app.activityState.write',{saved:Object.assign({nonce:"
                + quote(nonce) + ",status,frameCalls,nonResults:nonResults.slice(),received:received.slice()},extra||{}),retained:{}});}"
                + "function failed(error){report('error',{message:String(error)});}"
                + "globalThis.__pocketPumpServices=function(){const batch=JSON.parse(pod.takeEvents()||'[]');"
                + "for(const event of batch){if(event.t!=='service.result'){nonResults.push(event.t);continue;}"
                + "received.push(event.id);const entry=pending.get(event.id);if(!entry)continue;pending.delete(event.id);"
                + "if(event.ok)entry.resolve(event.value);else entry.reject(Error(event.code+': '+event.message));}};"
                + "globalThis.frame=function(){frameCalls++;report('unexpected-frame',{});};"
                + body;
    }

    private static void assertCleanCompletion(JSONObject report) throws Exception {
        assertEquals(report.toString(), "done", report.getString("status"));
        assertEquals("completion must not invoke the guest frame", 0, report.getInt("frameCalls"));
        assertEquals("completion must not dispatch input, theme, or lifecycle events", 0,
                report.getJSONArray("nonResults").length());
    }

    private static final class Harness implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File serviceDirectory;
        final List<File> nativeDirectories = new ArrayList<>();
        final PodRuntimeView view;
        final PodServices services;
        final SurfaceTexture texture;
        final Surface surface;
        boolean started;

        Harness(String servicePrefix) throws Exception {
            serviceDirectory = new File(context.getFilesDir(), "podjs/files/" + servicePrefix);
            view = onMain(() -> new PodRuntimeView(context, "android-watch"));
            services = (PodServices) field(view, "services");
            texture = onMain(() -> {
                SurfaceTexture value = new SurfaceTexture(false);
                value.setDefaultBufferSize(64, 64);
                return value;
            });
            surface = new Surface(texture);
        }

        void install(String source) throws Exception {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            assertEquals("replace only a destroyed host", 0L, field(view, "host"));
            File directory = new File(context.getCacheDir(), "foreground-native-" + UUID.randomUUID());
            assertTrue(directory.mkdir());
            nativeDirectories.add(directory);
            long handle = (Long) nativeCall("nativeCreate",
                    new Class<?>[] {Surface.class, String.class, int.class, int.class, float.class, int.class, int.class, int.class, String.class},
                    surface, "android-watch", 64, 64, 1f, 8, 50, 8000, directory.getAbsolutePath());
            assertNotEquals(0, handle);
            invoke(view, "setHost", new Class<?>[] {long.class}, handle);
            // Like the existing semantic JNI fixture, this needs a valid native
            // host but never submits presentation buffers or schedules a frame.
            nativeCall("nativeDetachSurface", new Class<?>[] {long.class}, handle);
            byte[] pak = "pak".getBytes(StandardCharsets.UTF_8);
            byte[] js = source.getBytes(StandardCharsets.UTF_8);
            JSONObject manifest = manifest();
            manifest.put("target", "android-watch").put("hostAbi", 2)
                    .put("pakHash", hash(pak)).put("bundleHash", hash(js));
            nativeCall("nativeBoot", new Class<?>[] {long.class, byte[].class, byte[].class, byte[].class},
                    handle, pak, js, manifest.toString().getBytes(StandardCharsets.UTF_8));
            started = false;
        }

        void startOnce() throws Exception {
            assertFalse("the test must not rescue progress with another pump", started);
            started = true;
            Object lifetime = field(view, "hostLifetime");
            assertNotNull(lifetime);
            // This is the same one-off turn used after a successful native boot.
            // All later turns must originate from real Java service completions.
            invoke(view, "pumpServiceResults", new Class<?>[] {lifetime.getClass()}, lifetime);
        }

        void postNonResultSentinels() throws Exception {
            long handle = (Long) field(view, "host");
            for (String event : new String[] {
                    "{\"t\":\"input.scroll\",\"vertical\":1,\"uptimeMs\":1,\"source\":1}",
                    "{\"t\":\"back\"}", "{\"t\":\"theme\",\"theme\":\"dark\"}",
                    "{\"t\":\"lifecycle\",\"state\":\"inactive\"}"}) {
                nativeCall("nativePostEvent", new Class<?>[] {long.class, byte[].class},
                        handle, event.getBytes(StandardCharsets.UTF_8));
            }
        }

        JSONObject awaitReport(String nonce) throws Exception {
            return awaitReport(nonce,null);
        }
        JSONObject awaitReport(String nonce,String status) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            JSONObject last = new JSONObject();
            do {
                // This getter only copies state. PodRuntimeView.savedActivityState
                // would pump services and invalidate the purpose of this test.
                last = new JSONObject(services.activityState.saved());
                if (nonce.equals(last.optString("nonce"))&&(status==null||status.equals(last.optString("status")))) return last;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("foreground completion chain stalled; last state=" + last);
        }

        @Override public void close() throws Exception {
            onMain(() -> {
                invoke(view, "destroyHost", new Class<?>[0]);
                services.close();
                ((ExecutorService) field(view, "network")).shutdownNow();
                return null;
            });
            onMain(() -> null);
            surface.release();
            texture.release();
            removeDirectory(serviceDirectory);
            for (File directory : nativeDirectories) removeDirectory(directory);
        }
    }

    private static final class OneResponseServer implements AutoCloseable {
        final ServerSocket server;
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> request = new AtomicReference<>();
        final Thread thread;

        OneResponseServer(byte[] body) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(5000);
            thread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    request.set(input.readLine());
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    OutputStream output = client.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(body);
                    output.flush();
                } catch (Throwable error) { failure.set(error); }
                finally { finished.countDown(); }
            }, "foreground-service-http");
            thread.start();
        }

        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/foreground-turn"; }

        void assertServed() throws Exception {
            assertTrue("HTTP fixture did not finish", finished.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("HTTP fixture failed", failure.get());
            assertEquals("GET /foreground-turn HTTP/1.1", request.get());
        }

        @Override public void close() throws Exception {
            server.close();
            thread.join(1000);
        }
    }

    private static JSONObject manifest() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = context.getAssets().open("semantic-host.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static String hash(byte[] bytes) {
        long result = 0xcbf29ce484222325L;
        for (byte value : bytes) { result ^= value & 255; result *= 0x100000001b3L; }
        return String.format(java.util.Locale.ROOT, "%016x", result);
    }

    private static String quote(String value) { return JSONObject.quote(value); }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object nativeCall(String name, Class<?>[] types, Object... values) throws Exception {
        return invoke(null, name, types, values);
    }

    private static Object invoke(Object instance, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = PodRuntimeView.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(instance, values); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static void removeDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) removeDirectory(child);
        if (directory.exists()) assertTrue("could not remove " + directory, directory.delete());
    }

    private static <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { value.set(action.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof Exception) throw (Exception) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        return value.get();
    }
}
