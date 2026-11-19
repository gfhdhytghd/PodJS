package dev.podjs.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSurfaceView;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoDirectOutputTest {
    static class Fixture {
        final Context context = ApplicationProvider.getApplicationContext();
        final FrameLayout host = new FrameLayout(context);
        final SurfaceTexture physical = new SurfaceTexture(0);
        final TextureView texture = new TextureView(context) {
            @Override public boolean isAttachedToWindow() { return true; }
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isHardwareAccelerated() { return true; }
            @Override public boolean isShown() { return true; }
            @Override public int getWindowVisibility() { return VISIBLE; }
            @Override public SurfaceTexture getSurfaceTexture() { return physical; }
        };
        final View controls = new View(context);
        final PodVideoDirectOutput output = new PodVideoDirectOutput(new Handler(Looper.getMainLooper()));
        AnalyticsListener frames;
        Surface playingSurface;
        boolean playing = true;
        int textureBinds;
        final ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[]{ExoPlayer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isPlaying": return playing;
                        case "addAnalyticsListener": frames=(AnalyticsListener)args[0]; return null;
                        case "removeAnalyticsListener": frames=null; return null;
                        case "setVideoSurface": playingSurface=(Surface)args[0]; return null;
                        case "setVideoTextureView": assertSame(texture,args[0]);textureBinds++; return null;
                        default: throw new AssertionError(method);
                    }
                });
        final Matrix matrix = PodVideoServices.videoTransform(400,400,1920,1080,0,"standard",0,0);
        SurfaceHolder.Callback callback;
        ShadowSurfaceView.FakeSurfaceHolder holder;
        Fixture() {
            FrameLayout.LayoutParams box = new FrameLayout.LayoutParams(400,400);
            box.topMargin=8;
            host.addView(texture,box);
            texture.layout(0,8,400,408);
            host.addView(controls);
        }
        static void advance(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
        void update(boolean steady) { output.update(player,texture,matrix,steady,7); }
        FrameLayout prepare() {
            update(true); advance(500); update(true);
            FrameLayout layer = (FrameLayout)host.getChildAt(0);
            assertEquals(8,((FrameLayout.LayoutParams)layer.getLayoutParams()).topMargin);
            SurfaceView surface=(SurfaceView)layer.getChildAt(0);
            holder=Shadows.shadowOf(surface).getFakeSurfaceHolder();
            callback=holder.getCallbacks().iterator().next();
            // Robolectric's holder does not own a native producer.
            Surface actual=new Surface(physical);
            SurfaceHolder wrapped=(SurfaceHolder)Proxy.newProxyInstance(SurfaceHolder.class.getClassLoader(),
                    new Class<?>[]{SurfaceHolder.class},(proxy,method,args)->{
                        if (method.getName().equals("getSurface")) return actual;
                        throw new AssertionError(method);
                    });
            callback.surfaceCreated(wrapped);
            assertSame(actual,playingSurface);
            return layer;
        }
    }

    @Test public void onlyConfirmedDecoderOutputAppearsBetweenTextureAndGuestControls() {
        Fixture f=new Fixture();
        FrameLayout layer=f.prepare();
        assertSame(f.texture,f.host.getChildAt(1));
        f.frames.onRenderedFirstFrame(null,new Object(),0);
        assertFalse(f.output.active());
        f.frames.onRenderedFirstFrame(null,f.playingSurface,0);
        assertTrue(f.output.active());
        assertSame(f.texture,f.host.getChildAt(0));
        assertSame(layer,f.host.getChildAt(1));
        assertSame(f.controls,f.host.getChildAt(2));
        f.output.discard(true);
        assertEquals(1,f.textureBinds);
        assertEquals(2,f.host.getChildCount());
    }

    @Test public void cancelledTransferAndSurfaceLossCannotResurrectOldOutput() {
        Fixture f=new Fixture();
        f.prepare();
        AnalyticsListener old=f.frames;
        f.output.interactive();
        old.onRenderedFirstFrame(null,f.playingSurface,0);
        assertFalse(f.output.active());
        f.callback.surfaceDestroyed(f.holder);
        Fixture.advance(20);
        assertFalse(f.output.hasLayer());
        assertEquals(1,f.textureBinds);
        old.onRenderedFirstFrame(null,f.playingSurface,0);
        assertFalse(f.output.active());
    }

    @Test public void timeoutRestoresTextureAndDisposesTheOptionalSurface() {
        Fixture f=new Fixture();
        f.prepare();
        Fixture.advance(1501);
        assertEquals(1,f.textureBinds);
        assertFalse(f.output.hasLayer());
        assertSame(f.texture,f.host.getChildAt(0));
        f.update(true); Fixture.advance(1000); f.update(true);
        assertFalse(f.output.hasLayer());
    }

    @Test public void geometryAndPlaybackEligibilityPreserveInteractiveOutput() {
        Rect rect=new Rect(); float[] values=new float[9];
        Matrix fit=PodVideoServices.videoTransform(400,400,1920,1080,0,"standard",0,0);
        assertTrue(PodVideoDirectOutput.contentRect(fit,400,400,values,rect));
        assertEquals(new Rect(0,88,400,313),rect);
        for (int angle : new int[]{18,90,180,270}) {
            Matrix rotated=PodVideoServices.videoTransform(400,400,1920,1080,angle,"standard",0,0);
            assertFalse(PodVideoDirectOutput.contentRect(rotated,400,400,values,rect));
        }
        Matrix crop=PodVideoServices.videoTransform(400,400,1920,1080,0,"expanded",0,0);
        assertFalse(PodVideoDirectOutput.contentRect(crop,400,400,values,rect));
        Fixture f=new Fixture();
        f.update(false); Fixture.advance(500); f.update(false);
        assertFalse(f.output.hasLayer());
        f.playing=false;
        f.update(true); Fixture.advance(500); f.update(true);
        assertFalse(f.output.hasLayer());
        f.playing=true;
        f.texture.setTranslationX(2f);
        f.update(true); Fixture.advance(500); f.update(true);
        assertFalse(f.output.hasLayer());
    }
}
