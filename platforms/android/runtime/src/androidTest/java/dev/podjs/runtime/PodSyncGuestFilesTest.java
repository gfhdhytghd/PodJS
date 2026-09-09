package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncGuestFilesTest {
    @Test public void descriptorTraversalRejectsSymlinksAndKeepsOpenedFileAcrossRename() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=Files.createTempDirectory(context.getCacheDir().toPath(),"guest-files-").toFile();
        File directory=new File(root,"nested"); assertTrue(directory.mkdir()); File source=new File(directory,"source"); Files.write(source.toPath(),new byte[]{1,2,3});
        Files.createSymbolicLink(new File(root,"link").toPath(),directory.toPath());
        Files.createSymbolicLink(new File(directory,"link").toPath(),source.toPath());
        for(String path:new String[]{"../outside","/absolute","nested/../nested/source","link/source","nested/link","nested//source"}) {
            try(android.os.ParcelFileDescriptor.AutoCloseInputStream ignored=PodSyncGuestFiles.open(root,path)) { fail("Unsafe path accepted: "+path); } catch(Exception expected) { }
        }
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream opened=PodSyncGuestFiles.open(root,"nested/source")) {
            Files.move(source.toPath(),new File(directory,"old").toPath()); Files.write(source.toPath(),new byte[]{9});
            assertEquals(1,opened.read()); assertEquals(2,opened.read());
        }
        try(PodSyncClient client=new PodSyncClient(context,UUID.randomUUID().toString(),"watch")) {
            PodSyncServices services=new PodSyncServices(client,Collections.singleton("companion.sync.file"),null,root);
            JSONObject result=(JSONObject)services.execute("sync.files.offer",new JSONObject().put("peerId","phone").put("path","nested/old").put("mime","application/octet-stream"),new CancellationSignal());
            assertEquals(3,result.getLong("totalBytes")); assertEquals("offered",result.getString("state"));
            assertEquals(result.getString("transferId"),client.outgoingFiles("phone").get(0).transferId);
            assertTrue(source.exists());
            int snapshots=client.sourceSnapshots().size();
            try { services.execute("sync.files.offer",new JSONObject().put("peerId","watch").put("path","nested/old").put("mime","application/octet-stream"),new CancellationSignal()); fail("Self offer accepted"); }
            catch(IllegalArgumentException expected) { }
            assertEquals(snapshots,client.sourceSnapshots().size());
        }
    }
}
