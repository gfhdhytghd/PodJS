package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

public class PodSyncPairingStoreTest {
    private String digest(String value) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
            output.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return output.toString();
    }
    @Test public void keystoreCredentialPersistsIsBoundAndRevokes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String app = UUID.randomUUID().toString(); byte[] key = PodSyncSession.newChallenge();
        try (PodSyncPairingStore store = new PodSyncPairingStore(context,app)) {
            assertNull(store.load("phone","watch"));
            store.importAuthorized("phone","watch",key);
            assertArrayEquals(key,store.load("phone","watch"));
            assertNull(store.load("phone","other"));
            try { store.importAuthorized("phone","watch",key); fail("Implicit rotation allowed"); }
            catch (IOException expected) { }
            try (PodSyncPairingStore duplicate = new PodSyncPairingStore(context,app)) { fail("Concurrent writer accepted"); }
            catch (IOException expected) { }
        }
        File root = new File(context.getNoBackupFilesDir(),"podjs-pairing-" + digest(app));
        File credential = new File(root,digest("PodJS-pairing-v1\n" + app + "\nphone\nwatch") + ".key");
        byte[] encrypted = Files.readAllBytes(credential.toPath());
        assertEquals(60,encrypted.length);
        for (int i=0;i<=encrypted.length-key.length;i++)
            assertFalse(Arrays.equals(key,Arrays.copyOfRange(encrypted,i,i+key.length)));
        try (PodSyncPairingStore reopened = new PodSyncPairingStore(context,app)) {
            assertArrayEquals(key,reopened.load("phone","watch"));
            encrypted[encrypted.length-1] ^= 1; Files.write(credential.toPath(),encrypted);
            // User-approved process snapshots ignore external edits until restart.
            assertArrayEquals(key,reopened.load("phone","watch"));
            byte[] caller=reopened.load("phone","watch");caller[0]^=1;assertArrayEquals(key,reopened.load("phone","watch"));
            // Drop only this synthetic fixture's cache to exercise a cold read.
            java.lang.reflect.Field cache=PodSyncPairingStore.class.getDeclaredField("CACHED");cache.setAccessible(true);
            ((java.util.Map<?,?>)cache.get(null)).remove(root);
            try { reopened.load("phone","watch"); fail("Tampered cold credential accepted"); }
            catch (java.security.GeneralSecurityException expected) { }
            reopened.revoke("phone","watch"); assertNull(reopened.load("phone","watch"));
            reopened.importAuthorized("phone","watch",key);
            assertArrayEquals(key,reopened.load("phone","watch"));
            reopened.revoke("phone","watch");
        }
        Arrays.fill(key,(byte)0);
    }
}
