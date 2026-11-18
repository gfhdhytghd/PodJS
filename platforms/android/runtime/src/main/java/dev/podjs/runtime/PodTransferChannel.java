package dev.podjs.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Channel-local fixed-codec owner. Local registration supplies descriptors,
 * never file paths or other parameters issued directly by the peer. */
final class PodTransferChannel {
    interface Journal {
        void stage(JSONObject lease, JSONObject[] objects, List<PodReceiveWindow.Verified> chunks) throws Exception;
    }
    private final InputStream input;
    private final OutputStream output;
    private final Executor verifier;
    private final ScheduledExecutorService durable;
    private final Executor writer;
    private final Executor sourceWorker, senderWorker;
    private volatile PodSendWindow sender;
    private final PodTransferFrames.Rate ackBudget=new PodTransferFrames.Rate(256,128);
    private final PodTransferFrames.Rate staleBudget=new PodTransferFrames.Rate(32,16);
    private final Journal journal;
    private final Runnable abort;
    private final Object frameWrite = new Object();
    private final AtomicInteger pendingAcks = new AtomicInteger();
    private volatile boolean closed;
    private PodTransferIndex index;
    private final ArrayList<PodReceiveWindow.ObjectSlot> registrations = new ArrayList<>();
    private final ArrayList<JSONObject> objects = new ArrayList<>();
    private final ArrayList<Boolean> applied = new ArrayList<>();
    private volatile PodReceiveWindow window;
    private final AtomicLong progress = new AtomicLong();
    private final java.util.ArrayDeque<Long> durableEvents=new java.util.ArrayDeque<>(32);
    private boolean eventsOverflow;
    private final ScheduledExecutorService deadlines;
    private volatile boolean enabled;
    private final java.util.concurrent.ConcurrentSkipListSet<Long> retired = new java.util.concurrent.ConcurrentSkipListSet<>();
    private final byte[] scratch = new byte[4096];
    private volatile Exception failure;
    private int stream;
    private long epoch;
    private long nextAck = 1;

    PodTransferChannel(InputStream input, OutputStream output, Executor verifier,
                       ScheduledExecutorService durable, Executor writer, Journal journal, Runnable abort, ScheduledExecutorService deadlines, Executor sourceWorker, Executor senderWorker) {
        this.input = input;
        this.output = output;
        this.verifier = verifier;
        this.durable = durable;
        this.writer = writer;
        this.journal = journal;
        this.abort = abort;
        this.deadlines = deadlines;
        this.sourceWorker = sourceWorker;
        this.senderWorker = senderWorker;
    }

    synchronized void attachIndex(PodTransferIndex index) {
        this.index=index;
    }

    synchronized void register(JSONArray page) throws Exception {
        if(closed)throw new IOException("AUTH_REVOKED");
        if (window != null || page.length() == 0 || page.length() > 16 || registrations.size() + page.length() > 512)
            throw new IOException("LIMIT_EXCEEDED");
        ArrayList<PodReceiveWindow.ObjectSlot> validated = new ArrayList<>(page.length());
        ArrayList<JSONObject> localObjects = new ArrayList<>(page.length());
        ArrayList<Boolean> receipts = new ArrayList<>(page.length());
        for (int i = 0; i < page.length(); i++) {
            JSONObject value = page.getJSONObject(i);
            JSONObject object = value.getJSONObject("object");
            JSONArray hashes = object.getJSONArray("chunkHashes");
            JSONArray digests = value.getJSONArray("digests");
            JSONArray dependencies = value.getJSONArray("dependsOn");
            int size = bounded(object.getLong("size"), 0, 16777216);
            if (hashes.length() != (size + 65535) / 65536 || digests.length() != hashes.length()
                    || dependencies.length() > 33) throw new IOException("INVALID_FRAME");
            byte[][] expected = new byte[hashes.length()][];
            String[] requests = new String[hashes.length()];
            for (int j = 0; j < expected.length; j++) {
                expected[j] = digest(hashes.getString(j));
                requests[j] = digests.getString(j);
                digest(requests[j]);
            }
            int[] dependencySlots = new int[dependencies.length()];
            for (int j = 0; j < dependencySlots.length; j++)
                dependencySlots[j] = bounded(dependencies.getInt(j), 0, 511);
            validated.add(new PodReceiveWindow.ObjectSlot(size, expected, requests, dependencySlots, value.optBoolean("compatibility")));
            localObjects.add(object);
            receipts.add(value.getBoolean("applied"));
        }
        if(index!=null)index.append(page);
        registrations.addAll(validated);
        objects.addAll(localObjects);
        applied.addAll(receipts);
    }

