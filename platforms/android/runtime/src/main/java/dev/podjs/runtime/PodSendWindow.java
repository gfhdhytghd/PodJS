package dev.podjs.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** The reader only settles credit. One source worker reads at most one
 * additional block while the socket owns the current frame. */
final class PodSendWindow {
    interface ReadRange { byte[] read(Chunk chunk) throws Exception; }
    interface WriteFrame { void write(PodSendWindow window,Chunk chunk,byte[] body) throws Exception; }

    static final class Chunk {
        final int slot, index, size;
        final long offset;
        final String path, operation, digest, hash;
        Chunk(int slot, int index, int size, long offset, String path, String operation, String digest, String hash) {
            this.slot = slot;
            this.index = index;
            this.size = size;
            this.offset = offset;
            this.path = path;
            this.operation = operation;
            this.digest = digest;
            this.hash = hash;
        }
    }

    static final class Result {
        final long tx, ack;
        final List<String> staged;
        final String error;
        Result(long tx,long ack,List<String> staged,String error){this.tx=tx;this.ack=ack;this.staged=new java.util.ArrayList<>(staged);this.error=error;}
    }

    private final int stream;
    private final long epoch;
    private final int maximum;
    private final Executor senderWorker, sourceWorker;
    private final ScheduledExecutorService timers;
    private final ReadRange source;
    private final WriteFrame writer;
    private final ArrayDeque<Chunk> ready;
    private final HashMap<Long, Chunk> inFlight = new HashMap<>();
    private final PodTransferFrames.Rate staleBudget=new PodTransferFrames.Rate(32,16);
    private final CompletableFuture<Result> completion = new CompletableFuture<>();
    private long tx;
    private long ack;
    private final java.util.ArrayList<String> staged = new java.util.ArrayList<>();
    private int retrying;
    private int retries;
    private String resultError;
    private boolean pumping;
    private int reading;
    private Chunk prepared;
    private CompletableFuture<byte[]> prefetch;

    PodSendWindow(int stream, long epoch, int maximum, long tx, long ack, List<Chunk> chunks,
                  Executor senderWorker, Executor sourceWorker, ScheduledExecutorService timers, ReadRange source, WriteFrame writer) {
        if (stream != 1 && stream != 2 || maximum != 1 && maximum != 8 || chunks.size() > 1024)
            throw new IllegalArgumentException("Invalid send window");
        this.stream = stream;
        this.epoch = epoch;
        this.maximum = maximum;
        this.tx = tx;
        this.ack = ack;
        this.ready = new ArrayDeque<>(chunks);
        this.senderWorker = senderWorker;
        this.sourceWorker = sourceWorker;
        this.timers = timers;
        this.source = source;
        this.writer = writer;
    }

    synchronized CompletableFuture<Result> start() { kick(); return completion; }
    synchronized boolean accepts(int stream, long epoch) { return stream==this.stream && epoch==this.epoch; }

    private synchronized void kick() {
        if (completion.isDone() || pumping) return;
        if (ready.isEmpty()) {
            if (inFlight.isEmpty() && retrying == 0) completion.complete(new Result(tx,ack,staged,resultError));
            return;
        }
        if (inFlight.size() >= maximum) return;
        pumping = true;
        senderWorker.execute(this::pump);
    }

    private void pump() {
        try {
            for (;;) {
                Chunk item;
                CompletableFuture<byte[]> loading;
                synchronized (this) {
                    if (completion.isDone() || inFlight.size() >= maximum || ready.isEmpty()) break;
                    item = ready.removeFirst();
                    prepare(item);
                    loading = prefetch;
                }
                byte[] body = loading.get();
                synchronized (this) {
                    prefetch = null;
                    prepared = null;
                    if (completion.isDone()||resultError!=null) break;
                    if (!ready.isEmpty()) prepare(ready.getFirst());
                }
                writer.write(this,item,body);
            }
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause=failure.getCause();
            stop(cause instanceof Exception?(Exception)cause:failure);
        } catch (Exception failure) {
            stop(failure);
        } finally {
            synchronized (this) { pumping = false; kick(); notifyAll(); }
        }
    }

    private void prepare(Chunk chunk) {
        if(prepared==chunk)return;
        prepared=chunk;
        CompletableFuture<byte[]> next=new CompletableFuture<>();
        prefetch=next;
        reading++;
        sourceWorker.execute(()->{
            try{
                byte[] body=source.read(chunk);
                if(body.length!=chunk.size||!ArraysCompat.hex(MessageDigest.getInstance("SHA-256").digest(body)).equals(chunk.hash))
                    throw new IOException("SOURCE_CHANGED");
                next.complete(body);
            }catch(Exception error){next.completeExceptionally(error);}
            finally {synchronized(this){reading--;notifyAll();}}
        });
    }

