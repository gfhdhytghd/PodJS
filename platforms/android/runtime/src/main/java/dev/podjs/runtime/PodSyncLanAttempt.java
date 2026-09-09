package dev.podjs.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One cancellable LAN attempt, not pairing/discovery or a persistent listener.
 * All blocking methods belong on the host's IO worker. A single absolute deadline
 * covers connect/accept AND handshake, including a peer that slowly drips bytes.
 * Successful return transfers the authenticated Session to the caller.
 */
public class PodSyncLanAttempt implements AutoCloseable {
    private final Object gate=new Object();
    private final PodSyncClient client;
    private final String peer;
    private final String[] channels;
    private final int timeoutMillis;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{ Thread thread=new Thread(task,"podjs-lan-deadline"); thread.setDaemon(true); return thread; });
    private ServerSocket listener;
    private Socket socket;
    private boolean started,accepting,closed,transferred,timedOut;
    public PodSyncLanAttempt(PodSyncClient client,String peer,String[] channels,int timeoutMillis) {
        this.client=java.util.Objects.requireNonNull(client);
        if(peer==null || !peer.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid peer identity");
        if(timeoutMillis<100 || timeoutMillis>30000) throw new IllegalArgumentException("LAN attempt timeout out of range");
        if(channels==null || channels.length==0 || channels.length>4) throw new IllegalArgumentException("Invalid channel grants");
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(String channel:channels) if(!java.util.Arrays.asList("state","message","file","ack").contains(channel) || !seen.add(channel)) throw new IllegalArgumentException("Invalid channel grant");
        this.peer=peer; this.channels=channels.clone(); this.timeoutMillis=timeoutMillis;
    }
    private static void address(InetSocketAddress value) {
        if(value==null || value.isUnresolved()) throw new IllegalArgumentException("Resolve the LAN address on an IO worker first");
    }
    private void begin() throws IOException {
        if(started || closed) throw new IOException("LAN attempt already used or closed"); started=true;
        timer.schedule(()->{
            synchronized(gate) { if(closed || transferred) return; timedOut=true; }
            close();
        },timeoutMillis,TimeUnit.MILLISECONDS);
    }
    public PodSyncClient.Session connect(InetSocketAddress remote) throws Exception {
        address(remote); if(remote.getPort()==0) throw new IllegalArgumentException("Remote port required");
        Socket pending;
        synchronized(gate) { begin(); socket=new Socket(); pending=socket; }
        try { pending.connect(remote,timeoutMillis); }
        catch(Exception error) { throw failed(error); }
        return authenticate(pending,true);
    }
    /** Bind a single-use listener, returning its actual local address/port.
     * Its deadline starts here, not when accept is eventually called. */
    public InetSocketAddress listen(InetSocketAddress local) throws Exception {
        address(local); ServerSocket pending;
        synchronized(gate) {
            if(started || closed) throw new IOException("LAN attempt already used or closed");
            listener=new ServerSocket(); begin(); pending=listener;
        }
        try {
            pending.bind(local,1); pending.setSoTimeout(timeoutMillis);
            synchronized(gate) {
                if(closed || timedOut) throw new IOException("LAN attempt closed before listen completed");
                return (InetSocketAddress)pending.getLocalSocketAddress();
            }
        }
        catch(Exception error) { throw failed(error); }
    }
    public PodSyncClient.Session accept() throws Exception {
        ServerSocket waiting;
        synchronized(gate) {
            if(closed || listener==null || accepting) throw new IOException("LAN listener unavailable"); accepting=true; waiting=listener;
        }
        Socket accepted=null;
        try {
            accepted=waiting.accept();
            synchronized(gate) {
                if(closed) throw new IOException("LAN attempt closed");
                socket=accepted; listener=null;
            }
            waiting.close();
        } catch(Exception error) {
            if(accepted!=null) try { accepted.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            throw failed(error);
        }
        return authenticate(accepted,false);
    }
    private PodSyncClient.Session authenticate(Socket connected,boolean initiator) throws Exception {
        PodSyncClient.Session session=null;
        try {
            connected.setSoTimeout(timeoutMillis);
            session=client.open(peer,new PodSyncStream(connected),initiator,channels);
            connected.setSoTimeout(0); // Authenticated lifetime belongs to foreground driver.
            synchronized(gate) {
                if(closed || timedOut) throw new IOException("LAN attempt closed before handoff");
                transferred=true; socket=null; timer.shutdownNow();
            }
            return session;
        } catch(Exception error) {
            if(session!=null) try { session.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            throw failed(error);
        }
    }
    private IOException failed(Exception error) {
        boolean expired; synchronized(gate) { expired=timedOut; }
        close(); return new IOException(expired?"LAN connection deadline exceeded":"LAN connection attempt failed",error);
    }
    public boolean isClosed() { synchronized(gate) { return closed; } }
    @Override public void close() {
        Socket pending; ServerSocket waiting;
        synchronized(gate) { if(closed) return; closed=true; pending=socket; waiting=listener; socket=null; listener=null; }
        // Never hold the state lock across blocking network IO or a close callback.
        if(pending!=null) try { pending.close(); } catch(IOException ignored) { }
        if(waiting!=null) try { waiting.close(); } catch(IOException ignored) { }
        timer.shutdownNow();
    }
}
