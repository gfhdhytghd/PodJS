package dev.podjs.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One receive epoch. There is one caller for DATA headers and one
 * consumer per lane. Socket I/O and durable I/O never share an executor. */
final class PodReceiveWindow {
    interface Committed {
        void stage(List<Verified> chunks) throws Exception;
    }
    interface Writer {
        void queued();
        void ack(List<Long> sequences, Runnable starting) throws Exception;
        void finished();
        void failed(Exception error);
    }

    static final class ObjectSlot {
        final int size;
        final byte[][] hashes;
        final String[] digests;
        final int[] dependencies;
        final boolean compatibility;
        ObjectSlot(int size, byte[][] hashes, String[] digests, int[] dependencies, boolean compatibility) {
            if (size < 0 || size > 16777216 || hashes.length != (size + 65535) / 65536
                    || digests.length != hashes.length) throw new IllegalArgumentException("Invalid registration");
            this.size = size;
            this.hashes = hashes;
            this.digests = digests;
            this.dependencies = dependencies;
            this.compatibility = compatibility;
        }
    }

    static final class Verified {
        final PodTransferFrames.Chunk header;
        final byte[] bytes;
        final boolean applied;
        long verifiedAt;
        Verified(PodTransferFrames.Chunk header, byte[] bytes, boolean applied) {
            this.header = header;
            this.bytes = bytes;
            this.applied = applied;
        }
    }

    private final ObjectSlot[] slots;
    private final boolean[] applied;
    private final int stream;
    private final long epoch;
    private final int window;
    private final int groupSize;
    private final PodTransferFence fence;
    private final Executor hashWorker;
    private final ScheduledExecutorService journalWorker;
    private final Executor writerWorker;
    private final Committed journal;
    private final Writer writer;
    private final ArrayBlockingQueue<byte[]> pool;
    private final ArrayDeque<Verified> verified = new ArrayDeque<>();
    private final LinkedHashMap<Long, PodTransferFrames.Chunk> pending = new LinkedHashMap<>();
    private final java.util.Set<Long> denied = new java.util.HashSet<>();
    private final Map<String, String> bindings = new HashMap<>();
    private long nextSequence = 1;

    private int activeSlot = -1;
    private ScheduledFuture<?> tail;
    private boolean writingJournal;
    private boolean closed;

    PodReceiveWindow(int stream, long epoch, int window, ObjectSlot[] slots, boolean[] applied,
                     PodTransferFence fence, Executor hashWorker, ScheduledExecutorService journalWorker,
                     Executor writerWorker, Committed journal, Writer writer) {
        if (window != 1 && window != 8 || slots.length > 512 || slots.length != applied.length)
            throw new IllegalArgumentException("Invalid receive window");
        this.stream = stream;
        this.epoch = epoch;
        this.window = window;
        this.groupSize = Math.min(4, window);
        this.slots = slots.clone();
        this.applied = applied.clone();
        this.fence = fence;
        this.hashWorker = hashWorker;
        this.journalWorker = journalWorker;
        this.writerWorker = writerWorker;
        this.journal = journal;
        this.writer = writer;
        pool = new ArrayBlockingQueue<>(window);
        for (int i = 0; i < window; i++) pool.add(new byte[65536]);
    }

    synchronized boolean checkBinding(String operation, String digest) throws IOException {
        fence.check();
        String prior = bindings.get(operation);
        if (prior != null && !prior.equals(digest)) throw new IOException("REQUEST_CONFLICT");
        return prior != null;
    }

    synchronized void bindControl(String operation, String digest) throws IOException {
        if (checkBinding(operation, digest)) return;
        if (bindings.size() == 12288) throw new IOException("STORAGE_PRESSURE");
        bindings.put(operation, digest);
    }

    synchronized void markApplied(int slot) throws IOException {
        fence.check();
        if(slot<0||slot>=slots.length)throw new IOException("INVALID_FRAME");
        for (PodTransferFrames.Chunk chunk : pending.values())
            if (chunk.slot == slot) throw new IOException("BUSY");
        applied[slot] = true;
        if (activeSlot == slot) activeSlot = -1;
    }

    private synchronized String reserve(PodTransferFrames.Chunk header) throws IOException {
        fence.check();
        if (header.stream != stream || header.epoch != epoch) throw new IOException("STALE_EPOCH");
        if (header.sequence != nextSequence || header.slot >= slots.length)
            throw new IOException("INVALID_FRAME");
        if (pending.size() >= window) throw new IOException("FLOW_CONTROL_VIOLATION");
        ObjectSlot slot = slots[header.slot];
        if (header.index >= slot.hashes.length || !slot.digests[header.index].equals(header.digest)
                || header.size != Math.min(65536, slot.size - 65536 * header.index))
            throw new IOException("REQUEST_CONFLICT");
        for (PodTransferFrames.Chunk chunk : pending.values())
            if (chunk.operation.equals(header.operation) || chunk.slot == header.slot && chunk.index == header.index)
                throw new IOException("REQUEST_CONFLICT");
        String reason = null;
        if (!applied[header.slot]) {
            if (activeSlot != -1 && activeSlot != header.slot) reason = "WINDOW_FULL";
            else {
                for (int dependency : slot.dependencies)
                    if (!applied[dependency]) { reason = "DEPENDENCIES_PENDING"; break; }
            }
            if (reason == null) activeSlot = header.slot;
        }
        // Reserve before letting the next CONTROL share this operation ID.
        bindControl(header.operation, header.digest);
        nextSequence++;
        pending.put(header.sequence, header);
        if (reason != null) denied.add(header.sequence);
        return reason;
    }

