package dev.podjs.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Aggregate encrypted-map budget, independent of the bridge's 256 KiB chunks. */
final class SecureStoreLimits {
    static final int MAX_PLAIN_BYTES = 2 * 1024 * 1024;
    // Existing format: 12-byte nonce followed by AES/GCM ciphertext + 16-byte tag.
    static final int MAX_FILE_BYTES = MAX_PLAIN_BYTES + 28;

    /** Closed codes only. Never include keys, values, paths or exception text. */
    static final class Failure extends IOException {
        final String code;
        private Failure(String code) { super(code); this.code = code; }
    }
    static Failure quota() { return new Failure("secure_quota"); }
    static Failure tooLarge() { return new Failure("secure_store_too_large"); }
    static Failure readFailed() { return new Failure("secure_read_failed"); }

    static void validate(JSONObject values) throws Exception { new Budget().value(values, 0); }
    static byte[] encode(JSONObject values) throws Exception {
        // Count escaped UTF-8 before allocating the complete JSON or plaintext.
        validate(values);
        byte[] plain = values.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_PLAIN_BYTES) {
            java.util.Arrays.fill(plain, (byte) 0);
            throw quota();
        }
        return plain;
    }

    private static final class Budget {
        private int bytes;
        void add(int n) throws Failure {
            if (n > MAX_PLAIN_BYTES - bytes) throw quota();
            bytes += n;
        }
        void string(String text) throws Failure {
            add(2);
            // Each counting allocation is <= 4097 UTF-16 code units. Use this
            // platform's JSONObject escaping (Android and desktop differ).
            for (int start = 0; start < text.length();) {
                int end = Math.min(text.length(), start + 4096);
                if (end < text.length() && (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))
                        || text.charAt(end - 1) == '<' && text.charAt(end) == '/')) end++;
                add(JSONObject.quote(text.substring(start, end)).getBytes(StandardCharsets.UTF_8).length - 2);
                start = end;
            }
        }
        void value(Object value, int depth) throws Exception {
            if (depth > 128) throw new IllegalArgumentException("secure_value_invalid");
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                add(2); boolean first = true;
                for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                    String key = keys.next();
                    if (!first) add(1); first = false;
                    string(key); add(1); value(object.get(key), depth + 1);
                }
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value; add(2);
                for (int i = 0; i < array.length(); i++) {
                    if (i > 0) add(1); value(array.get(i), depth + 1);
                }
            } else if (value instanceof String) string((String) value);
            else if (value == null || value == JSONObject.NULL) add(4);
            else if (value instanceof Boolean) add((Boolean) value ? 4 : 5);
            else if (value instanceof Number) add(JSONObject.numberToString((Number) value).length());
            else throw new IllegalArgumentException("secure_value_invalid");
        }
    }
}
