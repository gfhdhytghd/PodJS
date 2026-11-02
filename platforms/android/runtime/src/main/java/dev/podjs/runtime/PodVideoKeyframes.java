package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.view.TextureView;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Main-thread LRU of decoded frame-zero pixels, never network covers. HWUI
 * uploads these bitmaps before reveal. Eight 466x466 RGBA buffers: 6.63 MiB
 * CPU, at most another 6.63 MiB of GPU textures; one 0.83 MiB readback scratch. Displayed entries are pinned.
 * There is no disk encoding, credential persistence, or extra decoder lease. */
final class PodVideoKeyframes implements AutoCloseable {
    static final int MAX_EDGE = 466, MAX_ENTRIES = 8;
    static final long MAX_BYTES = (long) MAX_EDGE * MAX_EDGE * 4 * MAX_ENTRIES;
    static final class Frame {
        final Bitmap pixels;
        int pins;
        float pixelAspectRatio=1f;
        Frame(Bitmap pixels) { this.pixels = pixels; }
    }
    private final LinkedHashMap<String, Frame> frames = new LinkedHashMap<>(16, .75f, true);
    long bytes;
    Frame get(String binding) { return frames.get(binding); }
    int size() { return frames.size(); }
    private final java.util.ArrayDeque<Runnable> captures=new java.util.ArrayDeque<>();
    private boolean copying,closed;
    /** One bounded asynchronous readback. Explicit video crop excludes codec
     * stride padding; TextureView.getBitmap can include that padding on HWUI. */
    void capture(String binding,TextureView texture,int width,int height,android.os.Handler main,
                 java.util.function.BooleanSupplier current,java.util.function.Consumer<Frame> complete) {
        if(closed||binding==null||captures.size()>=MAX_ENTRIES){complete.accept(null);return;}
        captures.add(()->{
            if(!current.getAsBoolean()){complete.accept(null);nextCapture();return;}
            Frame existing=get(binding);
            if(existing!=null){complete.accept(existing);nextCapture();return;}
            readPixels(texture,width,height,main,pixels->{
                Frame frame=null;
                if(pixels!=null){if(closed||!current.getAsBoolean())pixels.recycle();else frame=put(binding,pixels);}
                complete.accept(frame);nextCapture();
            });
        });
        if(!copying)nextCapture();
    }
    private void nextCapture(){Runnable next=captures.poll();copying=next!=null;if(next!=null)next.run();}
    static void readPixels(TextureView texture,int width,int height,android.os.Handler main,java.util.function.Consumer<Bitmap> complete){
        if(texture==null||!texture.isAvailable()||width<1||height<1){complete.accept(null);return;}
        double scale=MAX_EDGE/(double)Math.max(width,height);
        Bitmap pixels=Bitmap.createBitmap(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)),Bitmap.Config.ARGB_8888);
        android.view.Surface surface=new android.view.Surface(texture.getSurfaceTexture());
        try{
            android.view.PixelCopy.request(surface,new android.graphics.Rect(0,0,width,height),pixels,result->{
                surface.release();
                if(result==android.view.PixelCopy.SUCCESS)complete.accept(pixels);else{pixels.recycle();complete.accept(null);}
            },main);
        }catch(RuntimeException error){surface.release();pixels.recycle();complete.accept(null);}
    }
    Frame put(String binding, Bitmap pixels) {
        long size = pixels.getAllocationByteCount();
        if (pixels.getWidth() > MAX_EDGE || pixels.getHeight() > MAX_EDGE || size > MAX_BYTES) { pixels.recycle(); return null; }
        Frame old = frames.get(binding);
        if (old != null) { pixels.recycle(); return old; }
        Iterator<Map.Entry<String, Frame>> iterator = frames.entrySet().iterator();
        while ((frames.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES) && iterator.hasNext()) {
            Frame frame = iterator.next().getValue();
            if (frame.pins != 0) continue;
            bytes -= frame.pixels.getAllocationByteCount(); iterator.remove(); frame.pixels.recycle();
        }
        if (frames.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES) { pixels.recycle(); return null; }
        pixels.prepareToDraw();
        Frame frame = new Frame(pixels); frames.put(binding, frame); bytes += size; return frame;
    }
    void trim() {
        Iterator<Frame> iterator = frames.values().iterator();
        while (iterator.hasNext()) { Frame frame = iterator.next(); if (frame.pins == 0) { bytes -= frame.pixels.getAllocationByteCount(); iterator.remove(); frame.pixels.recycle(); } }
    }
    @Override public void close() { closed=true; trim(); }
}
