package dev.podjs.runtime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncFileIdentityTest {
    @Test public void resolverRejectsCrossPeerAndDirectionCollisionsIncludingTerminalRows() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext(); String app=UUID.randomUUID().toString();
        try(PodSyncClient client=new PodSyncClient(context,app,"watch"); PodSyncIncomingFiles incoming=new PodSyncIncomingFiles(context,app)) {
            File source=File.createTempFile("identity-",".bin",context.getCacheDir()); JSONObject manifest;
            try { manifest=client.snapshotFile(source,"application/octet-stream"); } finally { source.delete(); }
            String id=manifest.getString("transfer_id");
            try { client.resolveFileIdentity(id); fail("Snapshot was treated as transfer"); } catch(java.io.IOException expected) { }
            client.offerFile("phone",id);
            assertFalse(client.resolveFileIdentity(id).incoming); assertEquals("phone",client.resolveFileIdentity(id).peerId);
            client.cancelOutgoingFile("phone",id);
            incoming.offer("phone",manifest);
            try { client.resolveFileIdentity(id); fail("Direction collision ignored"); } catch(IllegalArgumentException expected) { }
            JSONObject other=new JSONObject(manifest.toString()).put("transfer_id","incoming"); incoming.offer("phone",other);
            assertTrue(client.resolveFileIdentity("incoming").incoming);
            incoming.offer("tablet",other);
            try { client.resolveFileIdentity("incoming"); fail("Peer collision ignored"); } catch(IllegalArgumentException expected) { }
        }
    }
}
