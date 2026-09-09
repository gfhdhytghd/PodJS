package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the sync-apk fixture and actual MainActivity/native boot. No owner injection. */
public class InstalledSyncTest {
    private Object field(Object target,String name) {
        try {java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(Exception error){throw new AssertionError(error);}
    }
    private void click(Object panel,String label) {
        android.widget.LinearLayout content=(android.widget.LinearLayout)field(panel,"content");
        for(int i=0;i<content.getChildCount();i++) if(content.getChildAt(i) instanceof android.widget.Button) {
            android.widget.Button button=(android.widget.Button)content.getChildAt(i);
            if(label.contentEquals(button.getText())) {button.performClick();return;}
        }
        fail("Missing action: "+label);
    }
    private void awaitStatus(ActivityScenario<android.app.Activity> activity,Object panel,String text) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+7000;
        java.util.concurrent.atomic.AtomicReference<String> last=new java.util.concurrent.atomic.AtomicReference<>();
        do {
            activity.onActivity(host->last.set(((android.widget.TextView)field(panel,"status")).getText().toString()));
            if(last.get().contains(text))return;
            Thread.sleep(20);
        } while(android.os.SystemClock.elapsedRealtime()<deadline);
        fail("Expected "+text+"; received "+last.get());
    }
    private void pairAndConnect(ActivityScenario<android.app.Activity> activity,android.content.Context context,PodInstalledSync owner,Object panel) throws Exception {
        String peer="test-"+java.util.UUID.randomUUID();
        android.content.Context remote=new android.content.ContextWrapper(context) {
            @Override public java.io.File getNoBackupFilesDir() {
                java.io.File root=new java.io.File(super.getNoBackupFilesDir(),peer);root.mkdirs();return root;
            }
        };
        byte[] key=PodSyncSession.newChallenge();
        try(PodSyncClient phone=new PodSyncClient(remote,context.getPackageName(),peer)) {
            phone.authorizeAfterUserApproval(owner.client.localDeviceId(),key);
            StringBuilder hex=new StringBuilder();for(byte b:key)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            activity.onActivity(host->{
                ((android.widget.EditText)field(panel,"peer")).setText(peer);
                ((android.widget.EditText)field(panel,"key")).setText(hex.toString());click(panel,"批准配对");
                ((android.app.AlertDialog)field(panel,"confirmation")).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitStatus(activity,panel,"配对已保存");
            activity.onActivity(host->assertEquals("",((android.widget.EditText)field(panel,"key")).getText().toString()));
            try(PodSyncLanAttempt listener=new PodSyncLanAttempt(phone,owner.client.localDeviceId(),new String[]{"state","message","file","ack"},10000)) {
                java.net.InetSocketAddress address=listener.listen(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0));
                java.util.concurrent.FutureTask<PodSyncClient.Session> accept=new java.util.concurrent.FutureTask<>(listener::accept);
                new Thread(accept,"installed-sync-peer").start();
                activity.onActivity(host->{
                    ((android.widget.EditText)field(panel,"address")).setText(address.getAddress().getHostAddress());
                    ((android.widget.EditText)field(panel,"port")).setText(Integer.toString(address.getPort()));click(panel,"连接对端");
                });
                try(PodSyncClient.Session session=accept.get(12,java.util.concurrent.TimeUnit.SECONDS);
                    PodSyncForeground run=new PodSyncForeground(session,30000,Runnable::run,reason->{})) {
                    awaitStatus(activity,panel,"已连接");
                    phone.setState("normal-ui-probe",peer);run.synchronizeState(5000,new android.os.CancellationSignal());
                    assertEquals(peer,owner.client.getState("normal-ui-probe"));
                    long deadline=android.os.SystemClock.elapsedRealtime()+5000;boolean received=false;
                    while(android.os.SystemClock.elapsedRealtime()<deadline) {
                        String saved=new String(java.nio.file.Files.readAllBytes(new java.io.File(context.getFilesDir(),"podjs/podjs-kv.json").toPath()),java.nio.charset.StandardCharsets.UTF_8);
                        if(JSONObject.quote(peer).equals(new JSONObject(saved).optString("sync-ui-received"))){received=true;break;}
                        Thread.sleep(20);
                    }
                    assertTrue("Guest did not receive authenticated state event",received);
                    activity.onActivity(host->click(panel,"断开连接"));awaitStatus(activity,panel,"已撤销连接");
                }
            }
        } finally {java.util.Arrays.fill(key,(byte)0);owner.connection.disconnect();owner.client.revoke(peer);}
    }
    @Test public void normalBootRunsGuestSyncAndOpensApprovedHostSettings() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        long started=System.currentTimeMillis();
        android.content.Intent intent=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull("Installed host has no launcher",intent);
        intent.setAction(PodRuntimeView.ACTION_COMPANION_SETTINGS);
        try(ActivityScenario<android.app.Activity> activity=ActivityScenario.launch(intent)) {
            long deadline=android.os.SystemClock.elapsedRealtime()+20000;
            boolean complete=false;
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                java.io.File file=new java.io.File(context.getFilesDir(),"podjs/podjs-kv.json");
                if(file.isFile()) {
                    String saved=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);
                    String value=new JSONObject(saved).optString("sync-boot-result");
                    assertFalse("Guest service failed: "+value,value.startsWith("error:"));
                    if(value.startsWith("{")) {
                        JSONObject result=new JSONObject(value);assertTrue(result.getBoolean("exists"));
                        JSONObject entry=result.getJSONObject("entry").getJSONObject("value");
                        if(entry.getLong("bootedAt")>=started) {
                            assertEquals("installed-guest",entry.getString("source"));assertEquals(42,entry.getInt("value"));
                            assertEquals("queued",result.getJSONObject("message").getString("state"));
                            assertEquals("offered",result.getJSONObject("offered").getString("state"));
                            assertEquals(5,result.getJSONObject("offered").getInt("totalBytes"));
                            assertEquals("cancelled",result.getJSONObject("cancelled").getString("state"));
                            assertEquals(result.getJSONObject("offered").getString("transferId"),result.getJSONObject("cancelled").getString("transferId"));
                            complete=true;break;
                        }
                    }
                }
                Thread.sleep(25);
            }
            assertTrue("Native boot guest sync did not finish",complete);
            java.util.concurrent.atomic.AtomicReference<PodInstalledSync> installed=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Object> settings=new java.util.concurrent.atomic.AtomicReference<>();
            activity.onActivity(host->{
                PodRuntimeView view=(PodRuntimeView)field(host,"pod");
                PodServices services=(PodServices)field(view,"services");
                PodInstalledSync owner=(PodInstalledSync)field(services,"installedSync");assertNotNull(owner);
                assertTrue(owner.client.localDeviceId().startsWith("device-"));
                assertEquals(4,owner.services.connectionChannels().length);
                Object panel=field(view,"companionSettings");assertNotNull(panel);assertEquals(true,field(panel,"ready"));
                assertTrue(((android.app.AlertDialog)field(panel,"dialog")).isShowing());
                installed.set(owner);settings.set(panel);
            });
            pairAndConnect(activity,context,installed.get(),settings.get());
        }
    }
}