    synchronized byte[] reserve(Chunk chunk) throws IOException {
        if(completion.isDone())throw new IOException("AUTH_REVOKED");
        long sequence=++tx;
        if (sequence > 9007199254740991L) throw new IOException("LIMIT_EXCEEDED");
        ByteBuffer bytes = ByteBuffer.allocate(96);
        bytes.putInt(92 + chunk.size).putInt(0x4c425632).putInt(2 << 24).putInt(80);
        bytes.putInt(stream).putInt(chunk.slot).putLong(epoch).putLong(sequence);
        bytes.putInt(chunk.index).putInt(chunk.size);
        bytes.put(ArraysCompat.decode(chunk.operation,16)).put(ArraysCompat.decode(chunk.digest,32));
        inFlight.put(sequence,chunk);
        return bytes.array();
    }

    synchronized boolean acknowledge(byte[] frame) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(frame);
        long sequence = bytes.getLong(32);
        List<Long> entries = PodTransferFrames.ackEntries(frame,stream,epoch);
        if (sequence < 1 || sequence > 9007199254740991L || bytes.getInt(20)!=0 || bytes.getInt(44)!=0)
            throw new IOException("INVALID_FRAME");
        long previous=0;
        for (int i=0;i<entries.size();i++) {
            long id=entries.get(i), disposition=bytes.getLong(56+16*i);
            if (id<=previous || disposition!=1L<<56 && disposition!=2L<<56)
                throw new IOException("INVALID_FRAME");
            previous=id;
        }
        if (sequence <= ack) {
            staleBudget.take();
            return false;
        }
        if (sequence != ack+1 || entries.size()>maximum) throw new IOException("INVALID_FRAME");
        for (long id:entries) if(!inFlight.containsKey(id))throw new IOException("INVALID_FRAME");
        ack=sequence;
        for(int i=0;i<entries.size();i++){
            Chunk chunk=inFlight.remove(entries.get(i));
            if(bytes.get(56+16*i)==1)staged.add(chunk.slot+":"+chunk.index);
        }
        retries=0;
        kick();
        return true;
    }

    synchronized void negative(String operation,String digest,String result,int retryAfterMillis) throws IOException {
        Map.Entry<Long,Chunk> match=null;
        for(Map.Entry<Long,Chunk> entry:inFlight.entrySet())
            if(entry.getValue().operation.equals(operation)){match=entry;break;}
        if(match==null||!match.getValue().digest.equals(digest))throw new IOException("REQUEST_CONFLICT");
        Chunk chunk=match.getValue();
        inFlight.remove(match.getKey());
        if(resultError!=null){kick();return;}
        if("WINDOW_FULL".equals(result)||"BUSY".equals(result)){
            retrying++;
            int delay=Math.max(retryAfterMillis,Math.min(1000,100<<Math.min(retries++,4)));
            timers.schedule(()->{
                synchronized(this){retrying--;if(resultError==null&&!completion.isDone())ready.addFirst(chunk);kick();}
            },delay,TimeUnit.MILLISECONDS);
        }else if("DEPENDENCIES_PENDING".equals(result)||"LEASE_EXPIRED".equals(result)||"CHUNKS_MISSING".equals(result)){
            resultError=result;
            ready.clear();
            kick();
        }else stop(new IOException(result));
    }

    synchronized void awaitDrained() throws InterruptedException {
        while(pumping||reading!=0)wait();
    }

    synchronized void stop(Exception error) {
        ready.clear();
        inFlight.clear();
        completion.completeExceptionally(error);
    }

    private static final class ArraysCompat {
        static String hex(byte[] input) {
            char[] chars=new char[input.length*2];
            String alphabet="0123456789abcdef";
            for(int i=0;i<input.length;i++){
                chars[2*i]=alphabet.charAt(input[i]>>>4&15);
                chars[2*i+1]=alphabet.charAt(input[i]&15);
            }
            return new String(chars);
        }
        static byte[] decode(String input,int length) throws IOException {
            if(input.length()!=2*length)throw new IOException("INVALID_FRAME");
            byte[] bytes=new byte[length];
            for(int i=0;i<length;i++){
                int high=Character.digit(input.charAt(i*2),16),low=Character.digit(input.charAt(i*2+1),16);
                if(high<0||low<0)throw new IOException("INVALID_FRAME");
                bytes[i]=(byte)(high*16+low);
            }
            return bytes;
        }
    }
}
