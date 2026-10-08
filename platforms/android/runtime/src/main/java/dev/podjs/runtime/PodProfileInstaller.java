package dev.podjs.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.profileinstaller.ProfileInstallerInitializer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Automatic profile writes start only after successful storage admission, including no-source apps. */
final class PodProfileInstaller {
    private static final Set<String> scheduled=ConcurrentHashMap.newKeySet();
    private PodProfileInstaller() {}
    static void afterAdmission(Context context) {
        Context app=context.getApplicationContext();
        String key=app.getApplicationInfo().dataDir;
        if(!scheduled.add(key))return;
        // Initializer.create requires Choreographer on the main looper. Its normal first-frame
        // and 5-6 second background delay is preserved, now starting after ACTIVE publication.
        if(!new Handler(Looper.getMainLooper()).post(() -> {
            try {new ProfileInstallerInitializer().create(app);}
            catch(RuntimeException unavailable){scheduled.remove(key);}
        }))scheduled.remove(key);
    }
}
