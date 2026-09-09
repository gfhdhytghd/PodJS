package dev.podjs.runtime;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import dev.podjs.runtime.PodBleGattClient;
import dev.podjs.runtime.PodBleGattServer;
import dev.podjs.runtime.PodBleStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One explicit BLE connection plus authenticated SDK handoff. No discovery,
 * first pairing, permission prompting, background lifetime or automatic retry.
 */
public class PodSyncBleAttempt implements AutoCloseable {
    interface Link extends Closeable { PodBleStream open() throws IOException; }
    private final Object gate=new Object();
    private final PodSyncClient sdk;
    private final String peer;
    private final String[] channels;
    private final int timeoutMillis;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread=new Thread(task,"podjs-ble-deadline"); thread.setDaemon(true); return thread;
    });
    private Link link;
    private boolean started,closed,transferred,timedOut;
    private long deadline;
    public PodSyncBleAttempt(PodSyncClient sdk,String peer,String[] channels,int timeoutMillis) {
        this.sdk=java.util.Objects.requireNonNull(sdk);
        if(peer==null || !peer.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid peer identity");
        if(timeoutMillis<100 || timeoutMillis>30000) throw new IllegalArgumentException("BLE attempt timeout out of range");
        if(channels==null || channels.length==0 || channels.length>4) throw new IllegalArgumentException("Invalid channel grants");
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(String channel:channels) if(!java.util.Arrays.asList("state","message","file","ack").contains(channel) || !seen.add(channel)) throw new IllegalArgumentException("Invalid channel grant");
        this.peer=peer; this.channels=channels.clone(); this.timeoutMillis=timeoutMillis;
    }
    public PodSyncClient.Session connect(Context context,BluetoothDevice selectedPeripheral) throws IOException {
        PodBleGattClient client=new PodBleGattClient(context,selectedPeripheral,timeoutMillis);
        return open(new Link() { public PodBleStream open() throws IOException { return client.connect(); } public void close() { client.close(); } },true);
    }
    public PodSyncClient.Session accept(Context context,BluetoothDevice selectedCentral) throws IOException {
        PodBleGattServer server=new PodBleGattServer(context,selectedCentral,timeoutMillis);
        return open(new Link() { public PodBleStream open() throws IOException { return server.accept(); } public void close() { server.close(); } },false);
    }
    /** The first radio is a routing candidate, never an application identity.
     * sdk.open still authenticates only the constructor's already-approved peer. */
    public PodSyncClient.Session accept(Context context) throws IOException {
        PodBleGattServer server=new PodBleGattServer(context,timeoutMillis);
        return open(new Link() { public PodBleStream open() throws IOException { return server.accept(); } public void close() { server.close(); } },false);
    }
    PodSyncClient.Session open(Link candidate,boolean initiator) throws IOException {
        boolean reject;
        synchronized(gate) {
            reject=started || closed;
            if(!reject) {
                started=true; link=candidate; deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                timer.schedule(() -> terminate(true),timeoutMillis,TimeUnit.MILLISECONDS);
            }
        }
        if(reject) { try { candidate.close(); } catch(Exception ignored) { } throw new IOException("BLE attempt already used or closed"); }
        PodBleStream stream=null;
        PodSyncClient.Session session=null;
        try {
            stream=candidate.open();
            synchronized(gate) { if(closed) throw new IOException("BLE attempt cancelled before authentication"); }
            session=sdk.open(peer,stream.framed(),initiator,channels);
            synchronized(gate) {
                if(System.nanoTime()>=deadline) { timedOut=true; throw new IOException("BLE deadline before handoff"); }
                if(closed) throw new IOException("BLE attempt cancelled before handoff");
                transferred=true; link=null;
            }
            timer.shutdownNow(); return session;
        } catch(Exception error) {
            if(session!=null) try { session.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            else if(stream!=null) try { stream.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            close();
            boolean expired; synchronized(gate) { expired=timedOut; }
            throw new IOException(expired?"BLE connection/authentication deadline exceeded":"BLE connection attempt failed",error);
        }
    }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    private void terminate(boolean expired) {
        Link pending;
        synchronized(gate) {
            if(closed || (expired && transferred)) return;
            closed=true; timedOut|=expired; pending=link; link=null;
        }
        timer.shutdownNow();
        if(pending!=null) try { pending.close(); } catch(Exception ignored) { }
    }
    @Override public void close() { terminate(false); }
}
