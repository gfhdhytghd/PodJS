package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.CancellationSignal;
import android.os.Looper;
import android.provider.Settings;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PodNetworkDebugNotificationsTest {
    @Test public void aDebugSettingNotifiesOnlyOptedInSubscribers() throws Exception {
        Context debug = new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
                return info;
            }
        };
        List<String> events = new ArrayList<>();
        JSONObject ordinary = new JSONObject().put("subscriptionId","ordinary");
        JSONObject internet = new JSONObject().put("subscriptionId","internet").put("observeDebugOverride",true);
        try (PodNetworkServices service = new PodNetworkServices(debug, events::add)) {
            service.execute("discovery.networkSubscribe",ordinary,new CancellationSignal());
            service.execute("discovery.networkSubscribe",internet,new CancellationSignal());
            shadowOf(Looper.getMainLooper()).idle();
            events.clear();

            debug.getContentResolver().notifyChange(Settings.Global.getUriFor(PodNetworkServices.DEBUG_INTERNET_SETTING), null);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(events.toString(), 1, events.size());
            JSONObject event = new JSONObject(events.get(0));
            assertEquals("network.debugOverrideChanged", event.getString("t"));
            assertEquals("internet", event.getString("subscriptionId"));

            service.execute("discovery.networkUnsubscribe",internet,new CancellationSignal());
            events.clear();
            debug.getContentResolver().notifyChange(Settings.Global.getUriFor(PodNetworkServices.DEBUG_INTERNET_SETTING), null);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(events.toString(), events.isEmpty());
        }
    }
}
