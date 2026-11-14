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
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoTransformReviewTest {
    static void set(Object target,String name,Object value)throws Exception {
        java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static class PausedTexture extends TextureView {
        int invalidations;
        PausedTexture(Context context){super(context);}
        @Override public void invalidate(){invalidations++;super.invalidate();}
    }
    @Test public void legacyPausedTransformInvalidatesWithoutAnotherDecodedBuffer()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodVideoServices service=new PodVideoServices(context);
        ExoPlayer player=new ExoPlayer.Builder(context).build();
        PausedTexture texture=new PausedTexture(context);
        texture.setLayoutParams(new FrameLayout.LayoutParams(466,400));texture.layout(0,0,466,400);
        set(player,"videoSize",new VideoSize(1920,1080));set(service,"player",player);set(service,"videoView",texture);
        try {
            assertFalse(player.getPlayWhenReady());
            for(String mode:new String[]{"standard","expanded","shrunk"}) {
                int before=texture.invalidations;
                service.dispatch("video.setTransform",new JSONObject().put("rotationDeg",45).put("scaleRotationDeg",90).put("scaleMode",mode),new PodVideoServices.Callback(){
                    public void complete(JSONObject value){}
                    public void fail(String code,String message){org.junit.Assert.fail(code+":"+message);}
                });
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue("paused legacy texture must redraw without a decoder callback",texture.invalidations>before);
            }
        } finally {service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();player.release();}
    }

    // Validate the composed video-pixel -> viewport -> TextureView transform,
    // not the unequal TextureView correction coefficients in isolation.
    static void geometry(Matrix matrix,float w,float h,float vw,float vh,float angle,float target,String mode,float x,float y) {
        float[] points={0,0,w,0,0,h,w/2,h/2};matrix.mapPoints(points);
        double ux=points[2]-points[0],uy=points[3]-points[1],vx=points[4]-points[0],vy=points[5]-points[1];
        double width=Math.hypot(ux,uy),height=Math.hypot(vx,vy);
        double r=Math.toRadians(target),rw=vw*Math.abs(Math.cos(r))+vh*Math.abs(Math.sin(r)),rh=vw*Math.abs(Math.sin(r))+vh*Math.abs(Math.cos(r));
        double scale=mode.equals("expanded")?Math.max(w/rw,h/rh):Math.min(w/rw,h/rh);
        if(mode.equals("shrunk"))scale=Math.min(scale,Math.min(w,h)/Math.hypot(vw,vh));
        assertEquals("horizontal pixels have destination scale",scale,width/vw,.00001);
        assertEquals("vertical pixels have the same scale",scale,height/vh,.00001);
        assertEquals("picture axes stay perpendicular",0,(ux*vx+uy*vy)/(width*height),.00001);
        assertEquals(w/2+x,points[6],.002);assertEquals(h/2+y,points[7],.002);
        assertEquals(Math.cos(Math.toRadians(angle)),ux/width,.00001);
        assertEquals(Math.sin(Math.toRadians(angle)),uy/width,.00001);
    }
    @Test public void everyFramePreservesPixelScaleIncludingNonSquarePixelsAndViewportChanges() {
        PodVideoTransformCache cache=new PodVideoTransformCache();
        for(float[] viewport:new float[][]{{466,400},{400,466},{233,233}})
        for(float[] video:new float[][]{{1920,1080},{1080,1920},{720*1.25f,576}})
        for(String mode:new String[]{"standard","expanded","shrunk"})
        for(int start:new int[]{0,90,180,270})for(int frame=0;frame<=60;frame++) {
            float angle=start+frame*1.5f;
            geometry(cache.get(viewport[0],viewport[1],video[0],video[1],angle,start+90,mode,13,-27),
                viewport[0],viewport[1],video[0],video[1],angle,start+90,mode,13,-27);
        }
    }
    @Test public void cacheKeysEveryGeometryInputAndNeverMutatesAnAppliedMatrix() {
        float[] base={466,400,1920,1080,30,90,0,0};
        for(int key=0;key<9;key++) {
            PodVideoTransformCache cache=new PodVideoTransformCache();
            Matrix old=cache.get(base[0],base[1],base[2],base[3],base[4],base[5],"standard",base[6],base[7]);
            Matrix snapshot=new Matrix(old);
            assertSame(old,cache.get(base[0],base[1],base[2],base[3],base[4],base[5],"standard",base[6],base[7]));
            float[] next=base.clone();if(key<8)next[key]+=key==5?90:17;
            String mode=key==8?"expanded":"standard";
            Matrix changed=cache.get(next[0],next[1],next[2],next[3],next[4],next[5],mode,next[6],next[7]);
            assertNotSame("cache input "+key,old,changed);assertEquals(snapshot,old);
            geometry(changed,next[0],next[1],next[2],next[3],next[4],next[5],mode,next[6],next[7]);
        }
    }
}
