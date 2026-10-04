package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.SystemClock;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class PodDiscoveryServicesTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private JSONObject options(int window) throws Exception { return new JSONObject().put("serviceType", "_podjs-test._tcp.").put("namePrefix", "isolated-test-").put("expectedPort", 49177).put("windowMs", window); }
    @Test public void emptyScanIsBoundedAndCanBeRepeated() throws Exception {
        try (PodDiscoveryServices service = new PodDiscoveryServices(context())) {
            for (int i = 0; i < 2; i++) {
                long start = SystemClock.elapsedRealtime();
                assertEquals(0, service.execute("discovery.mdns", options(120), new CancellationSignal()).getJSONArray("endpoints").length());
                assertTrue(SystemClock.elapsedRealtime() - start < 2000);
            }
        }
    }
    @Test public void cancellationAndOwnerCloseWakePendingScan() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (boolean cancel : new boolean[]{true, false}) try (PodDiscoveryServices service = new PodDiscoveryServices(context())) {
                CancellationSignal signal = new CancellationSignal();
                Future<?> pending = worker.submit(() -> {
                    try { service.execute("discovery.mdns", options(30000), signal); throw new AssertionError("Unexpected discovery result"); }
                    catch (android.os.OperationCanceledException expected) { if (!cancel) throw expected; }
                    catch (IllegalStateException expected) { if (cancel || !expected.getMessage().equals("Discovery closed")) throw expected; }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                // Wait for actual registration, not merely submission of the worker.
                java.lang.reflect.Field field = PodDiscoveryServices.class.getDeclaredField("scans"); field.setAccessible(true);
                long until = SystemClock.elapsedRealtime() + 2000;
                Object scan = null;
                while (scan == null && SystemClock.elapsedRealtime() < until) {
                    synchronized (service) { java.util.Set<?> scans = (java.util.Set<?>) field.get(service); if (!scans.isEmpty()) scan = scans.iterator().next(); }
                    if (scan == null) Thread.sleep(1);
                }
                assertNotNull("Pending scan exists", scan);
                java.lang.reflect.Field started = scan.getClass().getDeclaredField("started"); started.setAccessible(true);
                boolean registered = false;
                while (!registered && SystemClock.elapsedRealtime() < until) { synchronized (scan) { registered = started.getBoolean(scan); } if (!registered) Thread.sleep(1); }
                assertTrue("Discovery registered before cancellation", registered);
                if (cancel) signal.cancel(); else service.close();
                pending.get(2, TimeUnit.SECONDS);
                synchronized (scan) {
                    assertFalse(started.getBoolean(scan));
                    java.lang.reflect.Field lock = scan.getClass().getDeclaredField("multicast"); lock.setAccessible(true); assertNull(lock.get(scan));
                }
            }
        } finally { worker.shutdownNow(); }
    }
    @Test public void findsRegisteredIpv4ServiceAndFiltersWrongPort() throws Exception {
        android.net.nsd.NsdManager manager = context().getSystemService(android.net.nsd.NsdManager.class);
        CompletableFuture<android.net.nsd.NsdServiceInfo> registered = new CompletableFuture<>();
        CompletableFuture<Boolean> removed = new CompletableFuture<>();
        android.net.nsd.NsdManager.RegistrationListener listener = new android.net.nsd.NsdManager.RegistrationListener() {
            public void onServiceRegistered(android.net.nsd.NsdServiceInfo info) { registered.complete(info); }
            public void onRegistrationFailed(android.net.nsd.NsdServiceInfo info, int code) { registered.completeExceptionally(new IllegalStateException("registration " + code)); }
            public void onServiceUnregistered(android.net.nsd.NsdServiceInfo info) { removed.complete(true); }
            public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo info, int code) { removed.completeExceptionally(new IllegalStateException("unregistration " + code)); }
        };
        android.net.nsd.NsdServiceInfo advertised = new android.net.nsd.NsdServiceInfo();
        advertised.setServiceType("_podjs-test._tcp.");
        advertised.setServiceName("isolated-test-" + java.util.UUID.randomUUID()); advertised.setPort(49177);
        manager.registerService(advertised, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener);
        try (PodDiscoveryServices service = new PodDiscoveryServices(context())) {
            String name = registered.get(10, TimeUnit.SECONDS).getServiceName();
            JSONObject request = options(3000).put("namePrefix", name);
            org.json.JSONArray endpoints = service.execute("discovery.mdns", request, new CancellationSignal()).getJSONArray("endpoints");
            assertTrue("Advertised IPv4 endpoint discovered", endpoints.length() > 0);
            for (int i = 0; i < endpoints.length(); i++) { JSONObject endpoint = endpoints.getJSONObject(i); assertEquals(49177, endpoint.getInt("port")); assertEquals(name, endpoint.getString("name")); assertTrue(java.net.InetAddress.getByName(endpoint.getString("address")) instanceof java.net.Inet4Address); }
            assertEquals(0, service.execute("discovery.mdns", request.put("expectedPort", 49178).put("windowMs", 1500), new CancellationSignal()).getJSONArray("endpoints").length());
        } finally { manager.unregisterService(listener); assertTrue(removed.get(5, TimeUnit.SECONDS)); }
    }
    @Test public void invalidBoundsAndClosedOwnerReject() throws Exception {
        try (PodDiscoveryServices service = new PodDiscoveryServices(context())) {
            for (Object value : new Object[]{0, 30001, 1.5, "120"}) {
                try { service.execute("discovery.mdns", options(120).put("windowMs", value), new CancellationSignal()); fail("invalid window"); }
                catch (IllegalArgumentException expected) {}
            }
            service.close();
            try { service.execute("discovery.mdns", options(120), new CancellationSignal()); fail("closed owner"); }
            catch (IllegalStateException expected) { assertEquals("Discovery closed", expected.getMessage()); }
        }
    }
}
