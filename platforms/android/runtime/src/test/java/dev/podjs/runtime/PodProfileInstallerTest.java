package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodProfileInstallerTest {
    @Test public void failedAdmissionNeverSchedulesButNoSourceAdmissionDoes()throws Exception {
        Path root=Files.createTempDirectory("profile-admission-");
        Context base=ApplicationProvider.getApplicationContext();
        Context context=new ContextWrapper(base) {
            @Override public Context getApplicationContext(){return this;}
            @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo(base.getApplicationInfo());info.dataDir=root.toString();return info;}
        };
        java.lang.reflect.Field field=PodProfileInstaller.class.getDeclaredField("scheduled");field.setAccessible(true);
        @SuppressWarnings("unchecked") Set<String> scheduled=(Set<String>)field.get(null);
        try {
            Files.createDirectories(root.resolve("databases"));
            Files.write(root.resolve("databases/watchrss.db"),new byte[]{1,2});
            assertFalse(PodTakeover.ready(context));
            assertFalse(scheduled.contains(root.toString()));
            assertFalse(Files.exists(root.resolve("files/profileInstalled")));
            // A distinct no-source sandbox is also a valid admission; ordinary PodJS apps
            // must still initialize profiles without creating a takeover journal.
            Path empty=root.resolve("empty");Files.createDirectory(empty);
            Context none=new ContextWrapper(base) {
                @Override public Context getApplicationContext(){return this;}
                @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo(base.getApplicationInfo());info.dataDir=empty.toString();return info;}
            };
            assertTrue(PodTakeover.ready(none));assertTrue(scheduled.contains(empty.toString()));
            assertFalse(Files.exists(empty.resolve("files/package-takeover")));
        }finally {
            scheduled.remove(root.toString());scheduled.remove(root.resolve("empty").toString());
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
