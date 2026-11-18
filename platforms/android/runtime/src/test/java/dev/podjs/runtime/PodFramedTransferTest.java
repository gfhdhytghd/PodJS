package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PodFramedTransferTest {
    private static final byte[] BLOCK = new byte[65536];

    private static JSONObject call(PodByteStreamServices service, String method, JSONObject args) throws Exception {
        return service.execute("stream.framed" + method, args, new CancellationSignal());
    }

    private static JSONObject id(String channel) throws Exception {
        return new JSONObject().put("id", channel);
    }

    private static String digest(int value) {
        return String.format(java.util.Locale.ROOT, "%064x", value);
    }

    private static String hash() throws Exception {
        return PodFileRanges.hex(MessageDigest.getInstance("SHA-256").digest(BLOCK));
    }

    private static byte[] data(int chunk, long sequence) {
        ByteBuffer header = ByteBuffer.allocate(96);
        header.putInt(92 + BLOCK.length).putInt(0x4c425632).putInt(2 << 24).putInt(80)
                .putInt(1).putInt(0).putLong(1).putLong(sequence).putInt(chunk).putInt(BLOCK.length);
        header.putLong(0).putLong(chunk + 1);
        header.position(92);
        header.putInt(chunk + 1);
        return header.array();
    }

    private static PodTransferFrames.Header read(Socket socket) throws Exception {
        PodTransferFrames.Header header = PodTransferFrames.read(socket.getInputStream());
        if (header.kind == PodTransferFrames.CONTROL)
            PodTransferFrames.readExact(socket.getInputStream(), new byte[header.body], 0, header.body);
        return header;
    }

    private static String attach(PodByteStreamServices service, Socket nativeSocket, int window) throws Exception {
        String original = service.attach(nativeSocket.getInputStream(), nativeSocket.getOutputStream(), nativeSocket);
        String channel = service.execute("stream.claim", id(original), new CancellationSignal()).getString("id");
        call(service, "Open", id(channel));
        JSONArray hashes = new JSONArray(), digests = new JSONArray();
        String hash = hash();
        for (int i = 0; i < window; i++) {
            hashes.put(hash);
            digests.put(digest(i + 1));
        }
        JSONObject object = new JSONObject().put("id", digest(94)).put("size", window * BLOCK.length)
                .put("sha256", hash).put("chunkHashes", hashes);
        JSONObject slot = new JSONObject().put("object", object).put("digests", digests)
                .put("dependsOn", new JSONArray()).put("applied", false);
        call(service, "Register", id(channel).put("page", new JSONArray().put(slot)));
        call(service, "Enable", id(channel).put("lease", new JSONObject().put("epoch", 1))
                .put("stream", 1).put("window", window));
        call(service, "Control", id(channel).put("control", "{}")
                .put("activate", true).put("timeoutMs", 4000));
        return channel;
    }

    @Test public void nativeDataAndAcksAdvanceWhileGuestReadRemainsPending() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (int size : new int[]{1, 8}) {
            AtomicInteger committed = new AtomicInteger();
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 Socket peer = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                 Socket nativeSocket = listener.accept();
                 PodByteStreamServices service = new PodByteStreamServices(context, (lease, objects, chunks) -> {
                     assertFalse(chunks.isEmpty());
                     for (PodReceiveWindow.Verified chunk : chunks) {
                         assertEquals(BLOCK.length, chunk.header.size);
                         assertTrue(Arrays.equals(BLOCK, chunk.bytes));
                     }
                     committed.addAndGet(chunks.size());
                 })) {
                peer.setSoTimeout(5000);
                String channel = attach(service, nativeSocket, size);
                assertEquals(PodTransferFrames.CONTROL, read(peer).kind);
                Future<JSONObject> next = reader.submit(() -> call(service, "Next", id(channel).put("timeoutMs", 5000)));
                for (int i = 0; i < size; i++) {
                    peer.getOutputStream().write(data(i, i + 1));
                    peer.getOutputStream().write(BLOCK);
                }
                int settled = 0;
                while (settled < size) {
                    PodTransferFrames.Header ack = read(peer);
                    assertEquals(PodTransferFrames.ACK, ack.kind);
                    byte[] body = new byte[ack.body];
                    PodTransferFrames.readExact(peer.getInputStream(), body, 0, body.length);
                    int entries = ByteBuffer.wrap(body).getInt(24);
                    assertTrue(entries > 0);
                    settled += entries;
                    assertTrue(committed.get() >= settled);
                }
                assertEquals(size, committed.get());
                assertFalse(next.isDone());
                service.execute("stream.close", id(channel), new CancellationSignal());
                try { next.get(5, TimeUnit.SECONDS); fail("Expected closed socket"); }
                catch (java.util.concurrent.ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof IOException);
                }
            } finally { reader.shutdownNow(); }
        }
    }

    @Test public void closingDuringDurabilityWaitClosesSocketAndDropsDeferredAck() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), durable = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket peer = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket nativeSocket = listener.accept();
             PodByteStreamServices service = new PodByteStreamServices(context, (lease, objects, chunks) -> {
                 entered.countDown();
                 assertTrue(finish.await(5, TimeUnit.SECONDS));
                 durable.countDown();
             })) {
            peer.setSoTimeout(5000);
            String channel = attach(service, nativeSocket, 1);
            read(peer);
            Future<JSONObject> next = reader.submit(() -> call(service, "Next", id(channel).put("timeoutMs", 5000)));
            peer.getOutputStream().write(data(0, 1));
            peer.getOutputStream().write(BLOCK);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.execute("stream.close", id(channel), new CancellationSignal());
            assertEquals(-1, peer.getInputStream().read());
            assertEquals(1, durable.getCount());
            finish.countDown();
            assertTrue(durable.await(5, TimeUnit.SECONDS));
            try { next.get(5, TimeUnit.SECONDS); fail("Expected closed socket"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
        } finally { finish.countDown(); reader.shutdownNow(); }
    }
}
