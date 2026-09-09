package dev.podjs.runtime;

import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncSettingsDialogTest {
    private android.content.Context endpoint(android.content.Context base,String name) {
        java.io.File root=new java.io.File(base.getNoBackupFilesDir(),name);root.mkdirs();
        return new android.content.ContextWrapper(base) {
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public java.io.File getNoBackupFilesDir(){return root;}
            @Override public java.io.File getFilesDir(){java.io.File files=new java.io.File(root,"files");files.mkdirs();return files;}
        };
    }
    private void click(PodSyncSettingsDialog panel,String text) throws Exception {
        android.widget.LinearLayout content=(android.widget.LinearLayout)field(panel,"content");
        for(int i=0;i<content.getChildCount();i++) if(content.getChildAt(i) instanceof android.widget.Button) {
            android.widget.Button button=(android.widget.Button)content.getChildAt(i);
            if(text.contentEquals(button.getText())) {button.performClick();return;}
        }
        fail("Missing action: "+text);
    }
    private void awaitStatus(Instrumentation instrumentation,PodSyncSettingsDialog panel,String text) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+7000; AtomicReference<String> status=new AtomicReference<>();
        do {
            instrumentation.runOnMainSync(()->{try {status.set(((TextView)field(panel,"status")).getText().toString());}catch(Exception error){throw new RuntimeException(error);}});
            if(status.get().contains(text))return;
            Thread.sleep(10);
        } while(android.os.SystemClock.elapsedRealtime()<deadline);
        fail("Expected UI state: "+text+"; received: "+status.get());
    }
    @Test public void approvedOwnerPanelPairsAndConnectsThroughRealButtons() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation(); android.content.Context base=instrumentation.getTargetContext();
        String instance=java.util.UUID.randomUUID().toString();
        android.content.Context local=endpoint(base,instance+"-settings"), remote=endpoint(base,instance+"-remote");
        java.lang.reflect.Constructor<PodInstalledSync> constructor=PodInstalledSync.class.getDeclaredConstructor(android.content.Context.class,java.util.HashSet.class);constructor.setAccessible(true);
        PodInstalledSync owner=constructor.newInstance(local,new java.util.HashSet<>(java.util.Collections.singleton("companion.sync.state")));
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodServices services=new PodServices(activity,event->{}); AtomicReference<PodSyncSettingsDialog> panel=new AtomicReference<>();
        byte[] secret=PodSyncSession.newChallenge();
        try(PodSyncClient phone=new PodSyncClient(remote,local.getPackageName(),"test-phone")) {
            java.lang.reflect.Field installed=PodServices.class.getDeclaredField("installedSync");installed.setAccessible(true);installed.set(services,owner);services.syncForeground(true);
            phone.authorizeAfterUserApproval(owner.client.localDeviceId(),secret);
            StringBuilder hex=new StringBuilder();for(byte b:secret)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            instrumentation.runOnMainSync(()->{panel.set(new PodSyncSettingsDialog(activity,services));panel.get().show();});
            long deadline=android.os.SystemClock.elapsedRealtime()+5000; AtomicReference<Boolean> ready=new AtomicReference<>(false);
            while(!ready.get()) {instrumentation.runOnMainSync(()->{try {ready.set((Boolean)field(panel.get(),"ready"));}catch(Exception error){throw new RuntimeException(error);}});assertTrue(android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(10);}
            instrumentation.runOnMainSync(()->{try {
                ((EditText)field(panel.get(),"peer")).setText("test-phone");((EditText)field(panel.get(),"key")).setText(hex.toString());
                click(panel.get(),"批准配对");
                ((AlertDialog)field(panel.get(),"confirmation")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            }catch(Exception error){throw new RuntimeException(error);}});
            awaitStatus(instrumentation,panel.get(),"配对已保存"); assertEquals("",((EditText)field(panel.get(),"key")).getText().toString());
            try(PodSyncLanAttempt listener=new PodSyncLanAttempt(phone,owner.client.localDeviceId(),new String[]{"state","ack"},5000)) {
                java.net.InetSocketAddress address=listener.listen(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0));
                java.util.concurrent.FutureTask<PodSyncClient.Session> accept=new java.util.concurrent.FutureTask<>(listener::accept);new Thread(accept,"settings-test-accept").start();
                instrumentation.runOnMainSync(()->{try {
                    ((EditText)field(panel.get(),"address")).setText(address.getAddress().getHostAddress());((EditText)field(panel.get(),"port")).setText(Integer.toString(address.getPort()));click(panel.get(),"连接对端");
                }catch(Exception error){throw new RuntimeException(error);}});
                awaitStatus(instrumentation,panel.get(),"已连接");
                try(PodSyncClient.Session session=accept.get(6,java.util.concurrent.TimeUnit.SECONDS);
                    PodSyncForeground foreground=new PodSyncForeground(session,10000,Runnable::run,reason->{})) {
                    awaitStatus(instrumentation,panel.get(),"已连接");phone.setState("settings-ui",true);
                    foreground.synchronizeState(5000,new android.os.CancellationSignal());assertEquals(true,owner.client.getState("settings-ui"));
                    instrumentation.runOnMainSync(()->{try {click(panel.get(),"断开连接");}catch(Exception error){throw new RuntimeException(error);}});
                    awaitStatus(instrumentation,panel.get(),"已撤销连接");
                }
            }
        } finally {
            java.util.Arrays.fill(secret,(byte)0);
            instrumentation.runOnMainSync(()->{if(panel.get()!=null)panel.get().close();activity.finish();});services.close();
        }
    }
    private Object field(Object value,String name) throws Exception { java.lang.reflect.Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value); }
    @Test public void nativePanelFailsClosedWithoutOwnerAndClearsUnsavedSecret() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodServices services=new PodServices(activity,event->{}); AtomicReference<PodSyncSettingsDialog> panel=new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(()->{panel.set(new PodSyncSettingsDialog(activity,services));panel.get().show();});
            instrumentation.waitForIdleSync();
            AlertDialog dialog=(AlertDialog)field(panel.get(),"dialog"); EditText key=(EditText)field(panel.get(),"key");
            assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            assertFalse(key.isSaveEnabled());
            long deadline=android.os.SystemClock.elapsedRealtime()+3000;
            AtomicReference<String> status=new AtomicReference<>();
            while(true) {
                instrumentation.runOnMainSync(()->{try {status.set(((TextView)field(panel.get(),"status")).getText().toString());}catch(Exception error){throw new RuntimeException(error);}});
                if(status.get().contains("不能配对"))break;
                assertTrue("Missing unavailable-owner explanation",android.os.SystemClock.elapsedRealtime()<deadline); Thread.sleep(10);
            }
            instrumentation.runOnMainSync(()->{try {
                Object ble=field(panel.get(),"ble");
                for(String action:new String[]{"允许蓝牙权限","扫描蓝牙设备","连接所选蓝牙设备","等待蓝牙连接"}) {
                    click(panel.get(),action);
                    assertEquals("同步尚未启用。",((TextView)field(ble,"status")).getText().toString());
                    assertNull(field(ble,"scan"));assertNull(field(ble,"selected"));
                }
                key.setText("0000000000000000000000000000000000000000000000000000000000000000");panel.get().close();
                assertEquals(true,field(ble,"closed"));
            }catch(Exception error){throw new RuntimeException(error);}});
            assertEquals("",key.getText().toString()); assertFalse(dialog.isShowing());
        } finally {instrumentation.runOnMainSync(()->{if(panel.get()!=null)panel.get().close();activity.finish();});services.close();}
    }
}