    synchronized void enable(JSONObject lease, int stream, int windowSize) throws Exception {
        if (closed || window != null || stream != 1 && stream != 2) throw new IOException("INVALID_FRAME");
        for (PodReceiveWindow.ObjectSlot slot : registrations)
            for (int dependency : slot.dependencies)
                if (dependency >= registrations.size()) throw new IOException("INVALID_FRAME");
        if(index!=null)index.seal();
        if(closed)throw new IOException("AUTH_REVOKED");
        this.stream = stream;
        this.nextAck = 1;
        epoch = lease.getLong("epoch");
        if (epoch < 1 || epoch > 9007199254740991L) throw new IOException("INVALID_FRAME");
        boolean[] dispositions = new boolean[applied.size()];
        for (int i = 0; i < dispositions.length; i++) dispositions[i] = applied.get(i);
        JSONObject[] localObjects = objects.toArray(new JSONObject[0]);
        window = new PodReceiveWindow(stream, epoch, windowSize,
                registrations.toArray(new PodReceiveWindow.ObjectSlot[0]), dispositions,
                new PodTransferFence(), verifier, durable, writer,
                chunks -> {
                    ScheduledFuture<?> watchdog=deadlines.schedule(()->{
                        failure=new IOException("LOCAL_IO");abort.run();
                    },30,TimeUnit.SECONDS);
                    try{
                        journal.stage(lease, localObjects, chunks);
                        synchronized(durableEvents){
                            for(PodReceiveWindow.Verified chunk:chunks)if(!chunk.applied){
                                if(durableEvents.size()==32){durableEvents.removeFirst();eventsOverflow=true;}
                                durableEvents.addLast((long)chunk.header.slot*256+chunk.header.index);
                            }
                        }
                    }
                    finally{watchdog.cancel(false);}
                }, new PodReceiveWindow.Writer() {
                    public void queued() { pendingAcks.incrementAndGet(); }
                    public void finished() { synchronized(frameWrite){pendingAcks.decrementAndGet();frameWrite.notifyAll();} }
                    public void ack(List<Long> sequences, Runnable starting) throws IOException {
                        synchronized (frameWrite) {
                            PodReceiveWindow current = window;
                            if (current == null) throw new IOException("STALE_EPOCH");
                            List<Integer> dispositions = new ArrayList<>(sequences.size());
                            for (long sequence : sequences) dispositions.add(current.disposition(sequence));
                            writeLocked(PodTransferFrames.ack(stream, epoch, nextAck++, sequences, dispositions), null, starting);
                        }
                    }
                    public void failed(Exception error) {
                        failure = error;
                        abort.run();
                    }
                });
    }

