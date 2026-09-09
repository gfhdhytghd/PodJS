package dev.podjs.runtime;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fresh test APK, real PermissionController denial, no shell grants or radio use. */
public class InstalledSyncPermissionTest {
    private Object field(Object target,String name) {
        try {java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(Exception error){throw new AssertionError(error);}
    }
    private interface Condition {boolean ready() throws Exception;}
    private void until(Condition condition) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+15000;
        while(!condition.ready()){assertTrue("Permission lifecycle condition timed out",android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(40);}
    }
    private AccessibilityNodeInfo denyButton(AccessibilityNodeInfo node) {
        if(node==null)return null;
        String id=node.getViewIdResourceName();
        if(id!=null && id.endsWith(":id/permission_deny_button"))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=denyButton(node.getChild(i));if(child!=null)return child;}
        return null;
    }
    private boolean chargingOverlay(AccessibilityNodeInfo node) {
        if(node==null)return false;
        if("com.heytap.wearable.systemui:id/wic_prompt".equals(node.getViewIdResourceName()) && "连续充电时间过长".contentEquals(node.getText()==null?"":node.getText()))return true;
        for(int i=0;i<node.getChildCount();i++)if(chargingOverlay(node.getChild(i)))return true;
        return false;
    }
    @Test public void realPermissionDenialClosesPanelAndNeverStartsDiscovery() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        android.accessibilityservice.AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        android.content.Context context=instrumentation.getTargetContext();
        assertTrue("Start with BLE operation permissions denied",PodBlePermissions.missing(context,true,true).length>0);
        android.content.Intent intent=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());assertNotNull(intent);
        intent.setAction(PodRuntimeView.ACTION_COMPANION_SETTINGS);
        AtomicReference<PodRuntimeView> view=new AtomicReference<>();AtomicReference<Object> panel=new AtomicReference<>();
        try(ActivityScenario<android.app.Activity> scenario=ActivityScenario.launch(intent)) {
            until(()->{
                AtomicReference<Boolean> ready=new AtomicReference<>(false);
                scenario.onActivity(host->{
                    view.set((PodRuntimeView)field(host,"pod"));panel.set(field(view.get(),"companionSettings"));
                    ready.set(panel.get()!=null && Boolean.TRUE.equals(field(panel.get(),"ready")));
                });return ready.get();
            });
            Object ble=field(panel.get(),"ble");
            scenario.onActivity(host->{
                ((android.widget.EditText)field(panel.get(),"key")).setText("test-secret-must-clear");
                android.widget.LinearLayout content=(android.widget.LinearLayout)field(panel.get(),"content");
                for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i) instanceof android.widget.Button) {
                    android.widget.Button button=(android.widget.Button)content.getChildAt(i);
                    if("允许蓝牙权限".contentEquals(button.getText())){button.performClick();return;}
                }
                fail("Missing native permission action");
            });
            until(()->{
                AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
                assertFalse("System charging overlay blocks permission acceptance; reconnect the watch USB cable",chargingOverlay(root));
                return root!=null && root.getPackageName()!=null && root.getPackageName().toString().contains("permissioncontroller") && denyButton(root)!=null;
            });
            instrumentation.runOnMainSync(()->{
                assertEquals(true,field(panel.get(),"closed"));
                assertEquals("",((android.widget.EditText)field(panel.get(),"key")).getText().toString());
                assertEquals(true,field(ble,"closed"));assertNull(field(ble,"scan"));assertNull(field(ble,"selected"));
            });
            AccessibilityNodeInfo deny=denyButton(instrumentation.getUiAutomation().getRootInActiveWindow());assertNotNull(deny);
            assertTrue(deny.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            until(()->{
                AtomicReference<Boolean> resumed=new AtomicReference<>(false);
                instrumentation.runOnMainSync(()->resumed.set(Boolean.TRUE.equals(field(field(view.get(),"services"),"syncForeground"))));
                return resumed.get();
            });
            assertTrue(PodBlePermissions.missing(context,true,true).length>0);
            scenario.onActivity(host->{assertNull(field(view.get(),"companionSettings"));assertNull(field(ble,"scan"));});
        }
    }
}
