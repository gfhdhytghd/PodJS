package dev.podjs.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodMigrationProgressTest {
    Context fixture()throws Exception {
        File root=Files.createTempDirectory("progress-fixture-").toFile();
        new File(root,"files/package-takeover").mkdirs();
        Properties journal=new Properties();journal.setProperty("phase","COPYING");journal.setProperty("generation","g-fixture");
        PodTakeoverCoordinator.atomic(new File(root,"files/package-takeover/journal"),journal);
        return new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo(super.getApplicationInfo());info.dataDir=root.toString();return info;}
        };
    }
    Object reporter(Context context)throws Exception {
        Constructor<?> constructor=Class.forName("dev.podjs.runtime.PodTakeover$ProgressReporter").getDeclaredConstructors()[0];constructor.setAccessible(true);return constructor.newInstance(context,null);
    }
    void call(Object target,String name,Class<?> arg,Object value)throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,arg);method.setAccessible(true);method.invoke(target,value);
    }
    void reset(Object target)throws Exception {Method m=target.getClass().getDeclaredMethod("reset");m.setAccessible(true);m.invoke(target);}
    @Test public void restartRestoresMonotonicProgress()throws Exception {
        Context c=fixture();Object reporter=reporter(c);
        call(reporter,"phase",PodTakeoverCoordinator.Phase.class,PodTakeoverCoordinator.Phase.VERIFYING);
        int before=PodTakeover.status(c).getInt("percent");assertTrue(before>=80);
        Field states=PodTakeover.class.getDeclaredField("progressStates");states.setAccessible(true);((Map<?,?>)states.get(null)).clear();
        reset(reporter(c));assertTrue("restart moved progress backwards",PodTakeover.status(c).getInt("percent")>=before);
    }
    @Test public void fileReplayCountsCumulativeBytesOnce()throws Exception {
        Context c=fixture();Object reporter=reporter(c);reset(reporter);
        call(reporter,"fileCopied",long.class,100L);
        // Background admission constructs a new reporter without the Activity's
        // reset(). Revalidating an already-copied file is not another copy.
        reporter=reporter(c);call(reporter,"fileCopied",long.class,100L);
        assertEquals(100L,PodTakeover.status(c).getLong("copiedBytes"));
        call(reporter,"fileCopied",long.class,200L);
        assertEquals(200L,PodTakeover.status(c).getLong("copiedBytes"));
    }
    @Test public void safeCandidateRestartRetainsDisplayHighWater()throws Exception {
        Context c=fixture();Object reporter=reporter(c);
        call(reporter,"phase",PodTakeoverCoordinator.Phase.class,PodTakeoverCoordinator.Phase.SNAPSHOTTED);
        int before=PodTakeover.status(c).getInt("percent");
        File journal=new File(c.getApplicationInfo().dataDir,"files/package-takeover/journal");
        Properties p=PodTakeoverCoordinator.read(journal);p.setProperty("generation","g-retry");p.setProperty("phase","DETECTED");PodTakeoverCoordinator.atomic(journal,p);
        Field states=PodTakeover.class.getDeclaredField("progressStates");states.setAccessible(true);((Map<?,?>)states.get(null)).clear();
        reset(reporter(c));assertTrue(PodTakeover.status(c).getInt("percent")>=before);
    }
    @Test public void pausedCodesUseTheExistingRecoveryCopyAndRetry()throws Exception {
        try(org.robolectric.android.controller.ActivityController<android.app.Activity> activity=Robolectric.buildActivity(android.app.Activity.class).setup()) {
            PodTakeover.RecoveryController controller=new PodTakeover.RecoveryController(activity.get(),()->fail("failed migration admitted"));
            for(String code:new String[]{"migration_timeout","migration_memory_limit","migration_interrupted","migration_row_too_large","stage_failed","space_insufficient","user_locked","migration_admission_failed","migration_storage_invalid","verification_scratch_invalid"}) {
                controller.show(new org.json.JSONObject().put("phase","PAUSED").put("progressPhase","PAUSED").put("reason",code).put("percent",80));
                Object view=controller.view;
                assertEquals("迁移已暂停",label(view,"title").getText().toString());
                String detail=label(view,"detail").getText().toString();
                assertTrue(detail,detail.startsWith("原因：")&&detail.length()>3&&!detail.contains(code));
                assertEquals(code,label(view,"code").getText().toString());
                assertEquals(1,label(view,"code").getMaxLines());
                assertEquals(java.util.Set.of("migration_row_too_large","migration_storage_invalid","verification_scratch_invalid").contains(code)?"原数据未改动，\n请截图联系支持。":"已完成的部分会保留，可重试或重开应用继续。",label(view,"next").getText().toString());
                assertEquals("重试迁移",label(view,"retry").getText().toString());
                assertEquals(android.view.View.VISIBLE,label(view,"retry").getVisibility());
                assertTrue(label(view,"retry").isEnabled());
            }
        }
    }
    android.widget.TextView label(Object view,String name)throws Exception {
        Field field=view.getClass().getDeclaredField(name);field.setAccessible(true);return (android.widget.TextView)field.get(view);
    }
}
