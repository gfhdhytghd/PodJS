package dev.podjs.runtime;

import android.os.CancellationSignal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Guest service adapter over a host-owned, app-scoped channel owner. Construct
 * only after native package approval. The guest cannot choose the owner, grants,
 * private storage root or an authenticated connection through service arguments.
 * Does not own/close the shared client or advertise target capabilities. */
final class PodSyncServices {
    /** Host supplies actual connection/file operations, including durable
     * synchronize completion. Returning a queued wake is not a sync result. */
    interface LiveOperations {
        Object execute(String method, JSONObject args, CancellationSignal cancellation) throws Exception;
    }
    private final PodSyncClient client;
    private final Set<String> grants;
    private final LiveOperations live;
    private final java.io.File guestFiles;
    private static final class Receipt {
        final byte[] token; final long expiresAt;
        Receipt(PodSyncInbox.Message message) throws Exception { token=message.acknowledgementToken(); expiresAt=message.expiresAt; }
    }
    private final java.util.Map<String, Receipt> deliveries = new java.util.HashMap<>();
    private int messageOffset;
    private final java.util.Map<String,String> emittedState=new java.util.HashMap<>();
    private int stateEventOffset;
    JSONArray stateEvents() throws Exception {
        JSONArray events=new JSONArray();
        if(!grants.contains("companion.sync.state")) return events;
        JSONArray entries=client.stateSnapshot().getJSONArray("entries");
        java.util.HashSet<String> present=new java.util.HashSet<>();
        int start=entries.length()==0?0:stateEventOffset%entries.length();
        for(int i=0;i<entries.length();i++) {
            int index=(start+i)%entries.length();
            JSONObject entry=entries.getJSONObject(index); String key=entry.getString("key"); present.add(key);
            String fingerprint=android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(PodSyncStateStore.encodeServiceJson(entry)),android.util.Base64.NO_WRAP);
            if(events.length()<64 && !fingerprint.equals(emittedState.get(key))) {
                events.put(new JSONObject().put("t","sync.state.changed").put("value",entry)); emittedState.put(key,fingerprint);
                stateEventOffset=(index+1)%entries.length();
            }
        }
        emittedState.keySet().retainAll(present);
        return events;
    }
    private void wakeState() {
        java.util.List<PodSyncForeground> runs;
        synchronized(this) { runs=new java.util.ArrayList<>(foregrounds.values()); }
        for(PodSyncForeground run:runs) try { run.requestState(); } catch(java.io.IOException stopped) { /* Local state remains durable. */ }
    }
    private final java.util.Map<String,PodSyncClient.FileIdentity> exposedFiles=new java.util.LinkedHashMap<>();
    private String fileEventCursor="";
    JSONArray fileEvents() throws Exception {
        JSONArray events=new JSONArray();
        if(!grants.contains("companion.sync.file")) return events;
        java.util.List<String> ids=client.fileEventIdsAfter(fileEventCursor);
        fileEventCursor=ids.size()<64?"":ids.get(ids.size()-1);
        for(String id:ids) {
            try {
                JSONObject status=client.fileServiceStatus(id); exposeFile(id);
                events.put(new JSONObject().put("t","sync.file.changed").put("value",status));
            } catch(java.io.IOException | IllegalArgumentException unavailable) { /* Ambiguous or temporarily unavailable rows are never auto-accepted. */ }
        }
        return events;
    }
    private synchronized void exposeFile(String id) throws Exception {
        PodSyncClient.FileIdentity identity=client.resolveFileIdentity(id);
        if(!exposedFiles.containsKey(id) && exposedFiles.size()>=256) exposedFiles.remove(exposedFiles.keySet().iterator().next());
        exposedFiles.remove(id);
        exposedFiles.put(id,identity);
    }
    private synchronized PodSyncClient.FileIdentity exposedFile(String id) throws Exception {
        PodSyncClient.FileIdentity prior=exposedFiles.get(id);
        if(prior==null) throw new IllegalArgumentException("File transfer not exposed to this guest");
        PodSyncClient.FileIdentity current=client.resolveFileIdentity(id);
        if(prior.incoming!=current.incoming || !prior.peerId.equals(current.peerId)) throw new SecurityException("File transfer identity changed");
        return prior;
    }
    private static String transferId(JSONObject args) {
        Object id=args.opt("transferId");
        if(!(id instanceof String) || !((String)id).matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid transfer identity");
        return (String)id;
    }
    private final java.util.Map<String,PodSyncForeground> foregrounds=new java.util.HashMap<>();
    String[] connectionChannels() {
        java.util.ArrayList<String> channels=new java.util.ArrayList<>();
        if(grants.contains("companion.sync.state")) channels.add("state");
        if(grants.contains("companion.sync.message")) channels.add("message");
        if(grants.contains("companion.sync.file")) channels.add("file");
        if(channels.isEmpty()) throw new SecurityException("No approved sync channels");
        channels.add("ack"); return channels.toArray(new String[0]);
    }
    /** Borrow only a host-selected, already authenticated run from this owner.
     * This adapter never creates connections or changes peer authorization. */
    synchronized void attachForeground(PodSyncForeground run) {
        if(run==null || !run.ownedBy(client)) throw new SecurityException("Foreign sync owner");
        if(run.isStopped()) throw new IllegalArgumentException("Stopped sync run");
        foregrounds.values().removeIf(PodSyncForeground::isStopped);
        if(!foregrounds.containsKey(run.peerId()) && foregrounds.size()>=8) throw new IllegalStateException("Too many foreground peers");
        foregrounds.put(run.peerId(),run);
    }
    synchronized void detachForeground(PodSyncForeground run) {
        if(run!=null) foregrounds.remove(run.peerId(),run);
    }
    private synchronized PodSyncForeground foreground(String peer) {
        PodSyncForeground run=foregrounds.get(peer);
        if(run!=null && run.isStopped()) { foregrounds.remove(peer); return null; }
        return run;
    }
    /** Polling deliberately redelivers unacknowledged messages, including after
     * a guest subscribes late. Invalid/non-JSON SDK messages remain pending. */
    JSONArray messageEvents(long now) throws Exception {
        JSONArray events=new JSONArray();
        if (!grants.contains("companion.sync.message")) return events;
        java.util.List<PodSyncInbox.Message> page=client.receivedMessagePage(now,messageOffset);
        messageOffset=page.size()<100 || messageOffset+100>=PodSyncOutbox.MAX_MESSAGES?0:messageOffset+100;
        for (PodSyncInbox.Message delivery:page) {
            try {
                String json=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(delivery.payload)).toString();
                Object payload;
                try (android.util.JsonReader reader=new android.util.JsonReader(new java.io.StringReader("["+json+"]"))) {
                    reader.beginArray(); payload=readJson(reader,0); reader.endArray();
                    if(reader.peek()!=android.util.JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing message data");
                }
                PodSyncStateStore.encodeServiceJson(payload);
                JSONObject message=new JSONObject().put("messageId",delivery.messageId).put("payload",payload)
                    .put("ttlMs",delivery.expiresAt-now).put("priority",delivery.highPriority?"high":"normal");
                JSONObject event=new JSONObject().put("t","sync.message.received").put("value",
                    new JSONObject().put("peerId",delivery.peerId).put("message",message));
                exposeMessage(delivery,now); events.put(event);
            } catch (Exception invalid) {
                // No business ACK for payloads this JSON guest cannot consume.
            }
        }
        return events;
    }
    private static Object readJson(android.util.JsonReader reader,int depth) throws Exception {
        if(depth>32) throw new IllegalArgumentException("Message JSON too deep");
        switch(reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject value=new JSONObject(); reader.beginObject();
                while(reader.hasNext()) { String name=reader.nextName(); if(value.has(name)) throw new IllegalArgumentException("Duplicate JSON key"); value.put(name,readJson(reader,depth+1)); }
                reader.endObject(); return value;
            }
            case BEGIN_ARRAY: {
                JSONArray value=new JSONArray(); reader.beginArray(); while(reader.hasNext()) value.put(readJson(reader,depth+1)); reader.endArray(); return value;
            }
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            case NUMBER: {
                String number=reader.nextString();
                try { return Long.parseLong(number); } catch(NumberFormatException fractional) { return Double.parseDouble(number); }
            }
            default: throw new IllegalArgumentException("Invalid message JSON");
        }
    }
    /** Host records the exact authenticated delivery exposed to this guest.
     * IDs alone must not acknowledge an unseen or later reused inbox entry. */
    synchronized void exposeMessage(PodSyncInbox.Message delivery, long now) throws Exception {
        if (delivery.expiresAt <= now) throw new IllegalArgumentException("Expired delivery");
        deliveries.values().removeIf(value -> value.expiresAt <= now);
        String key = delivery.peerId + "\n" + delivery.messageId;
        if (!deliveries.containsKey(key) && deliveries.size() >= PodSyncOutbox.MAX_MESSAGES)
            throw new IllegalStateException("Guest delivery limit reached");
        deliveries.put(key, new Receipt(delivery));
    }
    PodSyncServices(PodSyncClient client, Set<String> approvedGrants, LiveOperations live) {
        this(client,approvedGrants,live,null);
    }
    PodSyncServices(PodSyncClient client, Set<String> approvedGrants, LiveOperations live,java.io.File approvedGuestFiles) {
        if (client == null || approvedGrants == null) throw new IllegalArgumentException("Missing approved sync owner");
        this.client = client; this.grants = Collections.unmodifiableSet(new HashSet<>(approvedGrants)); this.live = live;
        this.guestFiles=approvedGuestFiles;
    }
    private static String capability(String method) {
        switch (method) {
            case "sync.state.get": case "sync.state.set": case "sync.state.delete": case "sync.state.synchronize": return "companion.sync.state";
            case "sync.messages.send": case "sync.messages.ack": return "companion.sync.message";
            case "sync.files.offer": case "sync.files.accept": case "sync.files.cancel": case "sync.files.status": case "sync.files.save": return "companion.sync.file";
            default: throw new UnsupportedOperationException("Unknown sync service");
        }
    }
    private static String key(JSONObject args) throws Exception {
        Object value = args.opt("key");
        if (!(value instanceof String) || !((String)value).matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid state key");
        return (String)value;
    }
    Object execute(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        String required = capability(method);
        if (!grants.contains(required)) throw new SecurityException("Sync capability not approved");
        cancellation.throwIfCanceled();
        switch (method) {
            case "sync.state.get": {
                String key = key(args); JSONArray entries = client.stateSnapshot().getJSONArray("entries");
                cancellation.throwIfCanceled();
                for (int n = 0; n < entries.length(); n++) {
                    JSONObject entry = entries.getJSONObject(n);
                    if (key.equals(entry.getString("key"))) return new JSONObject().put("exists", !entry.getBoolean("deleted")).put("entry", entry);
                }
                return new JSONObject().put("exists", false);
            }
            case "sync.state.set": {
                if (!args.has("value")) throw new IllegalArgumentException("Missing state value");
                String key = key(args); Object value = args.get("value");
                cancellation.throwIfCanceled(); JSONObject entry=client.setStateEntry(key, value); wakeState(); return entry;
            }
            case "sync.state.delete": {
                String key = key(args); cancellation.throwIfCanceled(); JSONObject entry=client.deleteStateEntry(key); wakeState(); return entry;
            }
            case "sync.state.synchronize": {
                Object peer=args.opt("peerId");
                if(!(peer instanceof String) || !((String)peer).matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid peer identity");
                PodSyncForeground run=foreground((String)peer);
                if(run==null) throw new UnsupportedOperationException("Approved foreground peer unavailable");
                return run.synchronizeState(30000,cancellation);
            }
            case "sync.messages.send": {
                Object peer=args.opt("peerId"), raw=args.opt("message");
                if (!(peer instanceof String) || !(raw instanceof JSONObject)) throw new IllegalArgumentException("Invalid message arguments");
                JSONObject message=(JSONObject)raw; Object id=message.opt("messageId"), ttlValue=message.opt("ttlMs"), priority=message.opt("priority");
                if (!(id instanceof String) || !(ttlValue instanceof Number) || !(priority instanceof String) || !message.has("payload"))
                    throw new IllegalArgumentException("Invalid message fields");
                Number number=(Number)ttlValue; long ttl=number.longValue();
                if (ttl<=0 || ttl>9007199254740991L || number.doubleValue()!=(double)ttl ||
                    !(priority.equals("normal") || priority.equals("high"))) throw new IllegalArgumentException("Invalid message TTL or priority");
                byte[] bytes=PodSyncStateStore.encodeServiceJson(message.get("payload")); cancellation.throwIfCanceled();
                client.queueMessageWithTtl((String)peer,(String)id,bytes,ttl,priority.equals("high"),System.currentTimeMillis());
                PodSyncForeground run=foreground((String)peer);
                if(run!=null) try { run.requestMessages(); } catch(java.io.IOException stopped) { /* Durable outbox remains retryable. */ }
                return new JSONObject().put("messageId",id).put("state","queued");
            }
            case "sync.messages.ack": {
                Object peer=args.opt("peerId"), id=args.opt("messageId");
                if (!(peer instanceof String) || !(id instanceof String) ||
                    !((String)peer).matches("[A-Za-z0-9_.:-]{1,128}") || !((String)id).matches("[A-Za-z0-9_.:-]{1,128}"))
                    throw new IllegalArgumentException("Invalid message identity");
                Receipt delivery;
                synchronized (this) { delivery=deliveries.get(peer+"\n"+id); }
                if (delivery==null) throw new IllegalArgumentException("Message not exposed to this guest");
                cancellation.throwIfCanceled();
                PodSyncInbox.Message applied=client.ackMessageToken((String)peer,(String)id,delivery.token,System.currentTimeMillis());
                PodSyncForeground run=foreground((String)peer);
                if(run!=null) try { run.ackMessage(applied); } catch(java.io.IOException unavailable) { /* Peer retry reads the durable applied receipt. */ }
                // Retain the delivery until expiry for lost service-result retries.
                // This proves local business acknowledgement, not peer receipt.
                return JSONObject.NULL;
            }
            case "sync.files.offer": {
                if(guestFiles==null) throw new UnsupportedOperationException("Approved guest files unavailable");
                Object peer=args.opt("peerId"), path=args.opt("path"), mime=args.opt("mime");
                if(!(peer instanceof String) || !((String)peer).matches("[A-Za-z0-9_.:-]{1,128}") ||
                    !(path instanceof String) || !(mime instanceof String)) throw new IllegalArgumentException("Invalid file offer");
                JSONObject snapshot=client.snapshotGuestFile(guestFiles,(String)path,(String)mime);
                String id=snapshot.getString("transfer_id");
                try { cancellation.throwIfCanceled(); }
                catch(android.os.OperationCanceledException cancelled) { client.releaseSource(id); throw cancelled; }
                PodSyncOutgoingFiles.Status status;
                try { status=client.offerFile((String)peer,id); }
                catch(Exception failed) {
                    // releaseSource refuses any active durable reference, so an
                    // uncertain committed offer cannot lose its source copy.
                    try { client.releaseSource(id); } catch(Exception retained) { failed.addSuppressed(retained); }
                    throw failed;
                }
                PodSyncForeground run=foreground((String)peer);
                if(run!=null) try { run.requestFiles(false); } catch(java.io.IOException stopped) { /* Durable offer remains pending. */ }
                exposeFile(id);
                return new JSONObject().put("transferId",status.transferId).put("state","offered")
                    .put("receivedBytes",0).put("totalBytes",status.totalBytes);
            }
            case "sync.files.status": {
                String id=transferId(args); cancellation.throwIfCanceled();
                JSONObject status=client.fileServiceStatus(id); exposeFile(id); return status;
            }
            case "sync.files.accept": {
                String id=transferId(args); PodSyncClient.FileIdentity identity=exposedFile(id);
                if(!identity.incoming) throw new IllegalArgumentException("Cannot accept outgoing file");
                cancellation.throwIfCanceled(); client.acceptFile(identity.peerId,id);
                return client.fileServiceStatus(id);
            }
            case "sync.files.save": {
                if(guestFiles==null) throw new UnsupportedOperationException("Approved guest files unavailable");
                String id=transferId(args); PodSyncClient.FileIdentity identity=exposedFile(id);
                Object path=args.opt("path");
                if(!identity.incoming || !(path instanceof String)) throw new IllegalArgumentException("Invalid received file destination");
                return client.saveIncomingFile(identity.peerId,id,guestFiles,(String)path,cancellation);
            }
            case "sync.files.cancel": {
                String id=transferId(args); PodSyncClient.FileIdentity identity=exposedFile(id);
                cancellation.throwIfCanceled();
                if(identity.incoming) client.cancelUnfinishedIncomingFile(identity.peerId,id); else client.cancelOutgoingFile(identity.peerId,id);
                PodSyncForeground run=foreground(identity.peerId);
                if(run!=null) try { run.requestFiles(true); } catch(java.io.IOException stopped) { /* Durable cancellation remains available. */ }
                return JSONObject.NULL;
            }
            default:
                if (live == null) throw new UnsupportedOperationException("Approved sync connection operations unavailable");
                return live.execute(method, new JSONObject(args.toString()), cancellation);
        }
    }
}
