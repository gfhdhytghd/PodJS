package dev.podjs.runtime;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Host-only pairing credentials; never exposed through guest secure.* services.
 * Import only after authenticated user-authorized pairing. Revoke must also close
 * active connections: deleting stored credentials cannot erase a live session key.
 */
public final class PodSyncPairingStore implements AutoCloseable {
    private final File root;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    private final String appId;
    private boolean closed;
    String applicationId() { return appId; }

    public PodSyncPairingStore(Context context, String appId) throws IOException {
        PodTakeover.requireReady(context);
        validateId(appId); this.appId = appId;
        root = new File(context.getNoBackupFilesDir(),"podjs-pairing-" + digest(appId));
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Pairing store unavailable");
        lockFile = new RandomAccessFile(new File(root,"store.lock"),"rw");
        FileLock acquired;
        try {
            acquired = lockFile.getChannel().tryLock();
            if (acquired == null) throw new IOException("Pairing store already open");
        } catch (IOException | RuntimeException error) {
            lockFile.close(); throw new IOException("Pairing store unavailable",error);
        }
        lock = acquired;
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid identity");
    }
    private static String digest(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (byte b : digest) text.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            return text.toString();
        } catch (java.security.GeneralSecurityException error) { throw new IOException(error); }
    }
    private String identity(String local, String remote) throws IOException {
        if (closed) throw new IOException("Pairing store closed");
        validateId(local); validateId(remote);
        if (local.equals(remote)) throw new IllegalArgumentException("Identical peers");
        return "PodJS-pairing-v1\n" + appId + "\n" + local + "\n" + remote;
    }
    private static KeyStore keystore() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); return keys;
    }
    private String alias(String identity) throws IOException { return "podjs.pairing.v1." + digest(identity); }
    private AtomicFile file(String identity) throws IOException { return new AtomicFile(new File(root,digest(identity) + ".key")); }
    private static boolean exists(AtomicFile file) {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }

    /** No implicit rotation: revoke the existing pairing before importing a replacement. */
    public synchronized void importAuthorized(String local, String remote, byte[] pairingKey) throws Exception {
        String identity = identity(local,remote);
        if (pairingKey == null || pairingKey.length != 32 || Arrays.equals(pairingKey,new byte[32]))
            throw new IllegalArgumentException("Invalid pairing key");
        String alias = alias(identity); KeyStore keys = keystore(); AtomicFile file = file(identity);
        if (exists(file) || keys.containsAlias(alias)) throw new IOException("Pairing already exists; revoke before replacement");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        SecretKey wrappingKey = generator.generateKey();
        byte[] clear = pairingKey.clone(); FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,wrappingKey);
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(clear); byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IOException("Invalid Keystore IV");
            output = file.startWrite(); output.write(iv); output.write(ciphertext); file.finishWrite(output); output = null;
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            keys.deleteEntry(alias); throw error;
        } finally { Arrays.fill(clear,(byte)0); }
    }
    /** Returns a caller-owned key to erase after handshake; missing pairing returns null. */
    public synchronized byte[] load(String local, String remote) throws Exception {
        String identity = identity(local,remote); AtomicFile file = file(identity);
        if (!exists(file)) return null;
        byte[] encrypted;
        try (java.io.FileInputStream input = file.openRead()) {
            // Exactly 12-byte IV + 32-byte key + 16-byte GCM tag.
            if (input.getChannel().size() != 60) throw new IOException("Corrupt pairing credential");
            encrypted = new byte[60]; int offset = 0;
            while (offset < encrypted.length) { int n = input.read(encrypted,offset,encrypted.length-offset); if (n <= 0) throw new IOException("Truncated credential"); offset += n; }
        }
        KeyStore keys = keystore(); String alias = alias(identity);
        if (!keys.containsAlias(alias)) throw new IOException("Pairing wrapping key unavailable; pair again");
        SecretKey key = ((KeyStore.SecretKeyEntry)keys.getEntry(alias,null)).getSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(encrypted,12)));
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length));
    }
    public synchronized void revoke(String local, String remote) throws Exception {
        String identity = identity(local,remote);
        keystore().deleteEntry(alias(identity));
        AtomicFile file = file(identity); file.delete();
        if (exists(file)) throw new IOException("Credential file could not be removed");
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { lock.release(); } finally { lockFile.close(); }
    }
}
