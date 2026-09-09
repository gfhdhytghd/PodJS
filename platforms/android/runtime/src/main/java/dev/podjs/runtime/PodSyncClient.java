package dev.podjs.runtime;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.json.JSONObject;

/** Headless Android companion SDK core for one logical app/local device.
 * All methods run on the caller's IO worker. No UI runtime, polling thread or
 * permanent socket is created. Discovery, user approval and connected transports
 * are host responsibilities. Close disconnects IO before closing durable stores.
 */
public class PodSyncClient implements AutoCloseable {
    private final Resources resources;
    private final String localId;
    private final ReentrantReadWriteLock lifetime=new ReentrantReadWriteLock();
    private volatile boolean closing;
    private boolean disposed;
    public String localDeviceId() { return localId; }
    private enum Topic { STATE, MESSAGES, FILES }
    private final java.util.concurrent.CopyOnWriteArrayList<Subscription> subscriptions=new java.util.concurrent.CopyOnWriteArrayList<>();
    private interface Operation<T> { T run() throws Exception; }
    private static final class Resources {
        final ArrayList<AutoCloseable> owned=new ArrayList<>();
        PodSyncPairingStore keys; PodSyncConnections connections;
        PodSyncStateStore state; PodSyncOutbox outbox; PodSyncInbox inbox;
        PodSyncIncomingFiles incoming; PodSyncFileRequests requests;
        PodSyncFileSnapshots snapshots; PodSyncOutgoingFiles outgoing;
        <T extends AutoCloseable> T own(T value) { owned.add(value); return value; }
        void close() throws Exception {
            Exception failure=null;
            for(int n=owned.size()-1;n>=0;n--) try { owned.get(n).close(); } catch(Exception error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
            owned.clear(); if(failure!=null) throw failure;
        }
    }
    /** Pass an application-lifetime context, not an Activity. */
    public PodSyncClient(Context context,String appId,String localId) throws Exception {
        this.localId=localId; Resources created=new Resources();
        try {
            created.keys=new PodSyncPairingStore(context,appId);
            created.connections=created.own(new PodSyncConnections(created.keys,localId));
            created.state=created.own(new PodSyncStateStore(context,appId,localId));
            created.outbox=created.own(new PodSyncOutbox(context,appId)); created.inbox=created.own(new PodSyncInbox(context,appId));
            created.incoming=created.own(new PodSyncIncomingFiles(context,appId)); created.requests=created.own(new PodSyncFileRequests(context,appId));
            created.snapshots=new PodSyncFileSnapshots(context,appId); created.outgoing=created.own(new PodSyncOutgoingFiles(created.requests,created.snapshots));
        } catch(Exception error) { try { created.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); } throw error; }
        resources=created;
    }
    private <T> T use(Operation<T> operation) throws Exception {
        lifetime.readLock().lock();
        try { if(closing) throw new IOException("Companion closed"); return operation.run(); }
        finally { lifetime.readLock().unlock(); }
    }
    private void pulse(Topic topic) { for(Subscription subscription:subscriptions) if(subscription.topic==topic) subscription.pulse(); }
    private <T> T change(Topic topic,Operation<T> operation) throws Exception {
        return use(()->{ T result=operation.run(); pulse(topic); return result; });
    }
    /** Import an out-of-band key only AFTER the local user has approved pairing.
     * This method is not discovery or a replacement for an authenticated pairing UI. */
    public void authorizeAfterUserApproval(String peer,byte[] key) throws Exception {
        if(key==null) throw new IllegalArgumentException("Missing pairing key"); byte[] copy=key.clone();
        try { use(()->{ resources.keys.importAuthorized(localId,peer,copy); return null; }); }
        finally { java.util.Arrays.fill(copy,(byte)0); }
    }
    public void revoke(String peer) throws Exception { use(()->{ resources.connections.revoke(peer); resources.outgoing.pause(); return null; }); }
    public void disconnect(String peer) throws Exception { use(()->{ resources.connections.disconnect(peer); resources.outgoing.pause(); return null; }); }
    /** Takes ownership of the connected stream, including on handshake failure.
     * Trusted host supplies the allowed channel subset after app authorization. */
    public Session open(String peer,PodSyncStream stream,boolean initiator,String[] channels) throws Exception {
        String[] approved=channels==null?null:channels.clone();
        try { return use(()->new Session(resources.connections.open(peer,stream,initiator,approved),approved)); }
        catch(Exception error) { try { stream.close(); } catch(Exception cleanup) { error.addSuppressed(cleanup); } throw error; }
    }
    public Object getState(String key) throws Exception { return use(()->resources.state.get(key)); }
    public void setState(String key,Object value) throws Exception { setStateEntry(key,value); }
    public void deleteState(String key) throws Exception { deleteStateEntry(key); }
    /** Exact entry returned by the durable mutation, not a later racing snapshot. */
    public JSONObject setStateEntry(String key,Object value) throws Exception { return change(Topic.STATE,()->resources.state.set(key,value)); }
    public JSONObject deleteStateEntry(String key) throws Exception { return change(Topic.STATE,()->resources.state.delete(key)); }
    public JSONObject stateSnapshot() throws Exception { return use(()->resources.state.snapshot()); }
    public boolean currentStateAcknowledged(String peer) throws Exception { return use(()->resources.state.currentStateAcknowledged(peer)); }
    public String sendMessage(String peer,byte[] payload,long expiresAt,boolean highPriority,long now) throws Exception {
        String id=UUID.randomUUID().toString(); queueMessage(peer,id,payload,expiresAt,highPriority,now); return id;
    }
    /** Service callers retain their own message ID across retries. Changed bytes
     * or expiry for the same ID are rejected by the existing durable outbox. */
    public void queueMessage(String peer,String messageId,byte[] payload,long expiresAt,boolean highPriority,long now) throws Exception {
        if(localId.equals(peer)) throw new IllegalArgumentException("Cannot queue a message to the local identity");
        change(Topic.MESSAGES,()->{ resources.outbox.enqueue(peer,messageId,payload,expiresAt,highPriority,now); return null; });
    }
    public long queueMessageWithTtl(String peer,String messageId,byte[] payload,long ttl,boolean highPriority,long now) throws Exception {
        if(localId.equals(peer)) throw new IllegalArgumentException("Cannot queue a message to the local identity");
        return change(Topic.MESSAGES,()->resources.outbox.enqueueWithTtl(peer,messageId,payload,ttl,highPriority,now));
    }
    public List<PodSyncOutbox.Message> pendingMessages(String peer,long now) throws Exception { return use(()->resources.outbox.pending(peer,now,100)); }
    public List<PodSyncInbox.Message> receivedMessages(long now) throws Exception { return use(()->resources.inbox.pending(now,100)); }
    List<PodSyncInbox.Message> receivedMessagePage(long now,int offset) throws Exception { return use(()->resources.inbox.pendingPage(now,100,offset)); }
    /** Offline completion: marks a receipt now; the peer receives its ACK when
     * it retries. Use Session.ackMessage for immediate authenticated delivery. */
    public void ackMessage(PodSyncInbox.Message delivery,long now) throws Exception {
        change(Topic.MESSAGES,()->{ resources.inbox.acknowledge(delivery.peerId,delivery.messageId,delivery.acknowledgementToken(),now); return null; });
    }
    PodSyncInbox.Message ackMessageToken(String peer,String messageId,byte[] token,long now) throws Exception {
        byte[] stable=token.clone();
        return change(Topic.MESSAGES,()->resources.inbox.acknowledge(peer,messageId,stable,now));
    }
    /** Bounded, coalesced wake-up subscription, not message application or ACK.
     * Runs on the caller's executor. Read receivedMessages and/or pendingMessages in the callback;
     * initial wake-up exposes persisted deliveries from an earlier process. */
    public AutoCloseable subscribeMessages(java.util.concurrent.Executor executor,Runnable onPending) throws Exception {
        return subscribe(Topic.MESSAGES,executor,onPending);
    }
    /** Coalesced notifications; read stateSnapshot for the latest durable state. */
    public AutoCloseable subscribeState(java.util.concurrent.Executor executor,Runnable onChanged) throws Exception { return subscribe(Topic.STATE,executor,onChanged); }
    /** Read incomingFiles/outgoingFiles after notification; not a consent grant. */
    public AutoCloseable subscribeFiles(java.util.concurrent.Executor executor,Runnable onChanged) throws Exception { return subscribe(Topic.FILES,executor,onChanged); }
    private AutoCloseable subscribe(Topic topic,java.util.concurrent.Executor executor,Runnable callback) throws Exception {
        java.util.Objects.requireNonNull(executor); java.util.Objects.requireNonNull(callback);
        return use(()->{
            Subscription subscription=new Subscription(topic,executor,callback);
            synchronized(subscriptions) {
                if(subscriptions.size()>=32) throw new IOException("Too many companion subscriptions");
                subscriptions.add(subscription);
            }
            subscription.pulse(); return subscription;
        });
    }
    private final class Subscription implements AutoCloseable {
        final Topic topic;
        final java.util.concurrent.Executor executor; final Runnable callback;
        final java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean active=true;
        Subscription(Topic topic,java.util.concurrent.Executor executor,Runnable callback) { this.topic=topic; this.executor=executor; this.callback=callback; }
        void pulse() {
            if(!active || closing || !queued.compareAndSet(false,true)) return;
            try { executor.execute(()->{
                queued.set(false); if(!active || closing) return;
                try { callback.run(); } catch(RuntimeException error) { android.util.Log.w("PodCompanion","Observer failed; durable data remains available"); }
            }); } catch(RuntimeException error) { queued.set(false); android.util.Log.w("PodCompanion","Observer executor rejected notification"); }
        }
        @Override public void close() { active=false; subscriptions.remove(this); }
    }
    public JSONObject snapshotFile(File source,String mime) throws Exception { return change(Topic.FILES,()->{ resources.outgoing.pause(); return resources.snapshots.create(source,mime); }); }
    JSONObject snapshotGuestFile(File root,String path,String mime) throws Exception {
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream input=PodSyncGuestFiles.open(root,path)) {
            return change(Topic.FILES,()->{ resources.outgoing.pause(); return resources.snapshots.create(input.getChannel(),mime); });
        }
    }
    public PodSyncOutgoingFiles.Status offerFile(String peer,String snapshotId) throws Exception {
        if(localId.equals(peer)) throw new IllegalArgumentException("Cannot offer file to self");
        return change(Topic.FILES,()->resources.outgoing.start(peer,snapshotId));
    }
    public List<String> sourceSnapshots() throws Exception { return use(()->{ resources.outgoing.pause(); return resources.snapshots.inventory(); }); }
    public void releaseSource(String snapshotId) throws Exception { change(Topic.FILES,()->{ resources.outgoing.releaseSnapshot(snapshotId); return null; }); }
    public List<PodSyncOutgoingFiles.Status> outgoingFiles(String peer) throws Exception { return use(()->resources.outgoing.list(peer)); }
    static final class FileIdentity {
        final String peerId,transferId; final boolean incoming;
        FileIdentity(String peer,String id,boolean incoming) { peerId=peer; transferId=id; this.incoming=incoming; }
    }
    /** Read-only identity resolution, not file consent. Terminal rows participate
     * in ambiguity checks; callers must not silently select a newer direction. */
    FileIdentity resolveFileIdentity(String id) throws Exception {
        return use(()->{
            List<PodSyncIncomingFiles.Offer> incoming=resources.incoming.findTransfer(id);
            List<PodSyncOutgoingFiles.Status> outgoing=resources.outgoing.findTransfer(id);
            int count=incoming.size()+outgoing.size();
            if(count==0) throw new IOException("Unknown file transfer");
            if(count!=1) throw new IllegalArgumentException("Ambiguous file transfer identity");
            return incoming.isEmpty()?new FileIdentity(outgoing.get(0).peerId,id,false):new FileIdentity(incoming.get(0).peerId,id,true);
        });
    }
    JSONObject fileServiceStatus(String id) throws Exception {
        FileIdentity identity=resolveFileIdentity(id);
        return use(()->{
            if(identity.incoming) return resources.incoming.serviceStatus(identity.peerId,id);
            PodSyncOutgoingFiles.Status status=resources.outgoing.status(identity.peerId,id);
            String state=status.phase.equals("complete")?"complete":status.phase.equals("cancelled")?"cancelled":
                (status.phase.equals("offer") || status.phase.equals("waiting"))?"offered":"transferring";
            return new JSONObject().put("transferId",id).put("state",state).put("receivedBytes",status.acknowledgedBytes)
                .put("totalBytes",status.totalBytes).put("progressKnown",status.progressKnown);
        });
    }
    List<String> fileEventIdsAfter(String after) throws Exception {
        return use(()->{
            java.util.TreeSet<String> ids=new java.util.TreeSet<>(resources.incoming.transferIdsAfter(after));
            ids.addAll(resources.outgoing.transferIdsAfter(after));
            ArrayList<String> result=new ArrayList<>();
            for(String id:ids) { result.add(id); if(result.size()==64) break; }
            return result;
        });
    }
    public List<PodSyncIncomingFiles.Offer> pendingFileConsent() throws Exception { return use(()->resources.incoming.pendingConsent()); }
    public List<PodSyncIncomingFiles.Offer> incomingFiles() throws Exception { return use(()->resources.incoming.list()); }
    public PodSyncIncomingFiles.Offer incomingFile(String peer,String id) throws Exception { return use(()->resources.incoming.status(peer,id)); }
    /** Local approval only; never call from a remote self-approval request. */
    public void acceptFile(String peer,String id) throws Exception { change(Topic.FILES,()->{ resources.incoming.accept(peer,id); return null; }); }
    public void cancelIncomingFile(String peer,String id) throws Exception { change(Topic.FILES,()->{ resources.incoming.cancel(peer,id); return null; }); }
    void cancelUnfinishedIncomingFile(String peer,String id) throws Exception { change(Topic.FILES,()->resources.incoming.cancelUnfinished(peer,id)); }
    public void cancelOutgoingFile(String peer,String id) throws Exception { change(Topic.FILES,()->{ resources.outgoing.cancel(peer,id); return null; }); }
    public File completedIncomingFile(String peer,String id) throws Exception { return use(()->resources.incoming.completedFile(peer,id)); }
    JSONObject saveIncomingFile(String peer,String id,File root,String path,android.os.CancellationSignal cancellation) throws Exception {
        return use(()->{
            cancellation.throwIfCanceled();
            PodSyncIncomingFiles.Offer offer=resources.incoming.status(peer,id);
            File source=resources.incoming.completedFile(peer,id);
            PodSyncGuestFiles.save(root,path,source,offer.manifest.getLong("size"),offer.manifest.getString("sha256"),cancellation);
            return new JSONObject().put("path",path).put("size",offer.manifest.getLong("size"));
        });
    }
    public void recoverFileIntents() throws Exception { change(Topic.FILES,()->{ resources.incoming.recover(); return null; }); }
    public final class Session implements AutoCloseable {
        private final PodSyncConnections.Connection connection;
        private final PodSyncChannelPump pump;
        private final java.util.Set<String> channels;
        private Session(PodSyncConnections.Connection connection,String[] approved) {
            this.connection=connection; pump=new PodSyncChannelPump(connection,resources.state,resources.outbox,resources.inbox,resources.incoming,resources.requests);
            channels=new java.util.HashSet<>(java.util.Arrays.asList(approved));
        }
        boolean permits(String channel) { return channels.contains(channel); }
        public String peerId() { return connection.peerId(); }
        boolean ownedBy(PodSyncClient client) { return PodSyncClient.this==client; }
        JSONObject acknowledgedStateReceipt() throws Exception {
            return use(()->resources.state.currentStateAcknowledged(peerId())
                ? new JSONObject().put("appliedCursor",resources.state.snapshot().getJSONObject("cursors").optLong(peerId(),0)) : null);
        }
        public boolean sendState() throws Exception { return use(pump::sendState); }
        public boolean sendMessage(long now) throws Exception { return use(()->pump.sendMessage(now)); }
        public boolean sendFile(boolean pollConsent) throws Exception {
            return use(()->{ boolean pending=resources.outgoing.advance(peerId(),pollConsent)!=null; pulse(Topic.FILES); return pending && pump.sendFile(); });
        }
        /** Handler must persist/idempotently apply business data before returning.
         * Caller observes state/file outcomes from the return value and queries the
         * stores. Do not close the whole client from inside this callback; closing
         * this Session is permitted. No implicit message acknowledgement API. */
        public String receive(long now,PodSyncMessagePump.Handler handler) throws Exception { return use(()->receiveObserved(()->pump.receiveOne(now,handler))); }
        /** Persist and expose messages without acknowledging business completion.
         * State/file channels retain their normal durable processing. */
        public String receiveDeferred(long now) throws Exception {
            return use(()->receiveObserved(()->pump.receiveOneDeferred(now)));
        }
        private String receiveObserved(Operation<String> receive) throws Exception {
            try { return observe(receive.run()); }
            catch(Exception error) {
                // Failure can occur after inbox/file persistence but before its
                // network reply. Refresh hints never assert successful delivery.
                pulse(Topic.STATE); pulse(Topic.MESSAGES); pulse(Topic.FILES); throw error;
            }
        }
        private String observe(String outcome) {
            if(outcome.equals("message.pending") || outcome.equals("message.applied") || outcome.equals("message.ack")) pulse(Topic.MESSAGES);
            else if(outcome.equals("state.applied") || outcome.equals("state.ack")) pulse(Topic.STATE);
            else if(outcome.startsWith("file.") && !outcome.equals("file.stale_reply") && !outcome.equals("file.duplicate_reply")) pulse(Topic.FILES);
            return outcome;
        }
        public void ackMessage(PodSyncInbox.Message delivery,long now) throws Exception {
            use(()->{
                try { pump.acknowledgeMessage(delivery,now); return null; }
                // The receipt may be durable even when sending its network ACK fails.
                finally { pulse(Topic.MESSAGES); }
            });
        }
        @Override public void close() throws IOException {
            try { connection.close(); }
            finally {
                lifetime.readLock().lock();
                try { if(!closing) resources.outgoing.pause(); }
                finally { lifetime.readLock().unlock(); }
            }
        }
    }
    @Override public void close() throws Exception {
        if(lifetime.getReadHoldCount()!=0) throw new IOException("Close companion outside its receive callback");
        closing=true;
        // Break blocked receive/handshake before waiting for store operations.
        Exception failure=null;
        try { resources.connections.close(); } catch(Exception error) { failure=error; }
        lifetime.writeLock().lock();
        try {
            if(!disposed) { disposed=true; for(Subscription subscription:subscriptions) subscription.close();
                try { resources.close(); } catch(Exception error) { if(failure==null) failure=error; else failure.addSuppressed(error); } }
        } finally { lifetime.writeLock().unlock(); }
        if(failure!=null) throw failure;
    }
}
