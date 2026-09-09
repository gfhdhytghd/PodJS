package dev.podjs.runtime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncOutgoingFilesTest {
    @Test public void progressCountsOnlyDurablePeerReceiptsIncludingShortFinalChunk() throws Exception {
        String app=UUID.randomUUID().toString(); PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(context(),app);
        File source=File.createTempFile("progress-",".bin",context().getCacheDir()); Files.write(source.toPath(),new byte[65539]);
        String id;
        try { id=snapshots.create(source,"application/octet-stream").getString("transfer_id"); } finally { Files.delete(source.toPath()); }
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app); PodSyncOutgoingFiles files=new PodSyncOutgoingFiles(queue,snapshots)) {
            assertFalse(files.start("watch",id).progressKnown);
            PodSyncFileRequests.Request offer=files.advance("watch",false); queue.receive("watch",offer.messageId,reply(offer,"accepted"));
            PodSyncFileRequests.Request missing=files.advance("watch",false);
            JSONObject response=reply(missing,"accepted"); response.getJSONObject("value").put("missing",new org.json.JSONArray().put(0).put(1));
            queue.receive("watch",missing.messageId,response);
            PodSyncFileRequests.Request first=files.advance("watch",false);
            assertTrue(files.status("watch",id).progressKnown); assertEquals(0,files.status("watch",id).acknowledgedBytes);
            queue.receive("watch",first.messageId,reply(first,"accepted"));
            PodSyncFileRequests.Request last=files.advance("watch",false);
            assertEquals(65536,files.status("watch",id).acknowledgedBytes);
            queue.receive("watch",last.messageId,reply(last,"accepted")); files.advance("watch",false);
            assertEquals(65539,files.status("watch",id).acknowledgedBytes); assertEquals("finish",files.status("watch",id).phase);
        }
    }
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private String snapshot(PodSyncFileSnapshots snapshots) throws Exception {
        File source=File.createTempFile("transfer-",".bin",context().getCacheDir()); Files.write(source.toPath(),new byte[]{1,2,3});
        try { return snapshots.create(source,"text/plain").getString("transfer_id"); } finally { Files.delete(source.toPath()); }
    }
    private JSONObject reply(PodSyncFileRequests.Request request,String phase) throws Exception {
        StringBuilder hash=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(request.payload)) hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new JSONObject().put("version",1).put("type","reply").put("request_sha256",hash.toString()).put("value",new JSONObject().put("phase",phase));
    }
    @Test public void reopenRetainsExactRequestAndSuccessorFailureRollsBackConsumedReply() throws Exception {
        String app=UUID.randomUUID().toString(); PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(context(),app); String id=snapshot(snapshots); PodSyncFileRequests.Request request;
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app); PodSyncOutgoingFiles files=new PodSyncOutgoingFiles(queue,snapshots)) {
            files.start("watch",id); request=files.advance("watch",false);
            queue.receive("watch",request.messageId,reply(request,"accepted"));
        }
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app); PodSyncOutgoingFiles files=new PodSyncOutgoingFiles(queue,snapshots);
            SQLiteDatabase fault=SQLiteDatabase.openDatabase(new File(context().getNoBackupFilesDir(),"podjs-file-requests/"+app+".sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals(request.messageId,files.status("watch",id).requestId);
            assertEquals(id,files.list("watch").get(0).transferId); assertTrue(files.list("other").isEmpty());
            fault.execSQL("CREATE TRIGGER reject_next BEFORE INSERT ON requests BEGIN SELECT RAISE(ABORT,'injected'); END");
            try { files.advance("watch",false); fail("Successor write failure ignored"); } catch(android.database.SQLException expected) { }
            assertEquals("offer",files.status("watch",id).phase); assertNotNull(queue.get("watch",request.messageId).reply);
            fault.execSQL("DROP TRIGGER reject_next"); PodSyncFileRequests.Request next=files.advance("watch",false);
            assertEquals("missing",new JSONObject(new String(next.payload,java.nio.charset.StandardCharsets.UTF_8)).getString("method"));
            assertNull(queue.get("watch",request.messageId)); assertEquals("missing",files.status("watch",id).phase);
            files.cancel("watch",id); assertEquals(next.messageId,files.advance("watch",false).messageId);
            try { files.releaseSnapshot(id); fail("Active source discarded"); } catch(java.io.IOException expected) { }
        }
    }
    @Test public void cancelBeforeOfferIsLocalAndSnapshotReaderRetainsLeaseUntilClose() throws Exception {
        String app=UUID.randomUUID().toString(); PodSyncFileSnapshots snapshots=new PodSyncFileSnapshots(context(),app); String id=snapshot(snapshots);
        try(PodSyncFileSnapshots.Reader reader=snapshots.reader(id)) {
            assertArrayEquals(new byte[]{1,2,3},reader.chunk(0));
            try { snapshots.discard(id); fail("Live reader lease ignored"); } catch(java.io.IOException expected) { }
        }
        try(PodSyncFileRequests queue=new PodSyncFileRequests(context(),app); PodSyncOutgoingFiles files=new PodSyncOutgoingFiles(queue,snapshots)) {
            files.start("watch",id); files.cancel("watch",id); assertNull(files.advance("watch",false));
            assertNull(queue.next("watch")); assertEquals("cancelled",files.status("watch",id).phase); files.releaseSnapshot(id);
            assertTrue(snapshots.inventory().isEmpty()); assertEquals("cancelled",files.start("watch",id).phase);
        }
    }
}
