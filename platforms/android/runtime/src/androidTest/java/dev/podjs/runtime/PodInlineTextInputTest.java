package dev.podjs.runtime;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks the real overlay lifetime without injecting keyboard or touch input. */
public final class PodInlineTextInputTest {
    @Test public void documentCaretUsesUnclippedInsetAndFollowsFocusAndSelection() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodInlineTextInput input=new PodInlineTextInput(activity,event->{});
        try {
            JSONObject title=new JSONObject().put("x",14).put("y",42.5).put("width",205).put("height",29.3)
                .put("value","").put("autoFocus",false).put("includeFontPadding",false)
                .put("document",new JSONObject().put("id","caret-note").put("role","title")
                    .put("viewportHeight",135).put("viewportOverhangDp",1));
            JSONObject body=new JSONObject().put("x",14).put("y",80.5).put("width",205).put("height",96)
                .put("value","").put("multiline",true).put("keepExisting",true)
                .put("autoFocus",false).put("includeFontPadding",false)
                .put("horizontalPaddingDp",0).put("verticalPaddingDp",0)
                .put("document",new JSONObject().put("id","caret-note").put("role","body")
                    .put("viewportHeight",135).put("unfocusedCaretColor","#ff6026"));
            instrumentation.runOnMainSync(()->{input.attach(activity.videoHost());input.open(401,title);input.open(402,body);});
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                ScrollView scroll=(ScrollView)activity.videoHost().getChildAt(0);
                android.widget.FrameLayout content=(android.widget.FrameLayout)scroll.getChildAt(0);
                assertEquals(3,content.getChildCount());
                EditText titleField=(EditText)content.getChildAt(0),bodyField=(EditText)content.getChildAt(1);
                View caret=content.getChildAt(2);
                int density=Math.round(activity.getResources().getDisplayMetrics().density*1000);
                assertEquals(Math.round(13*density/1000f),((android.widget.FrameLayout.LayoutParams)scroll.getLayoutParams()).leftMargin);
                assertEquals(Math.round(density/1000f),titleField.getLeft());
                assertEquals(Math.round(density/1000f),bodyField.getLeft());
                assertEquals(0,caret.getLeft());
                assertEquals(bodyField.getTop(),caret.getTop());
                assertEquals(View.VISIBLE,caret.getVisibility());
                bodyField.requestFocus();assertEquals(View.INVISIBLE,caret.getVisibility());
                activity.videoHost().requestFocus();assertEquals(View.VISIBLE,caret.getVisibility());
                input.setValue(402,"AB");
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                ScrollView scroll=(ScrollView)activity.videoHost().getChildAt(0);
                android.widget.FrameLayout content=(android.widget.FrameLayout)scroll.getChildAt(0);
                EditText bodyField=(EditText)content.getChildAt(1);View caret=content.getChildAt(2);
                assertEquals(2,bodyField.getSelectionStart());
                assertTrue("caret did not follow selection",caret.getLeft()>bodyField.getLeft());
                assertEquals(View.VISIBLE,caret.getVisibility());
            });
        }finally{instrumentation.runOnMainSync(()->{input.closeAll();activity.finish();});}
    }
    @Test public void crownAcceleratorMatchesNativeSlowFastAndDirectionRules(){
        PodInlineTextInput.CrownAccelerator slow=new PodInlineTextInput.CrownAccelerator();
        for(int index=0;index<49;index++)assertEquals(0,slow.consume(1f,index*300L));
        assertEquals(1,slow.consume(1f,49*300L));
        PodInlineTextInput.CrownAccelerator fast=new PodInlineTextInput.CrownAccelerator();
        assertEquals(0,fast.consume(1f,0));
        for(int index=1;index<=4;index++)assertEquals(0,fast.consume(1f,index*40L));
        assertEquals(1,fast.consume(1f,5*40L));
        PodInlineTextInput.CrownAccelerator reverse=new PodInlineTextInput.CrownAccelerator();
        for(int index=0;index<49;index++)assertEquals(0,reverse.consume(1f,index*300L));
        for(int index=0;index<49;index++)assertEquals(0,reverse.consume(-1f,15000L+index*300L));
        assertEquals(-1,reverse.consume(-1f,29700L));
    }
    @Test public void documentCrownMovesTitleDirectlyAndBodyByCodePoint() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodInlineTextInput input=new PodInlineTextInput(activity,event->{});
        try {
            JSONObject title=new JSONObject().put("x",18).put("y",42).put("width",197).put("height",32)
                .put("value","A😀B").put("autoFocus",false).put("selectionStart",1)
                .put("document",new JSONObject().put("id","crown-note").put("role","title").put("viewportHeight",135));
            JSONObject body=new JSONObject().put("x",18).put("y",82).put("width",197).put("height",69)
                .put("value","A😀B").put("multiline",true).put("keepExisting",true)
                .put("autoFocus",false).put("selectionStart",1)
                .put("document",new JSONObject().put("id","crown-note").put("role","body").put("viewportHeight",135));
            instrumentation.runOnMainSync(()->{
                input.attach(activity.videoHost());input.open(301,title);input.open(302,body);
                ScrollView scroll=(ScrollView)activity.videoHost().getChildAt(0);
                android.widget.FrameLayout content=(android.widget.FrameLayout)scroll.getChildAt(0);
                EditText titleField=(EditText)content.getChildAt(0),bodyField=(EditText)content.getChildAt(1);
                titleField.requestFocus();
                assertTrue(input.handleCrownScroll(crown(-1f,0)));
                assertEquals("one crown event should cross one title code point",3,titleField.getSelectionStart());
                activity.videoHost().requestFocus();
                for(int index=0;index<=5;index++)assertTrue(input.handleCrownScroll(crown(-1f,index*40L)));
                assertEquals("body crown must cross the surrogate pair as one code point",3,bodyField.getSelectionStart());
                assertFalse(bodyField.hasFocus());
                input.close(302);input.close(301);
                assertFalse(input.handleCrownScroll(crown(-1f,300L)));
            });
        }finally{instrumentation.runOnMainSync(()->{input.closeAll();activity.finish();});}
    }
    private static MotionEvent crown(float vertical,long uptime){
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();
        pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL,vertical);
        return MotionEvent.obtain(0,uptime,MotionEvent.ACTION_SCROLL,1,
            new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coords},
            0,0,1f,1f,0,0,InputDevice.SOURCE_ROTARY_ENCODER,0);
    }
    @Test public void rootTouchMovesUnfocusedDocumentCaret() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodInlineTextInput input=new PodInlineTextInput(activity,event->{});
        try {
            StringBuilder lines=new StringBuilder();
            for(int index=0;index<80;index++)lines.append("第").append(index).append("行 ABCDEFGHIJKLMN\n");
            JSONObject title=new JSONObject().put("x",18).put("y",42).put("width",197).put("height",32)
                .put("value","标题").put("autoFocus",false)
                .put("document",new JSONObject().put("id","root-touch").put("role","title").put("viewportHeight",135));
            JSONObject body=new JSONObject().put("x",18).put("y",82).put("width",197).put("height",69)
                .put("value",lines.toString()).put("multiline",true).put("keepExisting",true)
                .put("autoFocus",false).put("selectionStart",lines.indexOf("第40行"))
                .put("document",new JSONObject().put("id","root-touch").put("role","body").put("viewportHeight",135));
            instrumentation.runOnMainSync(()->{input.attach(activity.videoHost());input.open(201,title);input.open(202,body);});
            instrumentation.waitForIdleSync();
            final EditText[] field={null};final int[] before={0},point=new int[2],step={0};
            instrumentation.runOnMainSync(()->{
                ScrollView scroll=(ScrollView)activity.videoHost().getChildAt(0);
                field[0]=(EditText)((android.widget.FrameLayout)scroll.getChildAt(0)).getChildAt(1);
                assertFalse(field[0].hasFocus());
                before[0]=field[0].getSelectionStart();
                scroll.getLocationOnScreen(point);
                point[0]+=scroll.getWidth()/2;
                point[1]+=Math.round(scroll.getHeight()*0.7f);
                step[0]=Math.round(activity.getResources().getDisplayMetrics().density*24);
            });
            long started=SystemClock.uptimeMillis();
            pointer(instrumentation,started,started,MotionEvent.ACTION_DOWN,point[0],point[1]);
            pointer(instrumentation,started,started+30,MotionEvent.ACTION_MOVE,point[0],point[1]-step[0]);
            pointer(instrumentation,started,started+60,MotionEvent.ACTION_MOVE,point[0],point[1]-step[0]*2);
            pointer(instrumentation,started,started+90,MotionEvent.ACTION_UP,point[0],point[1]-step[0]*2);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                assertTrue("root swipe did not advance visual-line caret",field[0].getSelectionStart()>before[0]);
                assertFalse("root swipe unexpectedly focused body",field[0].hasFocus());
            });
        } finally {
            instrumentation.runOnMainSync(()->{input.closeAll();activity.finish();});
        }
    }
    private static void pointer(Instrumentation instrumentation,long down,long now,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(down,now,action,x,y,0);
        try{instrumentation.sendPointerSync(event);}finally{event.recycle();}
    }
    @Test public void documentFieldsShareScrollAndCenterInitialSelection() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodInlineTextInput input=new PodInlineTextInput(activity,event->{});
        try {
            StringBuilder longBody=new StringBuilder();
            for(int index=0;index<50;index++)longBody.append("第").append(index).append("行 ABCDEFGHIJKLMNOPQRST\n");
            JSONObject title=new JSONObject().put("x",18).put("y",42).put("width",197).put("height",32)
                .put("value","长备忘录").put("autoFocus",false)
                .put("document",new JSONObject().put("id","test-note").put("role","title")
                    .put("viewportHeight",135).put("metadata","今天 · 50 字"));
            JSONObject body=new JSONObject().put("x",18).put("y",108).put("width",197).put("height",69)
                .put("value",longBody.toString()).put("multiline",true).put("keepExisting",true)
                .put("autoFocus",false).put("selectionStart",longBody.indexOf("第40行"))
                .put("document",new JSONObject().put("id","test-note").put("role","body")
                    .put("viewportHeight",135));
            instrumentation.runOnMainSync(()->{
                input.attach(activity.videoHost());
                input.open(101,title);
                input.open(102,body);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                assertEquals(1,activity.videoHost().getChildCount());
                ScrollView scroll=(ScrollView)activity.videoHost().getChildAt(0);
                android.widget.FrameLayout content=(android.widget.FrameLayout)scroll.getChildAt(0);
                assertEquals(3,content.getChildCount());
                assertEquals("长备忘录",((EditText)content.getChildAt(1)).getText().toString());
                assertEquals("今天 · 50 字",((TextView)content.getChildAt(0)).getText().toString());
                assertTrue(scroll.getScrollY()>0);
                EditText bodyField=(EditText)content.getChildAt(2);
                assertFalse(bodyField.hasFocus());
                int before=bodyField.getSelectionStart();
                long started=SystemClock.uptimeMillis();
                touch(bodyField,started,started,MotionEvent.ACTION_DOWN,100,200);
                touch(bodyField,started,started+40,MotionEvent.ACTION_MOVE,100,104);
                touch(bodyField,started,started+80,MotionEvent.ACTION_UP,100,104);
                assertTrue("swipe should advance the caret by visual lines",bodyField.getSelectionStart()>before);
                assertFalse("swipe should not summon the keyboard",bodyField.hasFocus());
                touch(bodyField,started+100,started+100,MotionEvent.ACTION_DOWN,100,200);
                touch(bodyField,started+100,started+130,MotionEvent.ACTION_UP,100,200);
                assertTrue("tap should activate editing",bodyField.hasFocus());
                scroll.fullScroll(android.view.View.FOCUS_UP);
                assertEquals("title, metadata and body must share one document",0,scroll.getScrollY());
                assertEquals(0,((EditText)content.getChildAt(1)).getTop());
                assertTrue(((TextView)content.getChildAt(0)).getTop()<bodyField.getTop());
                input.close(102);
                input.close(101);
                assertEquals(0,activity.videoHost().getChildCount());
            });
        } finally {
            instrumentation.runOnMainSync(()->{input.closeAll();activity.finish();});
        }
    }
    private static void touch(EditText field,long down,long now,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(down,now,action,x,y,0);
        try{field.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    @Test public void inlineFieldUsesBoundsAndClosesWithItsSession() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        VideoTestActivity activity=(VideoTestActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PodInlineTextInput input=new PodInlineTextInput(activity,event->{});
        try {
            JSONObject args=new JSONObject().put("x",18).put("y",44).put("width",197).put("height",56).put("value","旧词").put("textAlign","center");
            JSONObject selected=new JSONObject().put("x",18).put("y",92).put("width",197).put("height",95).put("value","第一行\n第二行").put("multiline",true).put("keepExisting",true).put("selectionStart",3).put("horizontalPaddingDp",0);
            selected.put("autoFocus",false);
            instrumentation.runOnMainSync(()->{
                input.attach(activity.videoHost());
                input.open(17,args);
                assertEquals(1,activity.videoHost().getChildCount());
                EditText field=(EditText)activity.videoHost().getChildAt(0);
                assertEquals("旧词",field.getText().toString());
                assertTrue(field.isSingleLine());
                assertEquals(android.view.Gravity.CENTER_HORIZONTAL,field.getGravity()&android.view.Gravity.HORIZONTAL_GRAVITY_MASK);
                assertEquals(0,field.getPaddingLeft());
                assertEquals(0,field.getPaddingRight());
                int density=(int)Math.round(activity.getResources().getDisplayMetrics().density*1000);
                assertEquals(Math.round(18*density/1000f),((android.widget.FrameLayout.LayoutParams)field.getLayoutParams()).leftMargin);
                input.setValue(17,"新词");
                assertEquals("新词",field.getText().toString());
                assertEquals(2,field.getSelectionStart());
                input.open(19,selected);
                EditText body=(EditText)activity.videoHost().getChildAt(1);
                assertEquals(3,body.getSelectionStart());
                assertFalse(body.isSingleLine());
                assertEquals(0,body.getPaddingLeft());
                assertEquals(0,body.getPaddingRight());
                assertFalse(body.hasFocus());
                assertTrue(activity.videoHost().hasFocus());
                input.close(19);
                assertThrows(IllegalArgumentException.class,()->input.setValue(18,"不存在"));
                input.close(18);
                assertEquals(1,activity.videoHost().getChildCount());
                input.close(17);
                assertEquals(0,activity.videoHost().getChildCount());
            });
        } finally {
            instrumentation.runOnMainSync(()->{input.closeAll();activity.finish();});
        }
    }
}
