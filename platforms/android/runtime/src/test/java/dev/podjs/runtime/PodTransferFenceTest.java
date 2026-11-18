package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodTransferFenceTest {
    @Test public void guardedCommitAndRevocationAreDispatchedOnSeparateLanes() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        LinkedBlockingQueue<String> replies=new LinkedBlockingQueue<>();
        try(PodServices host=new PodServices(context,replies::add)) {
            PodByteStreamServices streams=ReflectionHelpers.getField(host,"byteStreams");
            String original=streams.attach(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),()->{});
            CancellationSignal signal=new CancellationSignal();
            String channel=streams.execute("stream.claim",channel(original),signal).getString("id");
            streams.execute("stream.framedOpen",channel(channel),signal);
            streams.execute("stream.framedEnable",channel(channel).put("lease",new JSONObject().put("epoch",1))
                    .put("stream",1).put("window",1),signal);
            JSONObject create=operation("CREATE TABLE IF NOT EXISTS transfer_guard_probe (k INTEGER PRIMARY KEY)");
            assertTrue(dispatch(host,replies,1,"sql.transactionFull",create).getBoolean("ok"));
            JSONObject insert=operation("INSERT INTO transfer_guard_probe VALUES(1)")
                    .put("channel",channel).put("epoch",1);
            JSONObject committed=dispatch(host,replies,2,"sql.transactionGuarded",insert);
            assertTrue(committed.toString(),committed.getBoolean("ok"));
            JSONObject revoked=dispatch(host,replies,3,"stream.framedRevoke",channel(channel));
            assertTrue(revoked.toString(),revoked.getBoolean("ok"));
            JSONObject stale=dispatch(host,replies,4,"sql.transactionGuarded",
                    operation("INSERT INTO transfer_guard_probe VALUES(2)").put("channel",channel).put("epoch",1));
            assertFalse(stale.getBoolean("ok"));
            assertEquals("STALE_EPOCH",stale.getString("message"));
            JSONObject row=dispatch(host,replies,5,"sql.query",new JSONObject()
                    .put("sql","SELECT count(*) AS n FROM transfer_guard_probe"));
            assertEquals(1,row.getJSONArray("value").getJSONObject(0).getInt("n"));
        }
    }

    @Test public void revokeCompletesAfterTheWinningCallAndRejectsEveryLaterCommit() throws Exception {
        PodTransferFence fence=new PodTransferFence();
        CountDownLatch entered=new CountDownLatch(1), finish=new CountDownLatch(1);
        Path file=Files.createTempFile("pod-transfer-fence",".bin");
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> pending=CompletableFuture.runAsync(()->{
                try{
                    fence.publish(()->{
                        try(FileOutputStream output=new FileOutputStream(file.toFile())){
                            output.write(7);
                            output.getFD().sync();
                            entered.countDown();
                            assertTrue(finish.await(5,TimeUnit.SECONDS));
                        }
                        return null;
                    });
                }catch(Exception error){throw new RuntimeException(error);}
            },worker);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            fence.revoke();
            assertFalse(fence.live());
            assertFalse(pending.isDone());
            finish.countDown();
            fence.awaitRevoked();
            pending.get(5,TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{7},Files.readAllBytes(file));
            try { fence.publish(()->{throw new AssertionError("Revoked publication ran");}); fail(); }
            catch(IOException stale){assertEquals("STALE_EPOCH",stale.getMessage());}
        } finally {
            finish.countDown();
            worker.shutdownNow();
            Files.deleteIfExists(file);
        }
    }

    private static JSONObject dispatch(PodServices host,LinkedBlockingQueue<String> replies,int id,String method,JSONObject args)throws Exception{
        host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id)
                .put("method",method).put("args",args));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            String line=replies.poll(10,TimeUnit.MILLISECONDS);
            if(line==null)continue;
            JSONObject value=new JSONObject(line);
            if(value.optInt("id")==id)return value;
        }
        throw new AssertionError("No reply "+id);
    }

    private static JSONObject channel(String channel)throws Exception{return new JSONObject().put("id",channel);}
    private static JSONObject operation(String sql)throws Exception{
        return new JSONObject().put("statements",new JSONArray().put(new JSONObject().put("sql",sql)));
    }
}