    /** null means consumed natively, empty means a compatibility frame. */
    String receive(PodTransferFrames.Chunk header, InputStream input, byte[] scratch) throws Exception {
        String failure = reserve(header);
        ObjectSlot slot = slots[header.slot];
        if (failure != null) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (int left = header.size; left > 0;) {
                int length = Math.min(left, scratch.length);
                PodTransferFrames.readExact(input, scratch, 0, length);
                hash.update(scratch, 0, length);
                left -= length;
            }
            if (!Arrays.equals(hash.digest(), slot.hashes[header.index])) throw new IOException("HASH_MISMATCH");
            return failure;
        }
        if (slot.compatibility) return "";
        boolean alreadyApplied = applied[header.slot];
        byte[] bytes = pool.poll();
        if (bytes == null) throw new IOException("FLOW_CONTROL_VIOLATION");
        try {
            PodTransferFrames.readExact(input, bytes, 0, header.size);
            hashWorker.execute(() -> hash(new Verified(header, bytes, alreadyApplied)));
            return null;
        } catch (IOException | RejectedExecutionException error) {
            pool.offer(bytes);
            throw error;
        }
    }

    private void hash(Verified chunk) {
        boolean queued = false;
        try {
            fence.check();
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(chunk.bytes, 0, chunk.header.size);
            if (!Arrays.equals(sha256.digest(), slots[chunk.header.slot].hashes[chunk.header.index]))
                throw new IOException("HASH_MISMATCH");
            synchronized (this) {
                if (closed) return;
                chunk.verifiedAt=System.nanoTime();
                verified.addLast(chunk);
                queued = true;
                if (!writingJournal) schedule();
            }
        } catch (Exception failure) {
            fail(failure);
        } finally {
            if (!queued) pool.offer(chunk.bytes);
        }
    }

    private void schedule() {
        if (verified.isEmpty() || closed) return;
        if (verified.size() >= groupSize) {
            if (tail != null) tail.cancel(false);
            tail = null;
            writingJournal = true;
            journalWorker.execute(this::commitVerified);
        } else if (tail == null) {
            long elapsed=System.nanoTime()-verified.getFirst().verifiedAt;
            long delay=Math.max(0,TimeUnit.MILLISECONDS.toNanos(20)-elapsed);
            tail=journalWorker.schedule(this::commitVerified,delay,TimeUnit.NANOSECONDS);
        }
    }

    private void commitVerified() {
        ArrayList<Verified> batch = new ArrayList<>(groupSize);
        synchronized (this) {
            tail = null;
            if (closed) return;
            writingJournal = true;
            while (batch.size() < groupSize && !verified.isEmpty()) batch.add(verified.removeFirst());
        }
        List<Long> sequences = new ArrayList<>(batch.size());
        try {
            if (!batch.isEmpty()) {
                fence.publish(() -> { journal.stage(batch); return null; });
                fence.check();
                for (Verified item : batch) sequences.add(item.header.sequence);
            }
        } catch (Exception failure) {
            fail(failure);
        } finally {
            // Replenish payload buffers before the peer can act on an ACK.
            for (Verified item : batch) pool.offer(item.bytes);
            synchronized (this) {
                writingJournal = false;
                schedule();
            }
        }
        if (!sequences.isEmpty() && fence.live()) {
            writer.queued();
            try { writerWorker.execute(() -> publishAck(sequences)); }
            catch (RejectedExecutionException rejected) {
                writer.finished();
                fail(rejected);
            }
        }
    }

    private void publishAck(List<Long> sequences) {
        try {
            fence.check();
            writer.ack(sequences, () -> {
                synchronized (this) {
                    for (long sequence : sequences) pending.remove(sequence);
                }
            });
        } catch (Exception failure) {
            fail(failure);
        } finally { writer.finished(); }
    }

    synchronized void settleDenied(long sequence) throws IOException {
        fence.check();
        if (!denied.remove(sequence)) throw new IOException("INVALID_FRAME");
        pending.remove(sequence);
    }

    synchronized void settleCompatibility(List<Long> sequences) throws IOException {
        fence.check();
        for (long sequence : sequences) {
            PodTransferFrames.Chunk header = pending.get(sequence);
            if (header == null || !slots[header.slot].compatibility) throw new IOException("INVALID_FRAME");
        }
        for (long sequence : sequences) pending.remove(sequence);
    }

    synchronized int disposition(long sequence) throws IOException {
        fence.check();
        PodTransferFrames.Chunk header = pending.get(sequence);
        if (header == null) throw new IOException("INVALID_FRAME");
        return applied[header.slot] ? 2 : 1;
    }

    void awaitRevoked() throws InterruptedException { fence.awaitRevoked(); }

    void close() {
        fence.revoke();
        synchronized (this) {
            closed = true;
            if (tail != null) tail.cancel(false);
            while (!verified.isEmpty()) pool.offer(verified.removeFirst().bytes);
        }
    }

    private void fail(Exception error) {
        if (fence.live()) {
            close();
            writer.failed(error);
        }
    }
}
