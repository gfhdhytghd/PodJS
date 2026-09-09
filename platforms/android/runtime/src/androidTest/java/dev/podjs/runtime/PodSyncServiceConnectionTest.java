package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.CancellationSignal;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncServiceConnectionTest {
    @Test public void bleHostRejectsBackgroundAndClosedBeforeAccessingRadio() throws Exception {
        String app=UUID.randomUUID().toString(); Context context=endpoint(app);
        try(PodSyncClient client=new PodSyncClient(context,app,"watch")) {
            PodSyncServices services=new PodSyncServices(client,Collections.singleton("companion.sync.state"),null);
            PodSyncHostConnection host=new PodSyncHostConnection(client,services,Runnable::run);
            try {
                try { host.startBle(context,"phone",null,true,(phase,detail)->fail("Unexpected callback")); fail("Background BLE started"); }
                catch(java.io.IOException expected) { assertEquals("Host is not foreground",expected.getMessage()); }
                host.close(); host.setForeground(true);
                try { host.startBle(context,"phone",null,true,(phase,detail)->fail("Unexpected callback")); fail("Closed BLE started"); }
                catch(java.io.IOException expected) { assertEquals("Host is not foreground",expected.getMessage()); }
            } finally { host.close(); }
        }
    }
    @Test public void hostControllerRoutesStateOnlyAndRevokesOnBackground() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodSyncClient phone=new PodSyncClient(endpoint(app+"-hp"),app,"phone"); PodSyncClient watch=new PodSyncClient(endpoint(app+"-hw"),app,"watch")) {
            byte[] key=PodSyncSession.newChallenge(); phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            PodSyncServices services=new PodSyncServices(phone,Collections.singleton("companion.sync.state"),null);
            try(PodSyncHostConnection host=new PodSyncHostConnection(phone,services,Runnable::run);
                PodSyncLanAttempt server=new PodSyncLanAttempt(watch,"phone",new String[]{"state","ack"},5000)) {
                java.net.InetSocketAddress address=server.listen(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0));
                FutureTask<PodSyncClient.Session> accept=new FutureTask<>(server::accept); new Thread(accept,"host-lan-accept").start();
                try { host.start("watch",address,false,(phase,detail)->{}); fail("Background connection started"); } catch(java.io.IOException expected) { }
                java.util.concurrent.CountDownLatch connected=new java.util.concurrent.CountDownLatch(1);
                host.setForeground(true); host.start("watch",address,false,(phase,detail)->{if(phase.equals("connected"))connected.countDown();});
                try(PodSyncClient.Session session=accept.get(6,TimeUnit.SECONDS);
                    PodSyncForeground remote=new PodSyncForeground(session,30000,Runnable::run,reason->{})) {
                    assertTrue(connected.await(5,TimeUnit.SECONDS));
                    services.execute("sync.state.set",new JSONObject().put("key","host").put("value",true),new CancellationSignal());
                    services.execute("sync.state.synchronize",new JSONObject().put("peerId","watch"),new CancellationSignal());
                    assertEquals(true,watch.getState("host")); assertFalse(remote.isStopped());
                    host.setForeground(false);
                    try { services.execute("sync.state.synchronize",new JSONObject().put("peerId","watch"),new CancellationSignal()); fail("Background route retained"); }
                    catch(UnsupportedOperationException expected) { }
                }
            }
        }
    }
    @Test public void sharedRuntimeLanAttemptAuthenticatesWithoutPhoneSdkDependency() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodSyncClient phone=new PodSyncClient(endpoint(app+"-lp"),app,"phone"); PodSyncClient watch=new PodSyncClient(endpoint(app+"-lw"),app,"watch")) {
            byte[] key=PodSyncSession.newChallenge(); phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            try(PodSyncLanAttempt server=new PodSyncLanAttempt(watch,"phone",new String[]{"state","ack"},5000);
                PodSyncLanAttempt client=new PodSyncLanAttempt(phone,"watch",new String[]{"state","ack"},5000)) {
                java.net.InetSocketAddress address=server.listen(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0));
                FutureTask<PodSyncClient.Session> accept=new FutureTask<>(server::accept); new Thread(accept,"shared-lan-accept").start();
                try(PodSyncClient.Session p=client.connect(address); PodSyncClient.Session w=accept.get(6,TimeUnit.SECONDS)) {
                    phone.setState("runtime-lan",true); assertTrue(p.sendState());
                    assertEquals("state.applied",w.receiveDeferred(System.currentTimeMillis()));
                    assertEquals("state.ack",p.receiveDeferred(System.currentTimeMillis()));
                    assertEquals(true,watch.getState("runtime-lan")); assertTrue(phone.currentStateAcknowledged("watch"));
                }
            }
        }
    }
    private Context endpoint(String id) {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(base) { @Override public File getNoBackupFilesDir() {
            File root=new File(super.getNoBackupFilesDir(),id); root.mkdirs(); return root;
        }};
    }
    @Test public void serviceUsesOnlySameOwnerAuthenticatedForegroundAndReturnsDurableAck() throws Exception {
        String app=UUID.randomUUID().toString();
        try(PodSyncClient phone=new PodSyncClient(endpoint(app+"-p"),app,"phone");
            PodSyncClient watch=new PodSyncClient(endpoint(app+"-w"),app,"watch")) {
            byte[] key=PodSyncSession.newChallenge(); phone.authorizeAfterUserApproval("watch",key); watch.authorizeAfterUserApproval("phone",key);
            PodBleStream[] link=new PodBleStream[2];
            link[0]=new PodBleStream(64,value->link[1].receive(value),()->link[1].close());
            link[1]=new PodBleStream(64,value->link[0].receive(value),()->link[0].close());
            FutureTask<PodSyncClient.Session> accepting=new FutureTask<>(()->watch.open("phone",link[1].framed(),false,new String[]{"state","message","file","ack"}));
            new Thread(accepting,"service-sync-accept").start();
            try(PodSyncClient.Session p=phone.open("watch",link[0].framed(),true,new String[]{"state","message","file","ack"});
                PodSyncClient.Session w=accepting.get(10,TimeUnit.SECONDS);
                PodSyncForeground pf=new PodSyncForeground(p,30000,Runnable::run,reason->{});
                PodSyncForeground wf=new PodSyncForeground(w,30000,Runnable::run,reason->{})) {
                PodSyncServices services=new PodSyncServices(phone,Collections.singleton("companion.sync.state"),null);
                try { services.attachForeground(wf); fail("Foreign owner accepted"); } catch(SecurityException expected) { }
                services.attachForeground(pf);
                phone.setState("service",42);
                JSONObject result=(JSONObject)services.execute("sync.state.synchronize",new JSONObject().put("peerId","watch"),new CancellationSignal());
                assertTrue(result.has("appliedCursor")); assertTrue(phone.currentStateAcknowledged("watch")); assertEquals(42,watch.getState("service"));
                services.execute("sync.state.set",new JSONObject().put("key","service").put("value",43),new CancellationSignal());
                long stateDeadline=android.os.SystemClock.elapsedRealtime()+5000;
                while(!Integer.valueOf(43).equals(watch.getState("service"))) {
                    assertTrue("State service did not wake sender",android.os.SystemClock.elapsedRealtime()<stateDeadline); Thread.sleep(10);
                }
                PodSyncServices stateReceiver=new PodSyncServices(watch,Collections.singleton("companion.sync.state"),null);
                assertEquals(43,stateReceiver.stateEvents().getJSONObject(0).getJSONObject("value").getInt("value"));
                PodSyncServices sender=new PodSyncServices(phone,Collections.singleton("companion.sync.message"),null);
                PodSyncServices receiver=new PodSyncServices(watch,Collections.singleton("companion.sync.message"),null);
                sender.attachForeground(pf); receiver.attachForeground(wf);
                JSONObject message=new JSONObject().put("messageId","service-message").put("payload",new JSONObject().put("hello",true))
                    .put("ttlMs",60000).put("priority","normal");
                sender.execute("sync.messages.send",new JSONObject().put("peerId","watch").put("message",message),new CancellationSignal());
                long deadline=android.os.SystemClock.elapsedRealtime()+5000;
                while(watch.receivedMessages(System.currentTimeMillis()).isEmpty()) {
                    assertTrue("Service did not wake message sender",android.os.SystemClock.elapsedRealtime()<deadline); Thread.sleep(10);
                }
                assertEquals(1,phone.pendingMessages("watch",System.currentTimeMillis()).size());
                assertEquals(1,receiver.messageEvents(System.currentTimeMillis()).length());
                receiver.execute("sync.messages.ack",new JSONObject().put("peerId","phone").put("messageId","service-message"),new CancellationSignal());
                while(!phone.pendingMessages("watch",System.currentTimeMillis()).isEmpty()) {
                    assertTrue("Service ACK did not reach sender",android.os.SystemClock.elapsedRealtime()<deadline); Thread.sleep(10);
                }
                assertTrue(watch.receivedMessages(System.currentTimeMillis()).isEmpty());
                Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
                File root=java.nio.file.Files.createTempDirectory(base.getCacheDir().toPath(),"service-file-").toFile();
                java.nio.file.Files.write(new File(root,"source").toPath(),new byte[]{1,2,3});
                PodSyncServices fileSender=new PodSyncServices(phone,Collections.singleton("companion.sync.file"),null,root);
                File receivedRoot=java.nio.file.Files.createTempDirectory(base.getCacheDir().toPath(),"service-received-").toFile();
                PodSyncServices fileReceiver=new PodSyncServices(watch,Collections.singleton("companion.sync.file"),null,receivedRoot);
                fileSender.attachForeground(pf); fileReceiver.attachForeground(wf);
                JSONObject offered=(JSONObject)fileSender.execute("sync.files.offer",new JSONObject().put("peerId","watch").put("path","source").put("mime","application/octet-stream"),new CancellationSignal());
                String transfer=offered.getString("transferId"); deadline=android.os.SystemClock.elapsedRealtime()+8000;
                while(watch.pendingFileConsent().isEmpty()) {
                    assertTrue("File offer not received",android.os.SystemClock.elapsedRealtime()<deadline); Thread.sleep(10);
                }
                JSONObject fileArgs=new JSONObject().put("transferId",transfer);
                org.json.JSONArray fileEvents=fileReceiver.fileEvents();
                assertEquals(1,fileEvents.length()); assertEquals("sync.file.changed",fileEvents.getJSONObject(0).getString("t"));
                assertEquals(transfer,fileEvents.getJSONObject(0).getJSONObject("value").getString("transferId"));
                fileReceiver.execute("sync.files.accept",fileArgs,new CancellationSignal());
                while(!phone.outgoingFiles("watch").get(0).phase.equals("complete")) {
                    assertTrue("Accepted file did not resume automatically",android.os.SystemClock.elapsedRealtime()<deadline); Thread.sleep(10);
                }
                assertArrayEquals(new byte[]{1,2,3},java.nio.file.Files.readAllBytes(watch.completedIncomingFile("phone",transfer).toPath()));
                JSONObject saveArgs=new JSONObject().put("transferId",transfer).put("path","received.bin");
                File staging=new File(receivedRoot.getParentFile(),".podjs-sync-publish"); staging.mkdir();
                File stale=new File(staging,"save-"+UUID.randomUUID()); java.nio.file.Files.write(stale.toPath(),new byte[]{7});
                File unrelated=new File(staging,"keep-"+UUID.randomUUID()); java.nio.file.Files.write(unrelated.toPath(),new byte[]{8});
                try(java.io.RandomAccessFile lockFile=new java.io.RandomAccessFile(new File(staging,"lock"),"rw");
                    java.nio.channels.FileLock held=lockFile.getChannel().lock()) {
                    try { fileReceiver.execute("sync.files.save",saveArgs,new CancellationSignal()); fail("Active publication lock ignored"); }
                    catch(java.io.IOException expected) { }
                    assertTrue(stale.exists());
                }
                JSONObject saved=(JSONObject)fileReceiver.execute("sync.files.save",saveArgs,new CancellationSignal());
                assertFalse(stale.exists()); assertTrue(unrelated.exists());
                assertEquals("received.bin",saved.getString("path")); assertEquals(3,saved.getLong("size"));
                fileReceiver.execute("sync.files.save",saveArgs,new CancellationSignal());
                assertArrayEquals(new byte[]{1,2,3},java.nio.file.Files.readAllBytes(new File(receivedRoot,"received.bin").toPath()));
                java.nio.file.Files.write(new File(receivedRoot,"received.bin").toPath(),new byte[]{9});
                try { fileReceiver.execute("sync.files.save",saveArgs,new CancellationSignal()); fail("Different destination overwritten"); }
                catch(android.system.ErrnoException expected) { }
                assertArrayEquals(new byte[]{9},java.nio.file.Files.readAllBytes(new File(receivedRoot,"received.bin").toPath()));
                try { services.execute("sync.state.synchronize",new JSONObject().put("peerId","other"),new CancellationSignal()); fail("Wrong peer routed"); }
                catch(UnsupportedOperationException expected) { }
                services.detachForeground(pf);
                try { services.execute("sync.state.synchronize",new JSONObject().put("peerId","watch"),new CancellationSignal()); fail("Detached run routed"); }
                catch(UnsupportedOperationException expected) { }
                assertFalse(pf.isStopped());
            } finally { link[0].close(); link[1].close(); }
        }
    }
}
