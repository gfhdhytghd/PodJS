package dev.podjs.runtime;

import android.content.ActivityNotFoundException;
import android.content.ContextWrapper;
import android.content.Intent;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class PodSystemSettingsTest {
    static class SettingsContext extends ContextWrapper {
        final List<String> attempted = new ArrayList<>();
        final List<String> missing;
        final boolean denyWifi;
        SettingsContext(boolean denyWifi, String... missing) {
            super(RuntimeEnvironment.getApplication());
            this.denyWifi = denyWifi;
            this.missing = Arrays.asList(missing);
        }
        @Override public void startActivity(Intent intent) {
            attempted.add(intent.getAction());
            assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            if (denyWifi && Settings.ACTION_WIFI_SETTINGS.equals(intent.getAction())) throw new SecurityException();
            if (missing.contains(intent.getAction())) throw new ActivityNotFoundException();
        }
    }
    @Test public void opensWifiDirectly() {
        SettingsContext context = new SettingsContext(false);
        PodSystemSettings.openWifi(context);
        assertEquals(Arrays.asList(Settings.ACTION_WIFI_SETTINGS), context.attempted);
    }
    @Test public void missingWifiFallsBackToWirelessSettings() {
        SettingsContext context = new SettingsContext(false, Settings.ACTION_WIFI_SETTINGS);
        PodSystemSettings.openWifi(context);
        assertEquals(Arrays.asList(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS), context.attempted);
    }
    @Test public void inaccessibleWifiAndMissingWirelessFallBackToSettingsRoot() {
        SettingsContext context = new SettingsContext(true, Settings.ACTION_WIRELESS_SETTINGS);
        PodSystemSettings.openWifi(context);
        assertEquals(Arrays.asList(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS), context.attempted);
    }
    @Test public void reportsWhenNoSettingsEntryPointCanLaunch() {
        SettingsContext context = new SettingsContext(false, Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS);
        assertThrows(ActivityNotFoundException.class, () -> PodSystemSettings.openWifi(context));
        assertEquals(3, context.attempted.size());
    }
}
