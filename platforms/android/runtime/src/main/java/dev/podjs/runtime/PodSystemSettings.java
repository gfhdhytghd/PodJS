package dev.podjs.runtime;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Watch vendors may expose only wireless settings or the settings root. */
final class PodSystemSettings {
    static void openWifi(Context context) {
        String[] actions = {Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String action : actions) {
            try {
                context.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // Try the next system-owned settings entry point.
            }
        }
        throw new ActivityNotFoundException("Network settings unavailable");
    }
}
