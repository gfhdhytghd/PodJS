package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.view.TextureView;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Main-thread LRU of decoded frame-zero pixels, never network covers. HWUI
 * uploads these bitmaps before reveal. Ten 466x466 RGBA buffers: 8.29 MiB
 * CPU, at most another 8.29 MiB of GPU textures; one 0.83 MiB readback scratch. Displayed entries are pinned.
 * Disk compression, when enabled, runs on a worker with a pinned bitmap.
 * No credential persistence or extra decoder lease occurs here. */
final class PodVideoKeyframes implements AutoCloseable {
    static final int MAX_EDGE = 466, MAX_ENTRIES = 10;
    static final long MAX_BYTES = (long) MAX_EDGE * MAX_EDGE * 4 * MAX_ENTRIES;
    static final class Frame {
        final Bitmap pixels;
        long entry;
        int pins;
        float pixelAspectRatio=1f;
        Frame(Bitmap pixels) { this.pixels = pixels; }
    }
    private final LinkedHashMap<String, Frame> frames = new LinkedHashMap<>(16, .75f, true);
    volatile long bytes;
    private java.util.Set<String> window;
    private volatile java.util.function.BiConsumer<String,Frame> stored;
    void onStored(java.util.function.BiConsumer<String,Frame> listener){stored=listener;}
    // Closed lifecycle stages: 1 stored, 2 outside window, 3 byte/count eviction,
    // 4 explicit trim, 5 pin, 6 unpin. Entry is a process-local cache ordinal.
    private void trace(Frame frame,int stage){
        if(frame.entry>0)android.util.Log.i("PodMediaCache","frame_lifecycle entry="+frame.entry+" stage="+stage+" pins="+frame.pins+" bytes="+frame.pixels.getAllocationByteCount()+" total_bytes="+bytes+" entries="+frames.size()+" mono_ms="+android.os.SystemClock.uptimeMillis());
    }
    synchronized void pin(Frame frame){frame.pins++;trace(frame,5);}
    synchronized void unpin(Frame frame){
        if(frame.pins<=0)throw new IllegalStateException("keyframe_pin_underflow");
        frame.pins--;trace(frame,6);trimOutsideWindow();
    }
    synchronized Frame acquire(String binding){Frame frame=frames.get(binding);if(frame!=null){frame.pins++;trace(frame,5);}return frame;}
    synchronized void retainWindow(java.util.Set<String> wanted){window=new java.util.LinkedHashSet<>(wanted);trimOutsideWindow();}
    private void trimOutsideWindow(){
        Iterator<Map.Entry<String,Frame>> it=frames.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String,Frame> entry=it.next();Frame frame=entry.getValue();
            if(frame.pins==0&&(closed||window!=null&&!window.contains(entry.getKey()))){bytes-=frame.pixels.getAllocationByteCount();it.remove();trace(frame,2);frame.pixels.recycle();}
        }
    }
    synchronized Frame get(String binding) { return frames.get(binding); }
    synchronized boolean contains(String binding) { return frames.containsKey(binding); }
    synchronized boolean canAccept(String binding) {
        if(closed||window!=null&&!window.contains(binding))return false;
        if(frames.containsKey(binding)||frames.size()<MAX_ENTRIES&&bytes+MAX_EDGE*(long)MAX_EDGE*4<=MAX_BYTES)return true;
        for(Map.Entry<String,Frame> entry:frames.entrySet())if(entry.getValue().pins==0&&priority(entry.getKey())>=priority(binding))return true;
        return false;
    }
    synchronized int size() { return frames.size(); }
    synchronized org.json.JSONObject memoryStats()throws Exception {
        long pinned=0;int pins=0;
        for(Frame frame:frames.values())if(frame.pins>0){pinned+=frame.pixels.getAllocationByteCount();pins++;}
        return new org.json.JSONObject().put("entries",frames.size()).put("bytes",bytes).put("maxBytes",MAX_BYTES)
            .put("pinnedEntries",pins).put("pinnedBytes",pinned).put("captureQueue",captures.size()).put("copying",copying);
    }
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
    synchronized Frame put(String binding, Bitmap pixels) {return put(binding,pixels,true);}
    synchronized Frame put(String binding, Bitmap pixels,boolean persist) {
        return put(binding,pixels,persist,0);
    }
    synchronized Frame put(String binding, Bitmap pixels,boolean persist,long cacheOrdinal) {
        long size = pixels.getAllocationByteCount();
        if (closed || window!=null&&!window.contains(binding) || pixels.getWidth() > MAX_EDGE || pixels.getHeight() > MAX_EDGE || size > MAX_BYTES) { pixels.recycle(); return null; }
        Frame old = frames.get(binding);
        if (old != null) { if(cacheOrdinal>0)old.entry=cacheOrdinal;pixels.recycle(); return old; }
        while (frames.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES) {
            String victim=null;int worst=-1;
            for(Map.Entry<String,Frame> entry:frames.entrySet()){
                if(entry.getValue().pins!=0)continue;
                int rank=priority(entry.getKey());
                if(victim==null||rank>worst){victim=entry.getKey();worst=rank;}
            }
            // The window orders the current and recently passed sources before
            // far speculation. A late far frame cannot evict those neighbours.
            if(victim==null||window!=null&&worst<priority(binding))break;
            Frame removed=frames.remove(victim);bytes-=removed.pixels.getAllocationByteCount();trace(removed,3);removed.pixels.recycle();
        }
        if (frames.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES) { pixels.recycle(); return null; }
        pixels.prepareToDraw();
        Frame frame = new Frame(pixels);frame.entry=cacheOrdinal;frames.put(binding, frame);bytes += size;trace(frame,1);
        if(persist&&stored!=null)stored.accept(binding,frame);return frame;
    }
    private int priority(String binding){
        if(window==null)return 0;int rank=0;
        for(String key:window){if(key.equals(binding))return rank;rank++;}
        return Integer.MAX_VALUE;
    }
    synchronized void trim() {
        Iterator<Frame> iterator = frames.values().iterator();
        while (iterator.hasNext()) { Frame frame = iterator.next(); if (frame.pins == 0) { bytes -= frame.pixels.getAllocationByteCount(); iterator.remove();trace(frame,4);frame.pixels.recycle(); } }
    }
    @Override public void close() { closed=true; trim(); }
}
