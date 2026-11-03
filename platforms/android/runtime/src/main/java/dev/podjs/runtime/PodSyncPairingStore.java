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
import java.util.Map;
import java.util.HashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Host-only pairing credentials; never exposed through guest secure.* services.
 * Import only after authenticated user-authorized pairing. Revoke must also close
 * active connections: deleting stored credentials cannot erase a live session key.
 */
public final class PodSyncPairingStore implements AutoCloseable {
    private static final Map<File,Map<String,byte[]>> CACHED=new HashMap<>();
    private static final Map<File,Map<String,FailureBackoff>> FAILURES=new HashMap<>();
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
        SecureOperationStats.start();
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
        return ProcessKeyStore.get();
    }
    private String alias(String identity) throws IOException { return "podjs.pairing.v1." + digest(identity); }
    private AtomicFile file(String identity) throws IOException { return new AtomicFile(new File(root,digest(identity) + ".key")); }
    private static boolean exists(AtomicFile file) {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }

    /** No implicit rotation: revoke the existing pairing before importing a replacement. */
    public synchronized void importAuthorized(String local,String remote,byte[] pairingKey)throws Exception {
        tracedMutation("pairing.import",identity(local,remote),()->importUncached(local,remote,pairingKey));
    }
    @FunctionalInterface private interface Mutation {void run()throws Exception;}
    private void tracedMutation(String method,String identity,Mutation action)throws Exception {
        SecureOperationStats.Span span=SecureOperationStats.PROCESS.begin(method,0);boolean success=false;
        try{synchronized(CACHED){
            FailureBackoff failure=FAILURES.computeIfAbsent(root,f->new HashMap<>()).computeIfAbsent(method+identity,k->new FailureBackoff());failure.check();
            try{action.run();failure.success();success=true;}
            catch(Exception error){failure.failed(error);throw error;}
        }}finally{SecureOperationStats.PROCESS.end(span,success);}
    }
    private void importUncached(String local, String remote, byte[] pairingKey) throws Exception {
        String identity = identity(local,remote);
        if (pairingKey == null || pairingKey.length != 32 || Arrays.equals(pairingKey,new byte[32]))
            throw new IllegalArgumentException("Invalid pairing key");
        String alias = alias(identity); KeyStore keys = keystore(); AtomicFile file = file(identity);
        if (exists(file) || contains(keys,alias)) throw new IOException("Pairing already exists; revoke before replacement");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        SecureOperationStats.PROCESS.op("generate");SecretKey wrappingKey = generator.generateKey();
        byte[] clear = pairingKey.clone(); FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); SecureOperationStats.PROCESS.op("cipherInit");cipher.init(Cipher.ENCRYPT_MODE,wrappingKey);
            SecureOperationStats.PROCESS.op("cipherAad");cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            SecureOperationStats.PROCESS.op("cipherFinal");byte[] ciphertext = cipher.doFinal(clear); byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IOException("Invalid Keystore IV");
            output = file.startWrite(); output.write(iv); output.write(ciphertext); file.finishWrite(output); output = null;
            synchronized(CACHED){CACHED.computeIfAbsent(root,f->new HashMap<>()).put(identity,pairingKey.clone());FAILURES.computeIfAbsent(root,f->new HashMap<>()).remove(identity);}
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            SecureOperationStats.PROCESS.op("deleteEntry");keys.deleteEntry(alias); throw error;
        } finally { Arrays.fill(clear,(byte)0); }
    }
    /** Returns a caller-owned key to erase after handshake; missing pairing returns null. */
    public synchronized byte[] load(String local,String remote)throws Exception {
        SecureOperationStats.Span span=SecureOperationStats.PROCESS.begin("pairing.load",0);boolean success=false;
        try{byte[] result=loadCached(local,remote);success=true;return result;}finally{SecureOperationStats.PROCESS.end(span,success);}
    }
    private byte[] loadCached(String local, String remote) throws Exception {
        String identity = identity(local,remote);
        synchronized(CACHED){
            Map<String,byte[]> cache=CACHED.computeIfAbsent(root,f->new HashMap<>());
            if(cache.containsKey(identity)){byte[] value=cache.get(identity);return value==null?null:value.clone();}
            FailureBackoff failure=FAILURES.computeIfAbsent(root,f->new HashMap<>()).computeIfAbsent(identity,k->new FailureBackoff());failure.check();
            try{byte[] value=loadUncached(identity);cache.put(identity,value);failure.success();return value==null?null:value.clone();}
            catch(Exception error){failure.failed(error);throw error;}
        }
    }
    private byte[] loadUncached(String identity)throws Exception {
        AtomicFile file = file(identity);
        if (!exists(file)) return null;
        byte[] encrypted;
        try (java.io.FileInputStream input = file.openRead()) {
            // Exactly 12-byte IV + 32-byte key + 16-byte GCM tag.
            if (input.getChannel().size() != 60) throw new IOException("Corrupt pairing credential");
            encrypted = new byte[60]; int offset = 0;
            while (offset < encrypted.length) { int n = input.read(encrypted,offset,encrypted.length-offset); if (n <= 0) throw new IOException("Truncated credential"); offset += n; }
        }
        KeyStore keys = keystore(); String alias = alias(identity);
        SecureOperationStats.PROCESS.op("containsAlias");if (!keys.containsAlias(alias)) throw new IOException("Pairing wrapping key unavailable; pair again");
        SecureOperationStats.PROCESS.op("getEntry");SecretKey key = ((KeyStore.SecretKeyEntry)keys.getEntry(alias,null)).getSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecureOperationStats.PROCESS.op("cipherInit");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(encrypted,12)));
        SecureOperationStats.PROCESS.op("cipherAad");cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        SecureOperationStats.PROCESS.op("cipherFinal");return cipher.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length));
    }
    public synchronized void revoke(String local,String remote)throws Exception {
        tracedMutation("pairing.revoke",identity(local,remote),()->revokeUncached(local,remote));
    }
    private static boolean contains(KeyStore keys,String alias)throws Exception {SecureOperationStats.PROCESS.op("containsAlias");return keys.containsAlias(alias);}
    private void revokeUncached(String local, String remote) throws Exception {
        String identity = identity(local,remote);
        synchronized(CACHED){
            CACHED.computeIfAbsent(root,f->new HashMap<>()).remove(identity);
            FailureBackoff failure=FAILURES.computeIfAbsent(root,f->new HashMap<>()).computeIfAbsent(identity,k->new FailureBackoff());
            try{SecureOperationStats.PROCESS.op("deleteEntry");keystore().deleteEntry(alias(identity));AtomicFile file=file(identity);file.delete();if(exists(file))throw new IOException("Credential file could not be removed");CACHED.get(root).put(identity,null);failure.success();FAILURES.get(root).remove("pairing.import"+identity);}
            catch(Exception error){failure.failed(error);throw error;}
        }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { lock.release(); } finally { lockFile.close(); }
    }
}
