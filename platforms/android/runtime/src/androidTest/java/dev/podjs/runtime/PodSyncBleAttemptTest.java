package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.runtime.PodBleStream;
import dev.podjs.runtime.PodSyncSession;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncBleAttemptTest {
    @Test public void wrongPairingKeyOnCandidateRadioNeverHandsOffSession() throws Exception {
        String app=UUID.randomUUID().toString(); PodBleStream[] wire=new PodBleStream[2];
        wire[0]=new PodBleStream(23,value->wire[1].receive(value),()->wire[1].close());
        wire[1]=new PodBleStream(23,value->wire[0].receive(value),()->wire[0].close());
        try(PodSyncClient phone=new PodSyncClient(endpoint(),app,"phone"); PodSyncClient watch=new PodSyncClient(endpoint(),app,"watch");
            PodSyncBleAttempt client=new PodSyncBleAttempt(phone,"watch",CHANNELS,2000); PodSyncBleAttempt server=new PodSyncBleAttempt(watch,"phone",CHANNELS,2000)) {
            phone.authorizeAfterUserApproval("watch",PodSyncSession.newChallenge());
            watch.authorizeAfterUserApproval("phone",PodSyncSession.newChallenge());
            FutureTask<PodSyncClient.Session> accepting=run(()->server.open(link(wire[1]),false));
            failed(run(()->client.open(link(wire[0]),true))); failed(accepting);
            assertTrue(wire[0].isClosed()); assertTrue(wire[1].isClosed());
            assertTrue(watch.receivedMessages(System.currentTimeMillis()).isEmpty()); assertNull(watch.getState("unauthorized"));
        } finally { wire[0].close(); wire[1].close(); }
    }
    private static final String[] CHANNELS={"state","message","file","ack"};
    private static Context endpoint() {
        String id=UUID.randomUUID().toString();
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public File getNoBackupFilesDir() {
                File root=new File(super.getNoBackupFilesDir(),id);
                if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Endpoint unavailable"); return root;
            }
        };
    }
    private static PodSyncBleAttempt.Link link(PodBleStream stream) {
        return new PodSyncBleAttempt.Link() { public PodBleStream open() { return stream; } public void close() throws IOException { stream.close(); } };
    }
    private static <T> FutureTask<T> run(java.util.concurrent.Callable<T> task) {
        FutureTask<T> future=new FutureTask<>(task); new Thread(future,"ble-attempt-test").start(); return future;
    }
    private static IOException failed(FutureTask<?> future) throws Exception {
        try { future.get(5,TimeUnit.SECONDS); fail("Unexpected success"); return null; }
        catch(java.util.concurrent.ExecutionException error) { assertTrue(error.getCause() instanceof IOException); return (IOException)error.getCause(); }
    }
    @Test public void authenticatedHandoffSurvivesAttemptCloseAtSmallMtu() throws Exception {
        String app=UUID.randomUUID().toString(); PodBleStream[] wire=new PodBleStream[2];
        wire[0]=new PodBleStream(23,value -> wire[1].receive(value),() -> wire[1].close());
        wire[1]=new PodBleStream(23,value -> wire[0].receive(value),() -> wire[0].close());
        try(PodSyncClient phone=new PodSyncClient(endpoint(),app,"phone"); PodSyncClient watch=new PodSyncClient(endpoint(),app,"watch");
            PodSyncBleAttempt client=new PodSyncBleAttempt(phone,"watch",CHANNELS,5000); PodSyncBleAttempt server=new PodSyncBleAttempt(watch,"phone",CHANNELS,5000)) {
            byte[] key=PodSyncSession.newChallenge(); phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            FutureTask<PodSyncClient.Session> accepting=run(() -> server.open(link(wire[1]),false));
            try(PodSyncClient.Session p=client.open(link(wire[0]),true); PodSyncClient.Session w=accepting.get(5,TimeUnit.SECONDS)) {
                client.close(); server.close(); assertFalse(wire[0].isClosed());
                phone.setState("ble","authenticated"); assertTrue(p.sendState());
                assertEquals("state.applied",w.receiveDeferred(1)); assertEquals("state.ack",p.receiveDeferred(1));
                assertEquals("authenticated",watch.getState("ble"));
            }
            assertTrue(wire[0].isClosed()); assertTrue(wire[1].isClosed());
        } finally { wire[0].close(); wire[1].close(); }
    }
    @Test public void deadlineClosesSilentAuthenticatedTransport() throws Exception {
        try(PodSyncClient sdk=new PodSyncClient(endpoint(),UUID.randomUUID().toString(),"phone");
            PodBleStream silent=new PodBleStream(23,value -> {},() -> {});
            PodSyncBleAttempt attempt=new PodSyncBleAttempt(sdk,"watch",CHANNELS,250)) {
            sdk.authorizeAfterUserApproval("watch",PodSyncSession.newChallenge());
            IOException error=failed(run(() -> attempt.open(link(silent),true)));
            assertTrue(error.getMessage().contains("deadline")); assertTrue(silent.isClosed()); assertTrue(attempt.isClosed());
        }
    }
    @Test public void cancellationReleasesOpeningAndRejectsLateStream() throws Exception {
        CountDownLatch opening=new CountDownLatch(1),release=new CountDownLatch(1);
        try(PodSyncClient sdk=new PodSyncClient(endpoint(),UUID.randomUUID().toString(),"phone");
            PodBleStream stream=new PodBleStream(23,value -> {},() -> {});
            PodSyncBleAttempt attempt=new PodSyncBleAttempt(sdk,"watch",CHANNELS,3000)) {
            PodSyncBleAttempt.Link delayed=new PodSyncBleAttempt.Link() {
                public PodBleStream open() throws IOException {
                    opening.countDown(); try { if(!release.await(3,TimeUnit.SECONDS)) throw new IOException("stalled"); }
                    catch(InterruptedException error) { throw new IOException(error); } return stream;
                }
                public void close() { release.countDown(); }
            };
            FutureTask<PodSyncClient.Session> pending=run(() -> attempt.open(delayed,true));
            assertTrue(opening.await(1,TimeUnit.SECONDS)); attempt.close(); failed(pending); assertTrue(stream.isClosed());
        }
    }
    @Test public void unknownPeerFailsAndClosesOpenedStream() throws Exception {
        try(PodSyncClient sdk=new PodSyncClient(endpoint(),UUID.randomUUID().toString(),"phone");
            PodBleStream stream=new PodBleStream(23,value -> {},() -> {});
            PodSyncBleAttempt attempt=new PodSyncBleAttempt(sdk,"watch",CHANNELS,1000)) {
            failed(run(() -> attempt.open(link(stream),true))); assertTrue(stream.isClosed()); assertTrue(attempt.isClosed());
        }
    }
}
