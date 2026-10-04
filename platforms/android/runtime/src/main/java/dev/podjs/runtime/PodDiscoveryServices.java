package dev.podjs.runtime;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.io.Closeable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded guest-owned discovery. Service identity and authentication remain in the guest. */
final class PodDiscoveryServices implements Closeable {
    private final Context context;
    private final Set<Scan> scans = new HashSet<>();
    private boolean closed;
    PodDiscoveryServices(Context context) { this.context = context; }

    JSONObject execute(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        if (!"discovery.mdns".equals(method)) throw new IllegalArgumentException("Unknown discovery method");
        String type = args.getString("serviceType"), prefix = args.optString("namePrefix", "");
        if (!type.matches("_[A-Za-z0-9-]{1,63}\\._(?:tcp|udp)\\.?") || prefix.length() > 255)
            throw new IllegalArgumentException("Invalid discovery service filter");
        int port = bounded(args, "expectedPort", 1, 65535), window = bounded(args, "windowMs", 1, 30000);
        NsdManager manager = context.getSystemService(NsdManager.class);
        if (manager == null) throw new IllegalStateException("NSD unavailable");
        Scan scan = new Scan(manager, prefix, port);
        synchronized (this) {
            if (closed) throw new IllegalStateException("Discovery closed");
            if (scans.size() >= 4) throw new IllegalStateException("Too many discovery requests");
            scans.add(scan);
        }
        cancellation.setOnCancelListener(scan::close);
        try {
            cancellation.throwIfCanceled();
            scan.start(type);
            long deadline = SystemClock.elapsedRealtime() + window;
            synchronized (scan) {
                while (!scan.ended && scan.failure == null) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    scan.wait(remaining);
                }
                cancellation.throwIfCanceled();
                if (scan.failure != null) throw scan.failure;
                if (scan.ended) throw new IllegalStateException("Discovery closed");
                return new JSONObject().put("endpoints", new JSONArray(scan.results.values()));
            }
        } finally {
            scan.close();
            cancellation.setOnCancelListener(null);
            synchronized (this) { scans.remove(scan); }
        }
    }
    static int bounded(JSONObject args, String key, int min, int max) throws Exception {
        Object value = args.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid " + key);
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max)
            throw new IllegalArgumentException("Invalid " + key);
        return (int) n;
    }
    public void close() {
        List<Scan> pending;
        synchronized (this) { closed = true; pending = new ArrayList<>(scans); scans.clear(); }
        for (Scan scan : pending) scan.close();
    }
    @SuppressWarnings("deprecation")
    private final class Scan implements NsdManager.DiscoveryListener, Closeable {
        final NsdManager manager;
        final String prefix;
        final int port;
        final Map<String, JSONObject> results = new LinkedHashMap<>();
        final Set<String> resolving = new HashSet<>();
        WifiManager.MulticastLock multicast;
        boolean started, ended;
        RuntimeException failure;
        Scan(NsdManager manager, String prefix, int port) { this.manager = manager; this.prefix = prefix; this.port = port; }
        synchronized void start(String type) {
            if (ended) throw new IllegalStateException("Discovery closed");
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("PodJS-discovery");
                multicast.setReferenceCounted(false);
                try { multicast.acquire(); } catch (RuntimeException ignored) { multicast = null; }
            }
            // Serialize start and cancellation so stop cannot run before registration.
            manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, this);
            started = true;
        }
        public synchronized void onDiscoveryStarted(String type) {}
        public synchronized void onDiscoveryStopped(String type) { ended = true; notifyAll(); }
        public synchronized void onStartDiscoveryFailed(String type, int code) { failure = new IllegalStateException("NSD start failed: " + code); notifyAll(); }
        public synchronized void onStopDiscoveryFailed(String type, int code) {}
        public synchronized void onServiceLost(NsdServiceInfo info) {}
        public synchronized void onServiceFound(NsdServiceInfo info) {
            if (ended || !info.getServiceName().startsWith(prefix) || resolving.size() >= 64 || !resolving.add(info.getServiceName())) return;
            try {
                manager.resolveService(info, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo ignored, int code) {}
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        synchronized (Scan.this) {
                            if (ended || resolved.getPort() != port) return;
                            List<InetAddress> addresses = android.os.Build.VERSION.SDK_INT >= 34 ? resolved.getHostAddresses() : Collections.singletonList(resolved.getHost());
                            for (InetAddress address : addresses) if (address instanceof Inet4Address) {
                                try {
                                    String host = address.getHostAddress();
                                    results.put(host, new JSONObject().put("address", host).put("port", port).put("name", resolved.getServiceName()));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                });
            } catch (RuntimeException ignored) {}
        }
        public synchronized void close() {
            if (ended && !started && multicast == null) return;
            ended = true;
            if (started) { started = false; try { manager.stopServiceDiscovery(this); } catch (RuntimeException ignored) {} }
            if (multicast != null) { try { if (multicast.isHeld()) multicast.release(); } catch (RuntimeException ignored) {} multicast = null; }
            notifyAll();
        }
    }
}
