package dev.podjs.runtime;

import android.os.CancellationSignal;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Bounded sandbox file operations. Ranges refer to local files, never wire names. */
final class PodFileRanges {
    private static final int BLOCK = 65536;
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    static String hex(byte[] bytes) {
        char[] text = new char[bytes.length * 2];
        String alphabet = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            text[2 * i] = alphabet.charAt((bytes[i] & 255) >>> 4);
            text[2 * i + 1] = alphabet.charAt(bytes[i] & 15);
        }
        return new String(text);
    }

    static long number(JSONObject row, String key, long min, long max) throws Exception {
        Object value = row.get(key);
        if (!(value instanceof Number)) throw new IOException("INVALID_FRAME");
        double n = ((Number)value).doubleValue();
        if (n != Math.rint(n) || n < min || n > max) throw new IOException("INVALID_FRAME");
        return (long)n;
    }

    static void readExact(InputStream in, byte[] bytes, int length) throws IOException {
        for (int at = 0; at < length;) {
            int n = in.read(bytes, at, length - at);
            if (n < 1) throw new EOFException("EOF");
            at += n;
        }
    }

    static void copy(InputStream in, FileChannel out, byte[] bytes, int length) throws IOException {
        readExact(in, bytes, length);
        if (out != null) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
            while (buffer.hasRemaining()) out.write(buffer);
        }
    }

    private static final class Range {
        final String path;
        final long offset, count;
        Range(String path, long offset, long count) { this.path = path; this.offset = offset; this.count = count; }
    }

    interface RangeOpen { FileInputStream open(File root, String relative) throws Exception; }

    static JSONObject execute(File root, JSONObject args, CancellationSignal cancel) throws Exception {
        return execute(root, args, cancel, PodSyncGuestFiles::open);
    }

    static JSONObject execute(File root, JSONObject args, CancellationSignal cancel, java.util.function.LongConsumer progress) throws Exception {
        return execute(root, args, cancel, PodSyncGuestFiles::open, progress);
    }

    static JSONObject execute(File root, JSONObject args, CancellationSignal cancel, RangeOpen source) throws Exception {
        return execute(root, args, cancel, source, ignored -> {});
    }

    static JSONObject execute(File root, JSONObject args, CancellationSignal cancel, RangeOpen source,
                                      java.util.function.LongConsumer progress) throws Exception {
        JSONArray ranges = args.getJSONArray("ranges");
        if (ranges.length() < 1 || ranges.length() > 32) throw new IOException("INVALID_FRAME");
        int segmentBytes = args.optBoolean("index") ? (int)number(args, "segmentBytes", BLOCK, 16777216) : 0;
        if (segmentBytes % BLOCK != 0) throw new IOException("INVALID_FRAME");
        long expected = number(args, "size", 0, MAX_BYTES);
        if (segmentBytes > 0 && (expected + segmentBytes - 1) / segmentBytes > 32) throw new IOException("INVALID_FRAME");
        String sha = args.optString("sha256", "");
        String target = args.optString("target", "");
        File file = target.isEmpty() ? null : PodFileConverter.safe(root, target);
        if (file != null && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("file_not_regular");
        List<Range> checked = new ArrayList<>(ranges.length());
        long remaining = expected;
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject range = ranges.getJSONObject(i);
            long offset = number(range, "offset", 0, MAX_BYTES), count = number(range, "size", 0, MAX_BYTES);
            if (count > remaining || (segmentBytes > 0 && i != ranges.length() - 1 && count % BLOCK != 0))
                throw new IOException("INVALID_FRAME");
            String path = range.getString("path");
            cancel.throwIfCanceled();
            try (FileInputStream in = source.open(root, path)) {
                if (offset + count > in.getChannel().size())
                    throw new IOException("SOURCE_CHANGED");
            }
            checked.add(new Range(path, offset, count));
            remaining -= count;
        }
        if (remaining != 0) throw new IOException("HASH_MISMATCH");
        MessageDigest whole = MessageDigest.getInstance("SHA-256");
        MessageDigest segment = MessageDigest.getInstance("SHA-256");
        MessageDigest block = MessageDigest.getInstance("SHA-256");
        JSONArray prefix = args.optJSONArray("prefix");
        if (prefix != null) {
            if (prefix.length() > 65536 || segmentBytes != 0 || file != null) throw new IOException("INVALID_FRAME");
            for (int i = 0; i < prefix.length(); i++) {
                int value = prefix.getInt(i);
                if (value < 0 || value > 255) throw new IOException("INVALID_FRAME");
                whole.update((byte)value);
            }
        }
        JSONArray segments = new JSONArray(), chunks = new JSONArray();
        byte[] buffer = new byte[BLOCK];
        long total = 0, segmentSize = 0;
        Path temp = file == null ? null : Files.createTempFile(file.toPath().getParent(), ".pod-range-", ".tmp");
        try {
          JSONObject result;
          try (FileChannel out = temp == null ? null : FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            for (Range range : checked) {
                try (FileInputStream in = source.open(root, range.path)) {
                    FileChannel channel = in.getChannel();
                    if (range.offset + range.count > channel.size()) throw new IOException("SOURCE_CHANGED");
                    channel.position(range.offset);
                    for (long left = range.count; left > 0;) {
                        cancel.throwIfCanceled();
                        int length = (int)Math.min(BLOCK, left);
                        copy(in, out, buffer, length);
                        whole.update(buffer, 0, length);
                        total += length;
                        progress.accept(length);
                        if (segmentBytes > 0) {
                            block.update(buffer, 0, length);
                            chunks.put(hex(block.digest()));
                            segment.update(buffer, 0, length);
                            segmentSize += length;
                            if (segmentSize == segmentBytes || total == expected) {
                                segments.put(new JSONObject().put("size", segmentSize).put("sha256", hex(segment.digest())).put("chunkHashes", chunks));
                                chunks = new JSONArray();
                                segmentSize = 0;
                            }
                        }
                        left -= length;
                    }
                }
            }
            String digest = hex(whole.digest());
            if (total != expected || (!sha.isEmpty() && !sha.equals(digest))) throw new IOException("HASH_MISMATCH");
            cancel.throwIfCanceled();
            if (out != null) out.force(true);
            result = new JSONObject().put("size", total).put("sha256", digest).put("segments", segments);
          }
          cancel.throwIfCanceled();
          if (temp != null) {
              Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
              temp = null;
          }
          return result;
        } finally {
          if (temp != null) Files.deleteIfExists(temp);
        }
    }
}