    void bind(JSONArray bindings) throws Exception {
        PodReceiveWindow current = window;
        if (current == null || bindings.length() > 128) throw new IOException("INVALID_FRAME");
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.getJSONObject(i);
            String id = binding.getString("requestId"), hash = binding.getString("requestDigest");
            if (!id.matches("[0-9a-f]{32}")) throw new IOException("INVALID_FRAME");
            digest(hash);
            current.bindControl(id, hash);
        }
    }

    void markApplied(int slot) throws IOException {
        PodReceiveWindow current = window;
        if (current == null) throw new IOException("STALE_EPOCH");
        current.markApplied(slot);
    }

    JSONObject next() throws Exception {
        try {
            for (;;) {
                ScheduledFuture<?> idle=deadlines.schedule(this::expireFrame,30,TimeUnit.SECONDS);
                java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> record=new java.util.concurrent.atomic.AtomicReference<>();
                try{
                    PodTransferFrames.Header frame=PodTransferFrames.read(input,()->{
                        idle.cancel(false);
                        record.set(deadlines.schedule(this::expireFrame,60,TimeUnit.SECONDS));
                    });
                    PodReceiveWindow current = window;
                    if (frame.kind == PodTransferFrames.DATA && (current != null||!retired.isEmpty())) {
                        PodTransferFrames.Chunk data = new PodTransferFrames.Chunk(frame.bytes, frame.body);
                        if (retired.contains(data.epoch)) {
                            staleBudget.take();
                            for(int left=frame.body;left>0;){
                                int length=Math.min(left,scratch.length);
                                PodTransferFrames.readExact(input,scratch,0,length);
                                left-=length;
                            }
                            continue;
                        }
                        if(!enabled||current==null)throw new IOException("FLOW_CONTROL_VIOLATION");
                        String result=current.receive(data,input,scratch);
                        if(result==null){
                            progress.addAndGet(data.size);
                            continue;
                        }
                        if(!result.isEmpty())return numbers(frame.kind,frame.bytes).put("error",result);
                    }
                    JSONObject result = new JSONObject().put("kind", frame.kind);
                    if (frame.kind == PodTransferFrames.CONTROL) {
                        byte[] body = new byte[frame.body];
                        PodTransferFrames.readExact(input, body, 0, body.length);
                        return result.put("control", controlText(body));
                    }
                    byte[] bytes = frame.bytes;
                    if (frame.kind == PodTransferFrames.ACK) {
                        bytes = new byte[frame.bytes.length + frame.body];
                        System.arraycopy(frame.bytes, 0, bytes, 0, frame.bytes.length);
                        PodTransferFrames.readExact(input, bytes, frame.bytes.length, frame.body);
                        PodSendWindow active=sender;
                        ByteBuffer ack=ByteBuffer.wrap(bytes);
                        if(active!=null&&active.accepts(ack.getInt(16),ack.getLong(24))){
                            ackBudget.take();
                            if(active.acknowledge(bytes))progress.incrementAndGet();
                            continue;
                        }
                    }
                    return numbers(frame.kind,bytes);
                }finally{
                    idle.cancel(false);
                    ScheduledFuture<?> frameDeadline=record.get();
                    if(frameDeadline!=null)frameDeadline.cancel(false);
                }
            }
        } catch (Exception error) {
            if (failure != null) throw failure;
            throw error;
        }
    }

    private void expireFrame() {
        failure=new IOException("Stream operation timed out");
        abort.run();
    }

    private static JSONObject numbers(int kind,byte[] bytes) throws Exception {
        JSONArray values=new JSONArray();
        for(byte value:bytes)values.put(value&255);
        return new JSONObject().put("kind",kind).put("bytes",values);
    }

    private static String controlText(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("INVALID_FRAME", error);
        }
    }

    void writeControl(String text, boolean activate, long rejected) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length < 1 || body.length > 131072) throw new IOException("LIMIT_EXCEEDED");
        byte[] header = ByteBuffer.allocate(16).putInt(body.length + 12).putInt(0x4c425632)
                .putInt(1 << 24).putInt(body.length).array();
        synchronized(frameWrite) {
            awaitPriority();
            if(rejected!=0){
                PodReceiveWindow current=window;
                if(current==null)throw new IOException("STALE_EPOCH");
                current.settleDenied(rejected);
            }
            writeLocked(header,body,activate ? () -> enabled=true : null);
        }
    }

    void writeCompatibilityAck(byte[] header) throws IOException {
        synchronized (frameWrite) {
            awaitPriority();
            PodReceiveWindow current = window;
            if (current != null) {
                List<Long> sequences = PodTransferFrames.ackEntries(header, stream, epoch);
                current.settleCompatibility(sequences);
                ByteBuffer.wrap(header).putLong(32, nextAck++);
            }
            writeLocked(header, null, null);
        }
    }

    void write(byte[] header, byte[] body, Runnable starting) throws IOException {
        synchronized (frameWrite) {
            awaitPriority();
            writeLocked(header,body,starting);
        }
    }
    private void awaitPriority()throws IOException {
        while(pendingAcks.get()>0&&!closed){
            try{frameWrite.wait(50);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Stream closed",interrupted);}
        }
    }

    private void writeLocked(byte[] header,byte[] body,Runnable starting)throws IOException {
        if(closed)throw new IOException("Stream closed");
        if(starting!=null)starting.run();
        output.write(header);
        if(body!=null)output.write(body);
        output.flush();
    }

    JSONObject send(JSONObject configuration,PodSendWindow.ReadRange readRange) throws Exception {
        if(closed)throw new IOException("AUTH_REVOKED");
        JSONArray rows=configuration.getJSONArray("chunks");
        if(rows.length()<1||rows.length()>1024)throw new IOException("LIMIT_EXCEEDED");
        List<PodSendWindow.Chunk> plan=new ArrayList<>(rows.length());
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            int size=bounded(row.getInt("size"),1,65536);
            long offset=row.getLong("offset");
            if(offset<0||offset>536870912L-size)throw new IOException("SOURCE_CHANGED");
            PodSendWindow.Chunk chunk=new PodSendWindow.Chunk(bounded(row.getInt("slot"),0,511),
                    bounded(row.getInt("index"),0,255),size,offset,row.getString("path"),
                    row.getString("operation"),row.getString("digest"),row.getString("sha256"));
            plan.add(chunk);
        }
        PodSendWindow active=new PodSendWindow(configuration.getInt("stream"),configuration.getLong("epoch"),
                configuration.getInt("window"),configuration.getLong("tx"),configuration.getLong("ack"),
                plan,senderWorker,sourceWorker,deadlines,readRange,(lane,chunk,body)->{
                    synchronized(frameWrite){
                        awaitPriority();
                        if(closed)throw new IOException("AUTH_REVOKED");
                        writeLocked(lane.reserve(chunk),body,null);
                        progress.addAndGet(body.length);
                    }
                });
        synchronized(this){
            if(sender!=null||closed)throw new IOException("BUSY");
            sender=active;
        }
        try{
            PodSendWindow.Result result=active.start().get();
            return new JSONObject().put("tx",result.tx).put("ack",result.ack).put("staged",new JSONArray(result.staged)).put("error",result.error);
        }catch(java.util.concurrent.ExecutionException error){
            if(error.getCause() instanceof Exception)throw (Exception)error.getCause();
            throw error;
        }finally{
            active.stop(new IOException("AUTH_REVOKED"));
            try{active.awaitDrained();}finally{sender=null;}
        }
    }

    void rejectSend(JSONObject args) throws Exception {
        PodSendWindow active=sender;
        if(active==null||!active.accepts(args.getInt("stream"),args.getLong("epoch")))throw new IOException("STALE_EPOCH");
        active.negative(args.getString("operation"),args.getString("digest"),args.getString("code"),args.getInt("retryAfterMillis"));
    }

    PodTransferFence guard(long expectedEpoch) throws IOException {
        PodReceiveWindow active=window;
        if(closed||active==null||expectedEpoch!=epoch)throw new IOException("STALE_EPOCH");
        PodTransferFence fence=active.fence();
        fence.check();
        return fence;
    }

    void awaitClosed() throws Exception {
        PodReceiveWindow active=window;
        if(active!=null)active.awaitRevoked();
        PodSendWindow outgoing=sender;
        if(outgoing!=null)outgoing.awaitDrained();
        synchronized(frameWrite){while(pendingAcks.get()!=0)frameWrite.wait();}
        synchronized(this){
            if(index!=null){index.close();index=null;}
        }
    }

    JSONObject state()throws Exception{
        synchronized(durableEvents){
            JSONObject state=new JSONObject().put("progress",progress.get())
                    .put("durable",new JSONArray(durableEvents)).put("overflow",eventsOverflow);
            durableEvents.clear();
            eventsOverflow=false;
            return state;
        }
    }

    void retire() throws Exception {
        PodReceiveWindow current = window;
        if(current==null)return;
        retired.add(epoch);
        enabled = false;
        current.close();
        current.awaitRevoked();
        synchronized(this) {
            if(window!=current)return;
            window=null;
            synchronized(durableEvents){durableEvents.clear();eventsOverflow=false;}
            if(index!=null)index.reset();
            registrations.clear();
            objects.clear();
            applied.clear();
        }
    }

    void close() {
        closed=true;
        PodSendWindow active=sender;if(active!=null)active.stop(new IOException("AUTH_REVOKED"));
        PodReceiveWindow current = window;
        if (current != null) current.close();
    }

    private static int bounded(long n, int low, int high) throws IOException {
        if (n < low || n > high) throw new IOException("INVALID_FRAME");
        return (int)n;
    }

    private static byte[] digest(String text) throws IOException {
        if (!text.matches("[0-9a-f]{64}")) throw new IOException("INVALID_FRAME");
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte)Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
