package dev.podjs.runtime;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodSyncBlePanelTest {
    private Object field(Object target,String name) throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private void call(PodSyncBlePanel panel,String name) throws Exception {
        java.lang.reflect.Method method=PodSyncBlePanel.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(panel);
    }
    private AlertDialog choices(PodSyncBlePanel panel,BluetoothDevice device) throws Exception {
        java.lang.reflect.Method method=PodSyncBlePanel.class.getDeclaredMethod("showChoices",java.util.List.class);method.setAccessible(true);
        method.invoke(panel,Collections.singletonList(new PodBleDiscovery.Entry(device,-50)));
        return (AlertDialog)field(panel,"choices");
    }
    @Test public void selectionIsExplicitAndCancelledOrClosedListsCannotRestoreCandidate() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodServices services=new PodServices(activity,event->{});AtomicReference<PodSyncBlePanel> panel=new AtomicReference<>();
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();assertNotNull(adapter);
        BluetoothDevice device=adapter.getRemoteDevice("02:50:4F:44:00:01");
        try {
            instrumentation.runOnMainSync(()->{try {
                panel.set(new PodSyncBlePanel(activity,services,new LinearLayout(activity),()->true,()->"peer"));
                AlertDialog dialog=choices(panel.get(),device);ListView list=dialog.getListView();
                list.performItemClick(null,0,0);
                assertEquals(device,field(panel.get(),"selected"));
                assertTrue(((TextView)field(panel.get(),"status")).getText().toString().contains("后点击连接"));
                assertNull(field(services,"installedSync"));
                AlertDialog cancelled=choices(panel.get(),device);ListView stale=cancelled.getListView();
                call(panel.get(),"cancelScan");assertNull(field(panel.get(),"selected"));
                stale.performItemClick(null,0,0);
                assertNull("Cancelled list restored a candidate",field(panel.get(),"selected"));
                AlertDialog closing=choices(panel.get(),device);ListView late=closing.getListView();
                panel.get().close();late.performItemClick(null,0,0);
                assertNull(field(panel.get(),"selected"));assertFalse(closing.isShowing());
            }catch(Exception error){throw new RuntimeException(error);}});
        } finally {instrumentation.runOnMainSync(()->{if(panel.get()!=null)panel.get().close();activity.finish();});services.close();}
    }
}
