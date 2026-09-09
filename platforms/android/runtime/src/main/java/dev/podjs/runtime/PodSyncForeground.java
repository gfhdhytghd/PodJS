package dev.podjs.runtime;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded foreground sync run over an already authenticated Session.
 * Owns that Session, not its SDK client. Host MUST close on leaving foreground.
 * Two IO workers and one timer stop together; one-second consent polling is
 * limited to this run. No reconnect,
 * discovery, foreground service or automatic business-message ACK is created.
 */
public class PodSyncForeground implements AutoCloseable {
    public interface Listener { void stopped(String reason); }
    private final Object gate=new Object();
    private final PodSyncClient.Session session;
    private final Executor events;
    private final Listener listener;
    private final ExecutorService receiveWorker,sendWorker;
    private final ScheduledExecutorService deadline;
    private final boolean[] wanted=new boolean[3], inFlight=new boolean[3];
    private final ArrayDeque<PodSyncInbox.Message> acknowledgements=new ArrayDeque<>();
    private boolean closed,writerScheduled,pollConsent;
    private int turn;
    private String reason;
    private Exception failure;
    private static java.util.concurrent.ThreadFactory threads(String name) {
        return task->{ Thread thread=new Thread(task,name); thread.setDaemon(true); return thread; };
    }
    /** durationMillis must be 100..120000. Use the app's main executor for the
     * terminal callback; only one terminal notification is submitted per run. */
    public PodSyncForeground(PodSyncClient.Session session,long durationMillis,Executor events,Listener listener) {
        if(durationMillis<100 || durationMillis>120000) throw new IllegalArgumentException("Foreground sync duration out of range");
        this.session=java.util.Objects.requireNonNull(session); this.events=java.util.Objects.requireNonNull(events); this.listener=java.util.Objects.requireNonNull(listener);
        receiveWorker=Executors.newSingleThreadExecutor(threads("podjs-sync-receive"));
        sendWorker=Executors.newSingleThreadExecutor(threads("podjs-sync-send"));
        deadline=Executors.newSingleThreadScheduledExecutor(threads("podjs-sync-deadline"));
        synchronized(gate) {
            wanted[0]=session.permits("state"); wanted[1]=session.permits("message"); wanted[2]=session.permits("file");
            receiveWorker.execute(this::receiveLoop); scheduleWriter();
            deadline.schedule(()->stop("deadline",null),durationMillis,TimeUnit.MILLISECONDS);
            // A peer can approve an offer after its initial "offered" reply.
            // Poll only inside this explicitly bounded, existing foreground run.
            if(session.permits("file")) deadline.scheduleWithFixedDelay(()->{
                synchronized(gate) {
                    if(closed) return;
                    wanted[2]=true; pollConsent=true; scheduleWriter();
                }
            },1000,1000,TimeUnit.MILLISECONDS);
        }
    }
    public void requestState() throws IOException { request(0,false); }
    public String peerId() { return session.peerId(); }
    boolean ownedBy(PodSyncClient client) { return session.ownedBy(client); }
    /** Blocking host-IO barrier for this authenticated peer. Success means the
     * current local state was durably acknowledged at the observation point.
     * appliedCursor is the locally received peer cursor, not proof that all
     * future remote writes have arrived. Cancellation leaves the shared run alive. */
    public org.json.JSONObject synchronizeState(long timeoutMillis,android.os.CancellationSignal cancellation) throws Exception {
        if(timeoutMillis<1 || timeoutMillis>120000) throw new IllegalArgumentException("Invalid sync timeout");
        java.util.Objects.requireNonNull(cancellation).throwIfCanceled();
        long end=android.os.SystemClock.elapsedRealtime()+timeoutMillis;
        requestState();
        synchronized(gate) {
            while(true) {
                cancellation.throwIfCanceled();
                if(closed) throw new IOException("Foreground sync stopped",failure);
                org.json.JSONObject receipt=session.acknowledgedStateReceipt();
                if(receipt!=null) { cancellation.throwIfCanceled(); return receipt; }
                long remaining=end-android.os.SystemClock.elapsedRealtime();
                if(remaining<=0) throw new java.net.SocketTimeoutException("State acknowledgement timed out");
                gate.wait(Math.min(remaining,100));
            }
        }
    }
    public void requestMessages() throws IOException { request(1,false); }
    public void requestFiles(boolean pollConsent) throws IOException { request(2,pollConsent); }
    private void request(int channel,boolean poll) throws IOException {
        if(!session.permits(new String[]{"state","message","file"}[channel])) throw new IOException("Channel not approved");
        synchronized(gate) { if(closed) throw new IOException("Foreground sync stopped"); wanted[channel]=true; pollConsent|=poll; scheduleWriter(); }
    }
    /** Application explicitly completed this delivery; no receive callback return
     * can silently mark it applied. The queue is bounded and duplicate IDs coalesce. */
    public void ackMessage(PodSyncInbox.Message delivery) throws IOException {
        if(delivery==null || !session.peerId().equals(delivery.peerId)) throw new IOException("Wrong acknowledgement peer");
        synchronized(gate) {
            if(closed) throw new IOException("Foreground sync stopped");
            for(PodSyncInbox.Message pending:acknowledgements) if(pending.messageId.equals(delivery.messageId)) return;
            if(acknowledgements.size()>=32) throw new IOException("Acknowledgement queue full");
            acknowledgements.add(delivery); scheduleWriter();
        }
    }
    private boolean eligible() {
        if(!acknowledgements.isEmpty()) return true;
        for(int n=0;n<3;n++) if(wanted[n] && !inFlight[n]) return true; return false;
    }
    private void scheduleWriter() {
        if(closed || writerScheduled || !eligible()) return;
        writerScheduled=true; sendWorker.execute(this::sendLoop);
    }
    private void sendLoop() {
        try {
            while(true) {
                int channel=-1; boolean poll=false; PodSyncInbox.Message ack=null;
                synchronized(gate) {
                    if(closed || !eligible()) { writerScheduled=false; return; }
                    for(int offset=0;offset<4;offset++) {
                        int candidate=(turn+offset)%4;
                        if(candidate==3 ? !acknowledgements.isEmpty() : wanted[candidate] && !inFlight[candidate]) { channel=candidate; turn=(candidate+1)%4; break; }
                    }
                    if(channel==3) ack=acknowledgements.removeFirst();
                    else { wanted[channel]=false; inFlight[channel]=true; if(channel==2) { poll=pollConsent; pollConsent=false; } }
                }
                boolean sent=true;
                if(channel==0) sent=session.sendState();
                else if(channel==1) sent=session.sendMessage(System.currentTimeMillis());
                else if(channel==2) sent=session.sendFile(poll);
                else session.ackMessage(ack,System.currentTimeMillis());
                // Flight is reserved BEFORE send: a fast ACK can clear it before
                // send returns. Never overwrite that clear after a successful send.
                if(channel<3 && !sent) synchronized(gate) { inFlight[channel]=false; }
            }
        } catch(Exception error) { stop("failed",error); }
    }
    private void receiveLoop() {
        try {
            while(true) {
                synchronized(gate) { if(closed) return; }
                String result=session.receiveDeferred(System.currentTimeMillis());
                synchronized(gate) {
                    if(closed) return;
                    if(result.equals("state.ack")) { inFlight[0]=false; wanted[0]=true; }
                    else if(result.equals("state.applied")) wanted[0]=true;
                    else if(result.equals("message.ack")) { inFlight[1]=false; wanted[1]=true; }
                    else if(result.equals("file.reply")) { inFlight[2]=false; wanted[2]=true; }
                    scheduleWriter();
                    gate.notifyAll();
                }
            }
        } catch(Exception error) { stop("failed",error); }
    }
    public boolean isStopped() { synchronized(gate) { return closed; } }
    public String stopReason() { synchronized(gate) { return reason; } }
    public Exception failure() { synchronized(gate) { return failure; } }
    private void stop(String reason,Exception error) {
        synchronized(gate) { if(closed) return; closed=true; this.reason=reason; failure=error; acknowledgements.clear(); gate.notifyAll(); }
        try { session.close(); } catch(Exception closing) { synchronized(gate) { if(failure==null) failure=closing; else failure.addSuppressed(closing); } }
        receiveWorker.shutdownNow(); sendWorker.shutdownNow(); deadline.shutdownNow();
        try { events.execute(()->{ try { listener.stopped(reason); } catch(RuntimeException ignored) { android.util.Log.w("PodCompanion","Sync stop observer failed"); } }); }
        catch(RuntimeException ignored) { android.util.Log.w("PodCompanion","Sync stop executor rejected notification"); }
    }
    @Override public void close() { stop("closed",null); }
}
