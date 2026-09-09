package dev.podjs.companion;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.podjs.runtime.PodSyncSession;
import dev.podjs.runtime.PodSyncStream;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodCompanionTest {
    @Test public void sharedRuntimeOwnerReturnsExactEntriesAndRetainsCallerMessageIdentity() throws Exception {
        String app=UUID.randomUUID().toString();
        try(dev.podjs.runtime.PodSyncClient client=new dev.podjs.runtime.PodSyncClient(endpoint(app),app,"watch")) {
            org.json.JSONObject first=client.setStateEntry("note","one"), second=client.setStateEntry("note","two");
            assertEquals("one",first.getString("value")); assertEquals(first.getLong("counter")+1,second.getLong("counter"));
            org.json.JSONObject deleted=client.deleteStateEntry("note"); assertTrue(deleted.getBoolean("deleted"));
            assertEquals(second.getLong("counter")+1,deleted.getLong("counter"));
            long now=System.currentTimeMillis(), expires=now+60000;
            client.queueMessage("phone","stable-id",new byte[]{1},expires,false,now);
            client.queueMessage("phone","stable-id",new byte[]{1},expires,false,now);
            assertEquals(1,client.pendingMessages("phone",now).size());
            assertEquals("stable-id",client.pendingMessages("phone",now).get(0).messageId);
            try { client.queueMessage("phone","stable-id",new byte[]{2},expires,false,now); fail("ID content changed"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void unusedSnapshotsCanBeReleasedButActiveTransfersRemainProtected() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodCompanion sdk=new PodCompanion(endpoint(app),app,"phone")) {
            File source=File.createTempFile("unused-source-",".bin",endpoint(app).getCacheDir());
            try {
                Files.write(source.toPath(),new byte[]{1,2,3});
                String unused=sdk.snapshotFile(source,"application/octet-stream").getString("transfer_id");
                sdk.releaseSource(unused); assertTrue(sdk.sourceSnapshots().isEmpty());
                sdk.releaseSource(unused); assertTrue(sdk.sourceSnapshots().isEmpty()); // Idempotent after a lost cleanup reply.
                try { sdk.releaseSource("../escape"); fail("Invalid source identity accepted"); } catch(IllegalArgumentException expected) { }
                String active=sdk.snapshotFile(source,"application/octet-stream").getString("transfer_id"); sdk.offerFile("watch",active);
                try { sdk.releaseSource(active); fail("Active source was released"); } catch(java.io.IOException expected) { }
                assertEquals(active,sdk.sourceSnapshots().get(0));
                sdk.cancelOutgoingFile("watch",active); sdk.releaseSource(active); assertTrue(sdk.sourceSnapshots().isEmpty());
            } finally { Files.deleteIfExists(source.toPath()); }
        }
    }
    @Test public void messageObserversTrackOutboxAndBothAcknowledgementSides() throws Exception {
        try(Pair pair=new Pair()) {
            java.util.concurrent.atomic.AtomicInteger phone=new java.util.concurrent.atomic.AtomicInteger(), watch=new java.util.concurrent.atomic.AtomicInteger();
            try(AutoCloseable p=pair.phone.subscribeMessages(Runnable::run,phone::incrementAndGet);
                AutoCloseable w=pair.watch.subscribeMessages(Runnable::run,watch::incrementAndGet)) {
                assertEquals(1,phone.get()); assertEquals(1,watch.get());
                pair.phone.sendMessage("watch",new byte[]{1},100,false,1); assertEquals(2,phone.get());
                assertTrue(pair.p.sendMessage(1)); assertEquals("message.pending",pair.w.receiveDeferred(1)); assertEquals(2,watch.get());
                pair.w.ackMessage(pair.watch.receivedMessages(1).get(0),1); assertEquals(3,watch.get()); assertTrue(pair.watch.receivedMessages(1).isEmpty());
                assertEquals("message.ack",pair.p.receiveDeferred(1)); assertEquals(3,phone.get()); assertTrue(pair.phone.pendingMessages("watch",1).isEmpty());
                pair.phone.sendMessage("watch",new byte[]{2},100,false,1); assertTrue(pair.p.sendMessage(1)); assertEquals("message.pending",pair.w.receiveDeferred(1));
                int before=watch.get(); pair.watch.ackMessage(pair.watch.receivedMessages(1).get(0),1); assertEquals(before+1,watch.get());
            }
        }
    }
    private static Context endpoint(String id) {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(base) { @Override public File getNoBackupFilesDir() {
            File root=new File(super.getNoBackupFilesDir(),id); if(!root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Endpoint unavailable"); return root;
        } };
    }
    private static final class Pair implements AutoCloseable {
        final PodCompanion phone,watch;
        final PodCompanion.Session p,w;
        final Context pc;
        final Context wc;
        final String appId;
        Pair() throws Exception { this(0); }
        Pair(int bleMtu) throws Exception {
            String app=UUID.randomUUID().toString(); appId=app; pc=endpoint(app+"-phone"); wc=endpoint(app+"-watch");
            phone=new PodCompanion(pc,app,"phone"); watch=new PodCompanion(wc,app,"watch"); byte[] key=PodSyncSession.newChallenge();
            phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            if(bleMtu!=0) {
                dev.podjs.runtime.PodBleStream[] link=new dev.podjs.runtime.PodBleStream[2];
                link[0]=new dev.podjs.runtime.PodBleStream(bleMtu,value -> link[1].receive(value),() -> link[1].close());
                link[1]=new dev.podjs.runtime.PodBleStream(bleMtu,value -> link[0].receive(value),() -> link[0].close());
                FutureTask<PodCompanion.Session> accepting=new FutureTask<>(()->watch.open("phone",link[1].framed(),false,new String[]{"state","message","file","ack"}));
                new Thread(accepting,"companion-ble-accept").start();
                try {
                    p=phone.open("watch",link[0].framed(),true,new String[]{"state","message","file","ack"});
                    w=accepting.get(10,TimeUnit.SECONDS);
                } catch(Exception error) { link[0].close(); phone.close(); watch.close(); throw error; }
            } else try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()), server=listener.accept(); client.setSoTimeout(10000); server.setSoTimeout(10000);
                FutureTask<PodCompanion.Session> accepting=new FutureTask<>(()->watch.open("phone",new PodSyncStream(server),false,new String[]{"state","message","file","ack"}));
                new Thread(accepting,"companion-accept").start(); p=phone.open("watch",new PodSyncStream(client),true,new String[]{"state","message","file","ack"}); w=accepting.get(10,TimeUnit.SECONDS);
            }
        }
        @Override public void close() throws Exception { try { phone.close(); } finally { watch.close(); } }
    }
    @Test public void deferredMessagesRequireExplicitAckAndDoNotBlockOtherChannels() throws Exception {
        try(Pair pair=new Pair()) {
            java.util.concurrent.atomic.AtomicInteger notices=new java.util.concurrent.atomic.AtomicInteger();
            try(AutoCloseable subscription=pair.watch.subscribeMessages(Runnable::run,notices::incrementAndGet)) {
                assertEquals(1,notices.get());
                String id=pair.phone.sendMessage("watch",new byte[]{7},100,false,1); assertTrue(pair.p.sendMessage(1));
                assertEquals("message.pending",pair.w.receiveDeferred(1)); assertEquals(2,notices.get());
                assertEquals(1,pair.phone.pendingMessages("watch",1).size());
                pair.phone.setState("while-unacked",true); assertTrue(pair.p.sendState());
                assertEquals("state.applied",pair.w.receiveDeferred(1)); assertEquals("state.ack",pair.p.receiveDeferred(1));
                dev.podjs.runtime.PodSyncInbox.Message delivery=pair.watch.receivedMessages(1).get(0); assertEquals(id,delivery.messageId);
                try { pair.p.ackMessage(delivery,1); fail("ACK crossed peer identity"); } catch(java.io.IOException expected) { }
                delivery.payload[0]=8;
                try { pair.w.ackMessage(delivery,1); fail("Changed UI snapshot acknowledged"); } catch(java.io.IOException expected) { }
                assertEquals(1,pair.watch.receivedMessages(1).size());
                pair.w.ackMessage(pair.watch.receivedMessages(1).get(0),1);
                assertEquals("message.ack",pair.p.receiveDeferred(1)); assertTrue(pair.phone.pendingMessages("watch",1).isEmpty()); assertTrue(pair.watch.receivedMessages(1).isEmpty());
            }
        }
    }
    @Test public void pendingDeliverySurvivesSdkReopenAndSubscriptionsAreCoalescedAndCancellable() throws Exception {
        try(Pair pair=new Pair()) {
            java.util.ArrayList<Runnable> scheduled=new java.util.ArrayList<>(); java.util.concurrent.atomic.AtomicInteger notices=new java.util.concurrent.atomic.AtomicInteger();
            AutoCloseable subscription=pair.watch.subscribeMessages(scheduled::add,notices::incrementAndGet);
            String id=pair.phone.sendMessage("watch",new byte[]{6},100,false,1);
            for(int repeat=0;repeat<2;repeat++) { assertTrue(pair.p.sendMessage(1)); assertEquals("message.pending",pair.w.receiveDeferred(1)); }
            assertEquals(1,scheduled.size()); assertEquals(0,notices.get()); subscription.close(); scheduled.remove(0).run(); assertEquals(0,notices.get());
            pair.watch.close();
            try(PodCompanion reopened=new PodCompanion(pair.wc,pair.appId,"watch")) {
                try(AutoCloseable restored=reopened.subscribeMessages(Runnable::run,notices::incrementAndGet)) { assertEquals(1,notices.get()); }
                assertEquals(id,reopened.receivedMessages(1).get(0).messageId);
                reopened.ackMessage(reopened.receivedMessages(1).get(0),1); assertTrue(reopened.receivedMessages(1).isEmpty());
                // Offline local ACK does not pretend to have reached the peer.
                assertEquals(1,pair.phone.pendingMessages("watch",1).size());
            }
        }
    }
    private static void fileRound(Pair pair,boolean poll,String method) throws Exception {
        FutureTask<String> receive=new FutureTask<>(()->pair.w.receive(1,(p,i,b)->fail("Unexpected message")));
        new Thread(receive,"companion-file").start(); assertTrue(pair.p.sendFile(poll)); assertEquals("file."+method,receive.get(15,TimeUnit.SECONDS));
        assertEquals("file.reply",pair.p.receive(1,(p,i,b)->fail("Unexpected message")));
    }
    @Test public void bleFragmentsCarryAuthenticatedStateExplicitAckAndFile() throws Exception {
        for(int mtu:new int[]{23,247}) try(Pair pair=new Pair(mtu)) {
            pair.phone.setState("ble-mtu",mtu); assertTrue(pair.p.sendState());
            assertEquals("state.applied",pair.w.receiveDeferred(1)); assertEquals("state.ack",pair.p.receiveDeferred(1));
            assertEquals(mtu,((Number)pair.watch.getState("ble-mtu")).intValue());
            pair.phone.sendMessage("watch",new byte[]{3,2,1},100,false,1); assertTrue(pair.p.sendMessage(1));
            assertEquals("message.pending",pair.w.receiveDeferred(1)); assertEquals(1,pair.phone.pendingMessages("watch",1).size());
            pair.w.ackMessage(pair.watch.receivedMessages(1).get(0),1);
            assertEquals("message.ack",pair.p.receiveDeferred(1)); assertTrue(pair.phone.pendingMessages("watch",1).isEmpty());
            byte[] bytes=new byte[65539]; for(int i=0;i<bytes.length;i++) bytes[i]=(byte)(i*17);
            File source=File.createTempFile("ble-file-",".bin",pair.pc.getCacheDir());
            String id;
            try { Files.write(source.toPath(),bytes); id=pair.phone.snapshotFile(source,"application/octet-stream").getString("transfer_id"); }
            finally { Files.deleteIfExists(source.toPath()); }
            pair.phone.offerFile("watch",id); fileRound(pair,false,"offer"); pair.watch.acceptFile("phone",id);
            fileRound(pair,true,"status"); fileRound(pair,false,"missing");
            fileRound(pair,false,"chunk"); fileRound(pair,false,"chunk"); fileRound(pair,false,"finish");
            assertFalse(pair.p.sendFile(false)); // Consume the persisted finish reply into outgoing phase.
            assertEquals("complete",pair.phone.outgoingFiles("watch").get(0).phase);
            assertArrayEquals(bytes,Files.readAllBytes(pair.watch.completedIncomingFile("phone",id).toPath()));
            pair.phone.releaseSource(id); pair.watch.cancelIncomingFile("phone",id);
        }
    }
    @Test public void stateAndFileSubscriptionsAreScopedAndAcceptedFilesSurviveUiReopen() throws Exception {
        try(Pair pair=new Pair()) {
            java.util.concurrent.atomic.AtomicInteger state=new java.util.concurrent.atomic.AtomicInteger(), files=new java.util.concurrent.atomic.AtomicInteger(), messages=new java.util.concurrent.atomic.AtomicInteger();
            try(AutoCloseable stateWatch=pair.watch.subscribeState(Runnable::run,state::incrementAndGet);
                AutoCloseable fileWatch=pair.watch.subscribeFiles(Runnable::run,files::incrementAndGet);
                AutoCloseable messageWatch=pair.watch.subscribeMessages(Runnable::run,messages::incrementAndGet)) {
                assertEquals(1,state.get()); assertEquals(1,files.get()); assertEquals(1,messages.get());
                pair.phone.setState("remote",true); assertTrue(pair.p.sendState()); assertEquals("state.applied",pair.w.receiveDeferred(1)); assertEquals("state.ack",pair.p.receiveDeferred(1));
                assertEquals(2,state.get()); assertEquals(1,files.get()); assertEquals(1,messages.get());
                pair.watch.setState("local",1); pair.watch.deleteState("local"); assertEquals(4,state.get());
                try { pair.watch.setState("",1); fail("Invalid mutation accepted"); } catch(IllegalArgumentException expected) { }
                assertEquals(4,state.get());
                File source=File.createTempFile("observer-file-",".bin",pair.pc.getCacheDir()); Files.write(source.toPath(),new byte[]{1});
                String id=pair.phone.snapshotFile(source,"text/plain").getString("transfer_id"); Files.delete(source.toPath());
                pair.phone.offerFile("watch",id); fileRound(pair,false,"offer"); assertEquals(2,files.get()); assertEquals(4,state.get());
                pair.watch.acceptFile("phone",id); assertEquals(3,files.get()); assertTrue(pair.watch.pendingFileConsent().isEmpty());
                assertEquals("accepted",pair.watch.incomingFiles().get(0).phase);
                pair.watch.close();
                try(PodCompanion reopened=new PodCompanion(pair.wc,pair.appId,"watch"); AutoCloseable restored=reopened.subscribeFiles(Runnable::run,files::incrementAndGet)) {
                    assertEquals(4,files.get()); assertEquals(id,reopened.incomingFiles().get(0).transferId); assertEquals("accepted",reopened.incomingFiles().get(0).phase);
                    reopened.incomingFiles().get(0).manifest.put("size",123); assertEquals(1,reopened.incomingFiles().get(0).manifest.getLong("size"));
                    reopened.cancelIncomingFile("phone",id); assertEquals(5,files.get()); assertEquals("cancelled",reopened.incomingFiles().get(0).phase);
                }
            }
        }
    }
    @Test public void sdkTransfersStateMessagesAndFilesWithoutUiRuntime() throws Exception {
        try(Pair pair=new Pair()) {
            pair.phone.setState("title","SDK双向😀"); assertTrue(pair.p.sendState());
            assertEquals("state.applied",pair.w.receive(1,(p,i,b)->fail("Unexpected message"))); assertEquals("state.ack",pair.p.receive(1,(p,i,b)->fail("Unexpected message")));
            assertEquals("SDK双向😀",pair.watch.getState("title"));
            pair.watch.setState("watch-local",true); assertTrue(pair.w.sendState());
            assertEquals("state.applied",pair.p.receive(1,(p,i,b)->fail("Unexpected message"))); assertEquals("state.ack",pair.w.receive(1,(p,i,b)->fail("Unexpected message")));
            assertEquals(true,pair.phone.getState("watch-local"));
            String message=pair.phone.sendMessage("watch",new byte[]{1,2,3},100,false,1); assertTrue(pair.p.sendMessage(1));
            assertEquals("message.applied",pair.w.receive(1,(peer,id,payload)->{
                assertEquals(message,id); assertArrayEquals(new byte[]{1,2,3},payload);
                pair.watch.setState("last-message",id);
                try { pair.watch.close(); fail("Callback close must not upgrade a read lock"); } catch(java.io.IOException expected) { }
            }));
            assertEquals("message.ack",pair.p.receive(1,(p,i,b)->fail("Unexpected message"))); assertTrue(pair.phone.pendingMessages("watch",1).isEmpty());
            byte[] bytes=new byte[65539]; for(int n=0;n<bytes.length;n++) bytes[n]=(byte)(n*13);
            File source=File.createTempFile("sdk-file-",".bin",pair.pc.getCacheDir()); Files.write(source.toPath(),bytes);
            String id=pair.phone.snapshotFile(source,"application/octet-stream").getString("transfer_id"); Files.delete(source.toPath());
            pair.phone.offerFile("watch",id); fileRound(pair,false,"offer"); assertFalse(pair.p.sendFile(false));
            assertEquals(1,pair.watch.pendingFileConsent().size()); pair.watch.acceptFile("phone",id);
            fileRound(pair,true,"status"); fileRound(pair,false,"missing"); fileRound(pair,false,"chunk"); fileRound(pair,false,"chunk"); fileRound(pair,false,"finish");
            assertFalse(pair.p.sendFile(false)); assertEquals("complete",pair.phone.outgoingFiles("watch").get(0).phase);
            assertArrayEquals(bytes,Files.readAllBytes(pair.watch.completedIncomingFile("phone",id).toPath()));
            pair.phone.releaseSource(id); assertTrue(pair.phone.sourceSnapshots().isEmpty()); pair.watch.cancelIncomingFile("phone",id);
        }
    }
    @Test public void closeUnblocksReceiveBeforeClosingStoresAndRejectsFurtherWork() throws Exception {
        try(Pair pair=new Pair()) {
            CountDownLatch started=new CountDownLatch(1);
            FutureTask<String> receiving=new FutureTask<>(()->{ started.countDown(); return pair.p.receive(1,(p,i,b)->fail("Unexpected message")); });
            new Thread(receiving,"companion-blocked-receive").start(); assertTrue(started.await(5,TimeUnit.SECONDS));
            try { receiving.get(150,TimeUnit.MILLISECONDS); fail("Receive did not block"); } catch(TimeoutException expected) { }
            pair.phone.close();
            try { receiving.get(5,TimeUnit.SECONDS); fail("Closed receive succeeded"); } catch(java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof java.io.IOException); }
            try { pair.phone.setState("closed",true); fail("Closed SDK accepted write"); } catch(java.io.IOException expected) { }
        }
    }
    @Test public void offlineStateAndMessagesPersistAcrossSdkReopen() throws Exception {
        String app=UUID.randomUUID().toString(); Context context=endpoint(app); String id;
        try(PodCompanion sdk=new PodCompanion(context,app,"phone")) { sdk.setState("offline",42); id=sdk.sendMessage("watch",new byte[]{4},100,false,1); }
        try(PodCompanion sdk=new PodCompanion(context,app,"phone")) { assertEquals(42,sdk.getState("offline")); assertEquals(id,sdk.pendingMessages("watch",1).get(0).messageId); }
    }
    @Test public void failedBusinessCallbackStillNotifiesPersistedPendingInbox() throws Exception {
        try(Pair pair=new Pair()) {
            java.util.concurrent.atomic.AtomicInteger notices=new java.util.concurrent.atomic.AtomicInteger();
            try(AutoCloseable observer=pair.watch.subscribeMessages(Runnable::run,notices::incrementAndGet)) {
                String id=pair.phone.sendMessage("watch",new byte[]{1},100,false,1); assertTrue(pair.p.sendMessage(1));
                try { pair.w.receive(1,(p,i,b)->{ throw new java.io.IOException("Injected business failure"); }); fail("Callback failure ignored"); }
                catch(java.io.IOException expected) { }
                assertEquals(2,notices.get()); assertEquals(id,pair.watch.receivedMessages(1).get(0).messageId);
                assertEquals(id,pair.phone.pendingMessages("watch",1).get(0).messageId);
            }
        }
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.ready()) { if(System.nanoTime()>=end) fail("SDK condition timed out"); Thread.sleep(10); }
    }
    @Test public void boundedDriverAutomaticallyDrainsThreeChannelsButNeverAutoAcksMessages() throws Exception {
        try(Pair pair=new Pair();
            PodForegroundSync phone=new PodForegroundSync(pair.p,30000,Runnable::run,reason->{});
            PodForegroundSync watch=new PodForegroundSync(pair.w,30000,Runnable::run,reason->{})) {
            pair.phone.setState("automatic",true); for(int n=0;n<100;n++) phone.requestState();
            await(()->Boolean.TRUE.equals(pair.watch.getState("automatic")));
            assertTrue(phone.synchronizeState(5000,new android.os.CancellationSignal()).has("appliedCursor"));
            assertTrue(pair.phone.currentStateAcknowledged("watch"));
            long now=System.currentTimeMillis(); String message=pair.phone.sendMessage("watch",new byte[]{1},now+60000,false,now); phone.requestMessages();
            await(()->!pair.watch.receivedMessages(System.currentTimeMillis()).isEmpty());
            assertEquals(message,pair.phone.pendingMessages("watch",System.currentTimeMillis()).get(0).messageId);
            watch.ackMessage(pair.watch.receivedMessages(System.currentTimeMillis()).get(0));
            await(()->pair.phone.pendingMessages("watch",System.currentTimeMillis()).isEmpty());
            File source=File.createTempFile("driver-file-",".bin",pair.pc.getCacheDir()); byte[] bytes=new byte[65539]; for(int n=0;n<bytes.length;n++) bytes[n]=(byte)(n*29); Files.write(source.toPath(),bytes);
            String id=pair.phone.snapshotFile(source,"application/octet-stream").getString("transfer_id"); Files.delete(source.toPath()); pair.phone.offerFile("watch",id); phone.requestFiles(false);
            await(()->!pair.watch.pendingFileConsent().isEmpty()); pair.watch.acceptFile("phone",id); phone.requestFiles(true);
            await(()->pair.phone.outgoingFiles("watch").get(0).phase.equals("complete"));
            dev.podjs.runtime.PodSyncOutgoingFiles.Status progress=pair.phone.outgoingFiles("watch").get(0);
            assertTrue(progress.progressKnown); assertEquals(bytes.length,progress.totalBytes); assertEquals(bytes.length,progress.acknowledgedBytes);
            assertArrayEquals(bytes,Files.readAllBytes(pair.watch.completedIncomingFile("phone",id).toPath()));
            assertFalse(phone.isStopped()); assertFalse(watch.isStopped()); pair.phone.releaseSource(id); pair.watch.cancelIncomingFile("phone",id);
        }
    }
    @Test public void stateBarrierTimesOutWithoutPeerAckAndCancellationKeepsRunAlive() throws Exception {
        try(Pair pair=new Pair(); PodForegroundSync phone=new PodForegroundSync(pair.p,30000,Runnable::run,reason->{})) {
            pair.phone.setState("unacked",true);
            try { phone.synchronizeState(100,new android.os.CancellationSignal()); fail("Queued state counted as acknowledged"); }
            catch(java.net.SocketTimeoutException expected) { }
            assertFalse(pair.phone.currentStateAcknowledged("watch")); assertFalse(phone.isStopped());
            android.os.CancellationSignal cancel=new android.os.CancellationSignal(); cancel.cancel();
            try { phone.synchronizeState(5000,cancel); fail("Cancelled barrier succeeded"); }
            catch(android.os.OperationCanceledException expected) { }
            assertFalse(phone.isStopped());
        }
    }
    @Test public void driverDeadlineClosesOnceAndKeepsUnacknowledgedOutbox() throws Exception {
        try(Pair pair=new Pair()) {
            long now=System.currentTimeMillis(); String id=pair.phone.sendMessage("watch",new byte[]{1},now+60000,false,now);
            CountDownLatch stopped=new CountDownLatch(1); java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
            try(PodForegroundSync run=new PodForegroundSync(pair.p,200,Runnable::run,reason->{ callbacks.incrementAndGet(); stopped.countDown(); })) {
                assertTrue(stopped.await(5,TimeUnit.SECONDS)); assertEquals("deadline",run.stopReason()); assertTrue(run.isStopped());
                await(()->{
                    for(Thread thread:Thread.getAllStackTraces().keySet()) if(thread.isAlive() && thread.getName().startsWith("podjs-sync-")) return false;
                    return true;
                });
                assertEquals(id,pair.phone.pendingMessages("watch",System.currentTimeMillis()).get(0).messageId);
                run.close(); assertEquals(1,callbacks.get());
                try { run.requestFiles(true); fail("Stopped driver accepted work"); } catch(java.io.IOException expected) { }
                pair.phone.setState("still-offline",true); assertEquals(true,pair.phone.getState("still-offline"));
            }
        }
    }
}
