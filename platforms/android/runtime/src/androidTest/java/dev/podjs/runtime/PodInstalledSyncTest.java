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
}
