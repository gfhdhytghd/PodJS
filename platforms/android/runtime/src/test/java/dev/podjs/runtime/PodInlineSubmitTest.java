package dev.podjs.runtime;
import android.app.Activity;
import android.app.Application;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=Application.class)
public class PodInlineSubmitTest {
 @Test public void physicalEnterSubmitsFinalValueOnceAndDoesNotReplaceTheContentSurface()throws Exception {
  try(org.robolectric.android.controller.ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
   Activity activity=controller.get();FrameLayout root=new FrameLayout(activity);activity.setContentView(root);
   android.view.View content=new android.view.View(activity);root.addView(content);ArrayList<String> events=new ArrayList<>();
   PodInlineTextInput input=new PodInlineTextInput(activity,events::add);input.attach(root);
   input.open(17,new JSONObject().put("x",18).put("y",44).put("width",197).put("height",56).put("value",""));
   EditText field=(EditText)root.getChildAt(1);field.setText("599");events.clear();
   assertTrue(field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER)));
   assertTrue(events.isEmpty());assertTrue(field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER)));
   assertEquals(1,events.size());JSONObject event=new JSONObject(events.get(0));assertEquals("input.inline.submit",event.getString("t"));assertEquals("599",event.getString("value"));
   assertSame(content,root.getChildAt(0));input.close(17);assertEquals(1,root.getChildCount());assertSame(content,root.getChildAt(0));
  }
 }
 @Test public void softSearchSubmitsButMultilineEnterRemainsANewline()throws Exception {
  try(org.robolectric.android.controller.ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
   Activity activity=controller.get();FrameLayout root=new FrameLayout(activity);activity.setContentView(root);ArrayList<String> events=new ArrayList<>();PodInlineTextInput input=new PodInlineTextInput(activity,events::add);input.attach(root);
   input.open(18,new JSONObject().put("x",18).put("y",44).put("width",197).put("height",56).put("value","599"));((EditText)root.getChildAt(0)).onEditorAction(EditorInfo.IME_ACTION_SEARCH);assertEquals(1,events.size());assertEquals("599",new JSONObject(events.get(0)).getString("value"));
   input.open(19,new JSONObject().put("x",18).put("y",44).put("width",197).put("height",56).put("value","note").put("multiline",true));events.clear();EditText field=(EditText)root.getChildAt(0);field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));
   assertTrue(field.getText().toString().contains("\n"));for(String raw:events)assertNotEquals("input.inline.submit",new JSONObject(raw).getString("t"));input.closeAll();
  }
 }
}
