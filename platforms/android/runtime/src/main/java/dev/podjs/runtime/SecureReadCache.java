package dev.podjs.runtime;

import java.util.Arrays;

/** One host-private decrypted JSON value. Callers must read the actual encrypted
 * file under the store lock on every access; neither timestamps nor file sizes
 * are sufficient evidence that credentials have not changed. */
final class SecureReadCache {
    interface Decoder { String decode(byte[] bytes) throws Exception; }
    private byte[] encoded;
    private String json;
    String read(byte[] bytes, Decoder decoder) throws Exception {
        if (encoded != null && Arrays.equals(encoded, bytes)) return json;
        clear(); // A failed replacement must never fall back to old credentials.
        String value = decoder.decode(bytes);
        encoded = bytes.clone();
        json = value;
        return value;
    }
    void clear() {
        if (encoded != null) Arrays.fill(encoded, (byte) 0);
        encoded = null;
        json = null;
    }
}
