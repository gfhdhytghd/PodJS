package dev.podjs.runtime;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;

/** Fixed length-prefixed-chunks-v1 framing. CONTROL body bytes remain opaque. */
final class PodTransferFrames {
    static final int PREFIX = 16;
    static final int DATA_HEADER = 96;
    static final int CHUNK = 65536;
    static final int CONTROL = 1;
    static final int DATA = 2;
    static final int ACK = 3;
    private static final int MAGIC = 0x4c425632;
    private static final long MAX_INTEGER = 9007199254740991L;

    static final class Rate {
        private final int burst, perSecond;
        private double tokens;
        private long at=System.nanoTime();
        Rate(int burst,int perSecond){this.burst=burst;this.perSecond=perSecond;tokens=burst;}
        synchronized void take()throws IOException{
            long now=System.nanoTime();
            tokens=Math.min(burst,tokens+(now-at)*perSecond/1e9);at=now;
            if(tokens<1)throw new IOException("RATE_LIMITED");
            tokens--;
        }
    }

    static final class Header {
        final byte[] bytes;
        final int kind;
        final int body;
        Header(byte[] bytes, int kind, int body) {
            this.bytes = bytes;
            this.kind = kind;
            this.body = body;
        }
    }

    static final class Chunk {
        final int stream;
        final int slot;
        final long epoch;
        final long sequence;
        final int index;
        final int size;
        final String operation;
        final String digest;
        Chunk(byte[] fixed, int payload) throws IOException {
            ByteBuffer bytes = ByteBuffer.wrap(fixed);
            stream = stream(bytes.getInt(16));
            slot = bounded(bytes.getInt(20), 0, 4095);
            epoch = positive(bytes.getLong(24));
            sequence = positive(bytes.getLong(32));
            index = bounded(bytes.getInt(40), 0, 255);
            size = bounded(bytes.getInt(44), 1, CHUNK);
            if (size != payload) throw invalid();
            operation = hex(fixed, 48, 16);
            digest = hex(fixed, 64, 32);
        }
    }

    static Header read(InputStream input) throws IOException {
        byte[] prefix = new byte[PREFIX];
        int first = input.read();
        if (first == -1) throw new EOFException("EOF");
        prefix[0] = (byte)first;
        readExact(input, prefix, 1, prefix.length - 1);
        ByteBuffer bytes = ByteBuffer.wrap(prefix);
        int length = bounded(bytes.getInt(), 12, 131084);
        if (bytes.getInt() != MAGIC) throw invalid();
        int kind = bytes.get() & 255;
        if (bytes.get() != 0 || bytes.getShort() != 0) throw invalid();
        int header = bounded(bytes.getInt(), 0, 131072);
        int payload = length - 12 - header;
        if (kind == CONTROL && payload == 0 && header > 0)
            return new Header(prefix, kind, header);
        if (kind == ACK && payload == 0 && header >= 48 && header <= 544)
            return new Header(prefix, kind, header);
        if (kind != DATA || header != 80 || payload < 1 || payload > CHUNK) throw invalid();
        byte[] data = new byte[DATA_HEADER];
        System.arraycopy(prefix, 0, data, 0, prefix.length);
        readExact(input, data, PREFIX, DATA_HEADER - PREFIX);
        return new Header(data, kind, payload);
    }

    static void readExact(InputStream input, byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            int size = input.read(bytes, offset, length);
            if (size < 0) throw new EOFException("Truncated sync stream");
            if (size == 0) throw new IOException("Stream read made no progress");
            length -= size;
            offset += size;
        }
    }

    static byte[] ack(int stream, long epoch, long sequence, List<Long> settled, List<Integer> dispositions) throws IOException {
        int count = bounded(settled.size(), 1, 32);
        if (dispositions.size() != count) throw invalid();
        int header = 32 + 16 * count;
        ByteBuffer bytes = ByteBuffer.allocate(PREFIX + header);
        bytes.putInt(header + 12).putInt(MAGIC).putInt(ACK << 24).putInt(header);
        bytes.putInt(stream(stream)).putInt(0).putLong(positive(epoch)).putLong(positive(sequence));
        bytes.putInt(count).putInt(0);
        long last = 0;
        for (int i = 0; i < count; i++) {
            long current = settled.get(i);
            if (current <= last) throw invalid();
            bytes.putLong(positive(current)).putLong((long)bounded(dispositions.get(i), 1, 2) << 56);
            last = current;
        }
        return bytes.array();
    }

    static List<Long> ackEntries(byte[] frame, int stream, long epoch) throws IOException {
        if (frame.length < 64 || frame.length > 560) throw invalid();
        ByteBuffer bytes = ByteBuffer.wrap(frame);
        int count = bounded(bytes.getInt(40), 1, 32);
        if (frame.length != 48 + 16 * count || bytes.getInt(16) != stream || bytes.getLong(24) != epoch)
            throw invalid();
        List<Long> sequences = new ArrayList<>(count);
        for (int i = 0; i < count; i++) sequences.add(positive(bytes.getLong(48 + 16 * i)));
        return sequences;
    }

    private static IOException invalid() { return new IOException("INVALID_FRAME"); }
    private static int stream(int stream) throws IOException { return bounded(stream, 1, 2); }
    private static int bounded(int n, int min, int max) throws IOException {
        if (n < min || n > max) throw invalid();
        return n;
    }
    private static long positive(long n) throws IOException {
        if (n < 1 || n > MAX_INTEGER) throw invalid();
        return n;
    }
    private static String hex(byte[] bytes, int start, int size) {
        char[] chars = new char[size * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < size; i++) {
            int value = bytes[start + i] & 255;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(chars);
    }
}
