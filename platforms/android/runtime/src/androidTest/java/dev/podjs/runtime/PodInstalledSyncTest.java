package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodInstalledSyncTest {
    @Test public void manifestSelectsExactSyncCapabilitiesAndRejectsWrongTarget() throws Exception {
        JSONObject manifest=new JSONObject().put("schema",1).put("target","android-watch")
            .put("capabilities",new JSONArray().put("net.http").put("companion.sync.state").put("companion.sync.message.extra"));
        assertEquals(java.util.Collections.singleton("companion.sync.state"),PodInstalledSync.approvedCapabilities(manifest,"android-watch"));
        try { PodInstalledSync.approvedCapabilities(manifest,"other"); fail("Wrong target"); } catch(SecurityException expected) { }
        manifest.put("capabilities",new JSONArray().put(1));
        try { PodInstalledSync.approvedCapabilities(manifest,"android-watch"); fail("Coerced capability"); } catch(SecurityException expected) { }
    }
    @Test public void localIdentityPersistsAndDoesNotRotateCorruptState() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(base.getCacheDir(),"identity-test-"+UUID.randomUUID());
        Context isolated=new ContextWrapper(base) { @Override public File getNoBackupFilesDir() { return root; } };
        String first=PodInstalledSync.localIdentity(isolated);
        assertEquals(first,PodInstalledSync.localIdentity(isolated));
        assertTrue(first.startsWith("device-"));
        File identity=new File(root,"podjs-sync-owner/identity");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(identity)) { output.write(new byte[]{'x'}); }
        try { PodInstalledSync.localIdentity(isolated); fail("Corrupt identity rotated"); } catch(java.io.IOException expected) { }
    }
    @Test public void replacementWaitsForPriorPairingOwnerWithoutRemovingItsLock() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(base.getCacheDir(),"handoff-test-"+UUID.randomUUID());
        Context isolated=new ContextWrapper(base){@Override public File getNoBackupFilesDir(){return root;}};
        PodSyncPairingStore first=new PodSyncPairingStore(isolated,"handoff.test");
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch attempted=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<PodSyncPairingStore> replacement=worker.submit(()->PodInstalledSync.afterOwnerRelease(()->{attempted.countDown();return new PodSyncPairingStore(isolated,"handoff.test");},()->false));
        try {
            assertTrue(attempted.await(2,java.util.concurrent.TimeUnit.SECONDS));Thread.sleep(80);
            assertFalse("replacement bypassed active owner's lock",replacement.isDone());
            first.close();
            try(PodSyncPairingStore next=replacement.get(3,java.util.concurrent.TimeUnit.SECONDS)){assertEquals("handoff.test",next.applicationId());}
        } finally {first.close();worker.shutdownNow();}
    }
    @Test public void cancellationAndUnrelatedErrorsAreNotRetried() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try {PodInstalledSync.afterOwnerRelease(()->{calls.incrementAndGet();return null;},()->true);fail("cancelled open ran");}catch(java.io.IOException expected){}
        assertEquals(0,calls.get());
        java.io.IOException original=new java.io.IOException("invalid manifest");
        try {PodInstalledSync.afterOwnerRelease(()->{calls.incrementAndGet();throw original;},()->false);fail("error hidden");}catch(java.io.IOException expected){assertSame(original,expected);}
        assertEquals(1,calls.get());
    }
}
