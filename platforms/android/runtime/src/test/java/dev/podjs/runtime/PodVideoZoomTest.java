package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Matrix;
import android.os.Looper;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoZoomTest {
    static Field field(Class<?> t,String n)throws Exception { Field f=t.getDeclaredField(n);f.setAccessible(true);return f; }
    static class Texture extends TextureView { int matrices; Texture(Context c){super(c);} @Override public void setTransform(Matrix m){matrices++;super.setTransform(m);} }
    Context context; FrameLayout parent; PodVideoSessionServices service; Class<?> type; Map<String,Object> slots;
    JSONObject bounds;
    @Before @SuppressWarnings("unchecked") public void setup()throws Exception {
        context=ApplicationProvider.getApplicationContext();parent=new FrameLayout(context);
        service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));service.attachToParent(parent);
        Shadows.shadowOf(Looper.getMainLooper()).idle();type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        slots=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);
        bounds=new JSONObject().put("width",466).put("height",400);
        Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
        for(String id:new String[]{"old","next"}) {
            Object slot=ctor.newInstance(service,id);slots.put(id,slot);
            ExoPlayer player=new ExoPlayer.Builder(context).build();field(type,"player").set(slot,player);
            field(type,"audioPolicy").set(slot,new PodMediaAudioPolicy(context,player));
            call("setBounds",new JSONObject().put("sessionId",id).put("bounds",bounds));
            TextureView original=(TextureView)field(type,"videoView").get(slot);
            Texture texture=new Texture(context);texture.setLayoutParams(original.getLayoutParams());parent.removeView(original);parent.addView(texture);texture.layout(0,0,466,400);field(type,"videoView").set(slot,texture);
        }
    }
    @After public void close(){service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void call(String method,JSONObject args)throws Exception {
        PodVideoSessionServices.Callback reply=new PodVideoSessionServices.Callback(){public void complete(JSONObject v){}public void fail(String c,String m){org.junit.Assert.fail(c+":"+m);}};
        service.dispatch("video.session."+method,args,reply);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    Texture texture(String id)throws Exception {return (Texture)field(type,"videoView").get(slots.get(id));}
    void sizes(int w,int h)throws Exception {for(Object slot:slots.values()){ExoPlayer p=(ExoPlayer)field(type,"player").get(slot);field(p.getClass(),"videoSize").set(p,new VideoSize(w,h));}}
    double[] edges(String id)throws Exception {
        Matrix m=new Matrix();texture(id).getTransform(m);float[] p={0,0,466,0,0,400};m.mapPoints(p);
        return new double[]{Math.hypot(p[2]-p[0],p[3]-p[1]),Math.hypot(p[4]-p[0],p[5]-p[1])};
    }
    @Test public void rotationUsesDestinationScaleForEveryFrame()throws Exception {
        for(int[] size:new int[][]{{160,90},{90,160}})for(String mode:new String[]{"standard","expanded","shrunk"})for(int start:new int[]{0,90,180,270}) {
            sizes(size[0],size[1]);
            call("setTransform",new JSONObject().put("sessionId","old").put("scaleMode",mode).put("rotationDeg",start+90).put("scaleRotationDeg",start+90));
            double[] expected=edges("old");
            for(int frame=0;frame<=20;frame++) {
                call("setTransform",new JSONObject().put("sessionId","old").put("scaleMode",mode).put("rotationDeg",start+frame*4.5).put("scaleRotationDeg",start+90));
                double[] actual=edges("old");assertEquals("rotation width must stay fixed",expected[0],actual[0],.002);assertEquals(expected[1],actual[1],.002);
                assertEquals((double)size[0]/size[1],actual[0]/actual[1],.00001);
            }
        }
    }
    @Test public void pagerKeepsAspectAndDoesNotResetTextureMatrixEachFrame()throws Exception {
        for(int[] size:new int[][]{{160,90},{90,160}})for(String mode:new String[]{"standard","expanded","shrunk"})for(int angle:new int[]{0,90,180,270})for(String direction:new String[]{"up","down"}) {
            sizes(size[0],size[1]);call("setTransform",new JSONObject().put("sessionId","old").put("scaleMode",mode).put("rotationDeg",angle));
            for(int frame=0;frame<=20;frame++) {
                int before=texture("next").matrices;
                call("transition",new JSONObject().put("fromSessionId","old").put("toSessionId","next").put("progress",frame/20.0).put("direction",direction).put("bounds",bounds));
                for(String id:new String[]{"old","next"}) { double[] actual=edges(id);assertEquals((double)size[0]/size[1],actual[0]/actual[1],.00001); }
                if(frame>0)assertEquals("pager frame must only translate",before,texture("next").matrices);
            }
        }
    }
}
