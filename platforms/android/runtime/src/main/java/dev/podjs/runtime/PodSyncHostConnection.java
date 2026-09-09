package dev.podjs.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Host-selected route; no discovery, pairing grant, reconnect or background run. */
public final class PodSyncHostConnection implements AutoCloseable {
    public interface Listener { void changed(String phase,String detail); }
    private final Object gate=new Object();
    private final PodSyncClient client;
    private final PodSyncServices services;
    private final Executor events;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup=Executors.newSingleThreadExecutor();
    private interface Route {
        PodSyncClient.Session open(long token,Listener listener) throws Exception;
        void close();
    }
    private interface RouteFactory { Route create(); }
    private Route attempt;
    private PodSyncForeground run;
    private boolean foreground,disposed,busy;
    private long generation;
    PodSyncHostConnection(PodSyncClient client,PodSyncServices services,Executor events) {
        this.client=client; this.services=services; this.events=events;
    }
    void setForeground(boolean active) { synchronized(gate) { foreground=active; if(!active) stopLocked(); } }
    void start(String peer,InetSocketAddress address,boolean listen,Listener listener) throws Exception {
        if(address==null || address.isUnresolved() || (!listen && (address.getPort()==0 || address.getAddress().isAnyLocalAddress()))) throw new IllegalArgumentException("Invalid LAN route");
        startRoute(peer,listener,()->{
            PodSyncLanAttempt pending=new PodSyncLanAttempt(client,peer,services.connectionChannels(),30000);
            return new Route() {
                public PodSyncClient.Session open(long token,Listener listener) throws Exception {
                    if(!listen) return pending.connect(address);
                    InetSocketAddress bound=pending.listen(address);
                    publish(token,listener,"listening",bound.toString()); return pending.accept();
                }
                public void close() { pending.close(); }
            };
        });
    }
    void startBle(android.content.Context context,String peer,android.bluetooth.BluetoothDevice selected,boolean listen,Listener listener) throws Exception {
        java.util.Objects.requireNonNull(context);
        if(!listen) java.util.Objects.requireNonNull(selected);
        startRoute(peer,listener,()->{
            PodBlePermissions.require(context,false,listen);
            PodSyncBleAttempt pending=new PodSyncBleAttempt(client,peer,services.connectionChannels(),30000);
            return new Route() {
                public PodSyncClient.Session open(long token,Listener listener) throws Exception {
                    if(!listen) return pending.connect(context,selected);
                    return selected==null ? pending.accept(context) : pending.accept(context,selected);
                }
                public void close() { pending.close(); }
            };
        });
    }
    private void startRoute(String peer,Listener listener,RouteFactory factory) throws Exception {
        java.util.Objects.requireNonNull(listener);
        synchronized(gate) {
            if(disposed || !foreground) throw new IOException("Host is not foreground");
            if(busy || attempt!=null || run!=null) throw new IOException("Host connection busy");
            Route pending=factory.create();
            long token=++generation; attempt=pending; busy=true;
            network.execute(()->{
                PodSyncClient.Session session=null;
                try {
                    session=pending.open(token,listener);
                    synchronized(gate) {
                        if(disposed || !foreground || token!=generation) return;
                        PodSyncForeground connected=new PodSyncForeground(session,120000,events,reason->ended(token,listener,reason)); session=null;
                        try { services.attachForeground(connected); }
                        catch(Exception error) { connected.close(); throw error; }
                        run=connected; attempt=null;
                    }
                    publish(token,listener,"connected",peer);
                } catch(Exception error) { publish(token,listener,"failed","Connection or authentication failed"); }
                finally {
                    if(session!=null) try { session.close(); } catch(Exception ignored) { }
                    pending.close(); synchronized(gate) { if(attempt==pending) attempt=null; busy=false; }
                }
            });
        }
    }
    private void publish(long token,Listener listener,String phase,String detail) {
        events.execute(()->{ synchronized(gate) { if(disposed || token!=generation) return; } listener.changed(phase,detail); });
    }
    private void ended(long token,Listener listener,String reason) {
        synchronized(gate) {
            if(disposed || token!=generation) return;
            if(run!=null) services.detachForeground(run); run=null;
        }
        publish(token,listener,"stopped",reason);
    }
    private void stopLocked() {
        ++generation; Route pending=attempt; PodSyncForeground active=run; attempt=null; run=null;
        if(active!=null) services.detachForeground(active);
        if(pending!=null || active!=null) cleanup.execute(()->{ if(pending!=null) pending.close(); if(active!=null) active.close(); });
    }
    void disconnect() { synchronized(gate) { if(!disposed) stopLocked(); } }
    @Override public void close() {
        synchronized(gate) { if(disposed)return; stopLocked(); disposed=true; network.shutdownNow(); cleanup.shutdown(); }
    }
}
