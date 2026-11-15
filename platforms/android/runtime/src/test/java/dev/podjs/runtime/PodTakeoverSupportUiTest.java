package dev.podjs.runtime;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk={30,35})
public class PodTakeoverSupportUiTest {
    @Test public void oldUnknownBlockerShowsOnlyOrdinaryMigrationProgress()throws Exception {
        try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()) {
            PodTakeover.RecoveryController controller=new PodTakeover.RecoveryController(activity.get(),()->{});
            JSONObject legacy=new JSONObject().put("phase","BLOCKED").put("reason","unknown_user_files");
            controller.running.set(true);controller.update(legacy);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertNull(controller.view);
            controller.show(legacy);
            assertEquals("正在检查数据",controller.view.title.getText().toString());
            assertEquals(View.GONE,controller.view.retry.getVisibility());assertEquals(View.GONE,controller.view.support.getVisibility());
            assertFalse(controller.view.currentStatus.toString().contains("unknown_user_files"));
            assertEquals("BLOCKED",legacy.getString("phase")); // UI projection never edits the durable journal.
        }
    }
    @Test public void knownBusinessFailureOpensConsentBeforeAdmission()throws Exception {
        try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()) {
            PodTakeover.RecoveryController controller=new PodTakeover.RecoveryController(activity.get(),()->fail("blocked runtime admitted"));
            controller.show(new JSONObject().put("phase","BLOCKED").put("reason","article_body_missing"));
            assertEquals(View.VISIBLE,controller.view.retry.getVisibility());assertEquals(View.VISIBLE,controller.view.support.getVisibility());
            controller.view.measure(View.MeasureSpec.makeMeasureSpec(192,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(192,View.MeasureSpec.EXACTLY));controller.view.layout(0,0,192,192);
            ScrollView scroll=(ScrollView)controller.view.getChildAt(1);scroll.fullScroll(View.FOCUS_DOWN);assertTrue(scroll.getScrollY()>0);
            assertTrue(controller.view.support.performClick());
            View page=((android.view.ViewGroup)activity.get().findViewById(android.R.id.content)).getChildAt(0);
            assertTrue(page instanceof PodTakeoverSupportAndroid.Page);
            PodTakeoverSupportAndroid.Page support=(PodTakeoverSupportAndroid.Page)page;assertFalse(support.consent.isChecked());assertFalse(support.upload.isEnabled());assertFalse(controller.finished);
        }
    }
    @Test public void everyFailureStacksBothButtonsWithAllCornersInsideTheRoundViewport()throws Exception {
        try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()) {
            for(int size:new int[]{192,233,320,390,466})for(String phase:new String[]{"PAUSED","BLOCKED"})for(String reason:new String[]{"store_unsupported_or_failed","stage_failed","migration_admission_failed","active_validation_failed"}) {
                PodTakeover.RecoveryView view=new PodTakeover.RecoveryView(activity.get());view.render(new JSONObject().put("phase",phase).put("reason",reason),false);
                view.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));view.layout(0,0,size,size);
                assertEquals("上传日志",view.support.getText().toString());assertEquals(1,view.title.getLineCount());
                assertEquals(View.VISIBLE,view.support.getVisibility());assertTrue(view.retry.getBottom()<view.support.getTop());
                for(View button:new View[]{view.retry,view.support})assertTrue("button corners outside circle "+size,PodRecoveryRoundLayout.rectInsideCircle(new PodRecoveryRoundLayout.Rect(button.getLeft(),button.getTop(),button.getWidth(),button.getHeight()),size,size,2));
            }
        }
    }
    @Test public void admittedReceiptManagementCannotStartAnotherUpload(){
        try(org.robolectric.android.controller.ActivityController<PodTakeoverSupportActivity> activity=Robolectric.buildActivity(PodTakeoverSupportActivity.class).setup()) {
            PodTakeoverSupportAndroid.Page page=(PodTakeoverSupportAndroid.Page)((android.view.ViewGroup)activity.get().findViewById(android.R.id.content)).getChildAt(0);
            assertTrue(page.manageOnly);assertEquals(View.GONE,page.upload.getVisibility());assertEquals(View.GONE,page.consent.getVisibility());
            page.consent.setChecked(true);assertFalse(page.upload.isEnabled());
        }
    }
    @Test public void transientPauseRetainsRetryAndNeverAutoUploads()throws Exception {
        try(org.robolectric.android.controller.ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()) {
            PodTakeover.RecoveryView view=new PodTakeover.RecoveryView(activity.get());
            view.render(new JSONObject().put("phase","PAUSED").put("reason","space_insufficient"),false);
            assertEquals(View.VISIBLE,view.retry.getVisibility());assertTrue(view.retry.isEnabled());assertEquals(View.VISIBLE,view.support.getVisibility());
            view.render(new JSONObject().put("phase","COPYING"),true);
            assertEquals(View.GONE,view.support.getVisibility());assertEquals(View.GONE,view.retry.getVisibility());
        }
    }
}
