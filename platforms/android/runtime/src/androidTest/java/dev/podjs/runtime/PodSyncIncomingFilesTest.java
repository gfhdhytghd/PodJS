package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncIncomingFilesTest {
    @Test public void fileEventsPageDurableOffersAndDoNotGrantConsent() throws Exception {
        String app=app();
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app); PodSyncClient client=new PodSyncClient(context(),app,"watch")) {
            for(int n=0;n<65;n++) files.offer("phone",manifest(String.format(java.util.Locale.ROOT,"item-%03d",n),new byte[0]));
            PodSyncServices denied=new PodSyncServices(client,java.util.Collections.emptySet(),null);
            assertEquals(0,denied.fileEvents().length());
            PodSyncServices services=new PodSyncServices(client,java.util.Collections.singleton("companion.sync.file"),null);
            assertEquals(64,services.fileEvents().length());
            JSONArray last=services.fileEvents(); assertEquals(1,last.length());
            assertEquals("item-064",last.getJSONObject(0).getJSONObject("value").getString("transferId"));
            assertEquals(64,services.fileEvents().length()); assertEquals(65,files.pendingConsent().size());
            services.execute("sync.files.accept",new JSONObject().put("transferId","item-064"),new android.os.CancellationSignal());
            assertEquals("accepted",files.status("phone","item-064").phase);
        }
    }
    @Test public void serviceStatusDoesNotAcceptOfferAndReportsDurableTailBytesBeforeFinish() throws Exception {
        String app=app(); byte[] data=new byte[65539];
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app);
            PodSyncClient client=new PodSyncClient(context(),app,"watch")) {
            files.offer("phone",manifest("progress",data));
            PodSyncServices services=new PodSyncServices(client,java.util.Collections.singleton("companion.sync.file"),null);
            JSONObject args=new JSONObject().put("transferId","progress");
            try { services.execute("sync.files.accept",args,new android.os.CancellationSignal()); fail("Unseen file accepted"); }
            catch(IllegalArgumentException expected) { }
            JSONObject offered=(JSONObject)services.execute("sync.files.status",args,new android.os.CancellationSignal());
            assertEquals("offered",offered.getString("state")); assertFalse(offered.getBoolean("progressKnown"));
            assertEquals("offered",files.status("phone","progress").phase);
            services.execute("sync.files.accept",args,new android.os.CancellationSignal()); files.chunk("phone","progress",0,new byte[65536]);
            JSONObject partial=(JSONObject)services.execute("sync.files.status",args,new android.os.CancellationSignal());
            assertEquals(65536,partial.getLong("receivedBytes")); assertEquals("transferring",partial.getString("state"));
            files.chunk("phone","progress",1,new byte[3]);
            assertEquals("transferring",client.fileServiceStatus("progress").getString("state"));
            files.finish("phone","progress"); JSONObject complete=client.fileServiceStatus("progress");
            assertEquals(65539,complete.getLong("receivedBytes")); assertEquals("complete",complete.getString("state"));
            try { services.execute("sync.files.cancel",args,new android.os.CancellationSignal()); fail("Completed file removed by cancel"); }
            catch(IllegalStateException expected) { }
            assertTrue(files.completedFile("phone","progress").isFile());
            files.offer("phone",manifest("cancel",new byte[0])); JSONObject cancelArgs=new JSONObject().put("transferId","cancel");
            services.execute("sync.files.status",cancelArgs,new android.os.CancellationSignal());
            services.execute("sync.files.cancel",cancelArgs,new android.os.CancellationSignal());
            assertEquals("cancelled",files.status("phone","cancel").phase);
        }
    }
    @Test public void pendingConsentSurvivesRestartWithoutAcceptingOrLeakingApps() throws Exception {
        String app=app();
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app)) {
            files.offer("z-phone",manifest("later",new byte[0]));
            files.offer("a-phone",manifest("first",new byte[0]));
            files.offer("a-phone",manifest("cancelled",new byte[0]));
            files.cancel("a-phone","cancelled");
        }
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app);
            PodSyncIncomingFiles other=new PodSyncIncomingFiles(context(),app())) {
            java.util.List<PodSyncIncomingFiles.Offer> pending=files.pendingConsent();
            assertEquals(2,pending.size()); assertEquals("a-phone",pending.get(0).peerId);
            assertEquals("first",pending.get(0).transferId);
            assertTrue(other.pendingConsent().isEmpty());
            pending.get(0).manifest.put("size",123);
            assertEquals(0,files.pendingConsent().get(0).manifest.getLong("size"));
            try { pending.clear(); fail("Mutable listing"); } catch(UnsupportedOperationException expected) { }
            assertFalse(new File(context().getNoBackupFilesDir(),"podjs-sync/app-"+hash(app.getBytes(StandardCharsets.UTF_8))).exists());
            files.accept("a-phone","first");
            assertEquals(1,files.pendingConsent().size());
            assertEquals("z-phone",files.pendingConsent().get(0).peerId);
            files.cancel("a-phone","first"); files.cancel("z-phone","later");
            assertTrue(files.pendingConsent().isEmpty());
        }
    }
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private String app() { return "dev.podjs."+UUID.randomUUID(); }
    private String hash(byte[] bytes) throws Exception { StringBuilder value=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format(java.util.Locale.ROOT,"%02x",b & 255)); return value.toString(); }
    private JSONObject manifest(String id, byte[] data) throws Exception {
        JSONArray hashes=new JSONArray(); for(int offset=0;offset<data.length;offset+=65536) hashes.put(hash(Arrays.copyOfRange(data,offset,Math.min(data.length,offset+65536))));
        return new JSONObject().put("transfer_id",id).put("size",data.length).put("sha256",hash(data)).put("chunk_hashes",hashes).put("mime","application/octet-stream");
    }
    private File database(String app) throws Exception { return new File(context().getNoBackupFilesDir(),"podjs-file-offers/app-"+hash(app.getBytes(StandardCharsets.UTF_8))+".sqlite"); }
    @Test public void offerDoesNotAcceptAndVerifiedChunksSurviveReopen() throws Exception {
        String app=app(), peer="phone.device:1"; byte[] data=new byte[65539]; for(int n=0;n<data.length;n++) data[n]=(byte)(n*37);
        JSONObject manifest=manifest("transfer",data);
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app)) {
            assertEquals("offered",files.offer(peer,manifest).phase);
            assertFalse(new File(context().getNoBackupFilesDir(),"podjs-sync/app-"+hash(app.getBytes(StandardCharsets.UTF_8))).exists());
            try { files.chunk(peer,"transfer",0,Arrays.copyOf(data,65536)); fail("Unaccepted chunk written"); } catch(java.io.IOException expected) { }
            try { files.finish(peer,"transfer"); fail("Unaccepted file finished"); } catch(java.io.IOException expected) { }
            assertEquals("accepted",files.accept(peer,"transfer").phase);
            files.chunk(peer,"transfer",0,Arrays.copyOf(data,65536));
            try { files.chunk(peer,"transfer",1,new byte[]{0,0,0}); fail("Corrupt chunk accepted"); } catch(java.io.IOException expected) { }
        }
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app)) {
            assertEquals("accepted",files.status(peer,"transfer").phase); assertEquals("[1]",files.missing(peer,"transfer").toString());
            files.chunk(peer,"transfer",1,Arrays.copyOfRange(data,65536,data.length)); assertEquals("complete",files.finish(peer,"transfer").phase);
            assertArrayEquals(data,Files.readAllBytes(files.completedFile(peer,"transfer").toPath()));
            assertEquals("cancelled",files.cancel(peer,"transfer").phase);
        }
    }
    @Test public void peerConsentAndAppNamespacesAreIsolatedAndCancelledOffersDoNotRevive() throws Exception {
        String app=app(); JSONObject empty=manifest("same",new byte[0]);
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app); PodSyncIncomingFiles other=new PodSyncIncomingFiles(context(),app())) {
            files.offer("first",empty); files.offer("second",empty); files.accept("first","same");
            try { files.finish("second","same"); fail("Consent crossed peers"); } catch(java.io.IOException expected) { }
            try { other.status("first","same"); fail("State crossed apps"); } catch(java.io.IOException expected) { }
            files.cancel("first","same"); assertEquals("offered",files.status("second","same").phase);
            assertEquals("cancelled",files.offer("first",empty).phase);
            try { files.accept("first","same"); fail("Cancelled transfer revived"); } catch(java.io.IOException expected) { }
            try { files.offer("first",manifest("same",new byte[]{1})); fail("Transfer ID rebound"); } catch(java.io.IOException expected) { }
            files.accept("second","same"); assertEquals("complete",files.finish("second","same").phase); files.cancel("second","same");
        }
    }
    @Test public void interruptedAcceptanceAndCancellationRecoverWithoutRepeatingConsent() throws Exception {
        String app=app();
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app)) {
            files.offer("phone",manifest("empty",new byte[0]));
            try(SQLiteDatabase fault=SQLiteDatabase.openDatabase(database(app).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                fault.execSQL("CREATE TRIGGER reject_accept BEFORE UPDATE ON offers WHEN NEW.phase='accepted' BEGIN SELECT RAISE(ABORT,'injected acceptance failure'); END");
                try { files.accept("phone","empty"); fail("Acceptance commit failure ignored"); } catch(android.database.SQLException expected) { }
                assertEquals("accepting",files.status("phone","empty").phase);
            }
        }
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app); SQLiteDatabase fault=SQLiteDatabase.openDatabase(database(app).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals("accepting",files.status("phone","empty").phase); fault.execSQL("DROP TRIGGER reject_accept"); files.recover();
            assertEquals("accepted",files.status("phone","empty").phase); files.finish("phone","empty");
            fault.execSQL("CREATE TRIGGER reject_cancel BEFORE UPDATE ON offers WHEN NEW.phase='cancelled' BEGIN SELECT RAISE(ABORT,'injected cancellation failure'); END");
            try { files.cancel("phone","empty"); fail("Cancel commit failure ignored"); } catch(android.database.SQLException expected) { }
            assertEquals("cancelling",files.status("phone","empty").phase);
        }
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app); SQLiteDatabase fault=SQLiteDatabase.openDatabase(database(app).getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals("cancelling",files.status("phone","empty").phase); fault.execSQL("DROP TRIGGER reject_cancel"); files.recover(); files.recover();
            assertEquals("cancelled",files.status("phone","empty").phase);
        }
    }
    @Test public void manifestMutationAndUnknownFieldsCannotChangePersistedOffer() throws Exception {
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app())) {
            JSONObject value=manifest("id",new byte[]{1}); files.offer("phone",value); value.put("size",99);
            assertEquals(1,files.status("phone","id").manifest.getInt("size"));
            JSONObject extra=manifest("other",new byte[0]).put("path","/unexpected");
            try { files.offer("phone",extra); fail("Unknown file path accepted"); } catch(IllegalArgumentException expected) { }
            try { files.status("phone","other"); fail("Invalid manifest persisted"); } catch(java.io.IOException expected) { }
        }
    }
    @Test public void reopenAllowsCancelAndRecoveryReleasesQuotaBeforeWaitingAcceptance() throws Exception {
        String app=app(); String digest=new String(new char[64]).replace('\0','0'); JSONArray chunks=new JSONArray();
        for(int n=0;n<256;n++) chunks.put(digest);
        JSONObject large=new JSONObject().put("transfer_id","large").put("size",16L*1024*1024).put("sha256",digest).put("chunk_hashes",chunks).put("mime","application/octet-stream");
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app)) {
            files.offer("waiting",large); files.offer("occupying",large); files.accept("occupying","large");
            try { files.accept("waiting","large"); fail("Quota exceeded"); } catch(java.io.IOException expected) { }
            assertEquals("accepting",files.status("waiting","large").phase);
            File lock=new File(context().getNoBackupFilesDir(),"podjs-sync/app-"+hash(app.getBytes(StandardCharsets.UTF_8))+"/@quota.lock");
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(lock,"rw"); java.nio.channels.FileLock held=file.getChannel().lock()) {
                try { files.cancel("occupying","large"); fail("Busy native lock ignored"); } catch(java.io.IOException expected) { }
                assertEquals("cancelling",files.status("occupying","large").phase);
            }
        }
        try(PodSyncIncomingFiles files=new PodSyncIncomingFiles(context(),app)) {
            files.recover(); assertEquals("cancelled",files.status("occupying","large").phase); assertEquals("accepted",files.status("waiting","large").phase);
            files.cancel("waiting","large");
        }
    }
}
