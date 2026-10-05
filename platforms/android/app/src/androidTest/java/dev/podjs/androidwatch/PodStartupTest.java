package dev.podjs.androidwatch;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.Configuration;
import androidx.work.WorkManager;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Run only in an isolated fixture host, never against the formal package. */
@RunWith(AndroidJUnit4.class)
public final class PodStartupTest {
    @Test public void defaultWorkManagerIsAvailableOnFirstWorkerCall() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("dev.podjs.androidwatch", context.getPackageName());
        assertTrue(context instanceof Configuration.Provider);
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
            PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
        boolean startupProvider = false;
        for (ProviderInfo provider : info.providers) {
            if (!"androidx.startup.InitializationProvider".equals(provider.name)) continue;
            startupProvider = true;
            assertNotNull(provider.metaData);
            assertFalse(provider.metaData.containsKey("androidx.work.WorkManagerInitializer"));
            assertTrue(provider.metaData.containsKey("androidx.profileinstaller.ProfileInstallerInitializer"));
        }
        assertTrue(startupProvider);
        FutureTask<WorkManager> first = new FutureTask<>(() -> WorkManager.getInstance(context));
        Thread thread = new Thread(first, "podjs-startup-test");
        thread.start();
        WorkManager work = first.get(10, TimeUnit.SECONDS);
        assertNotNull(work);
        assertSame(work, WorkManager.getInstance(context));
    }
}
