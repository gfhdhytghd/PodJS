package dev.podjs.runtime;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Process owner, including foreground views and headless jobs. No account data. */
public final class PodProcessMemoryBudget implements ComponentCallbacks2 {
    public static final int IMAGE=1, GRAPHICS=2, MEDIA=3, SCRATCH=4, TEXT=5;
    private static volatile PodProcessMemoryBudget instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<java.util.function.IntConsumer> listeners=new CopyOnWriteArrayList<>();
    private final long limit;
    private final PodProcessMemoryReader memory;
    private volatile long pss;
    private volatile int lastLevel;
    private long lastPressure,lastPurge;
    private PodProcessMemoryBudget(Context context,long bytes) {
        limit=bytes;memory=PodProcessMemoryReader.get(context);nativeConfigure(bytes);
        if(bytes==0)return;
        context.registerComponentCallbacks(this);
        Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"PodMemoryBudget");t.setDaemon(true);return t;})
            .scheduleWithFixedDelay(this::sample,0,750,TimeUnit.MILLISECONDS);
    }
    public static synchronized void install(Context context) {
        if(instance!=null)return;
        System.loadLibrary("podjs_android");
        long bytes=0;
        try(java.io.InputStream input=context.getAssets().open("pod.manifest.json")) {
            java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
            byte[] chunk=new byte[4096];int count;
            while(buffer.size()<=128*1024&&(count=input.read(chunk))!=-1)buffer.write(chunk,0,count);
            byte[] raw=buffer.toByteArray();
            if(raw.length>128*1024)throw new IllegalArgumentException("Package manifest too large");
            JSONObject manifest=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
            if(manifest.has("memoryBudgetBytes")) {
                Object value=manifest.get("memoryBudgetBytes");
                if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue())throw new IllegalArgumentException("Invalid memory budget");
                bytes=((Number)value).longValue();
                if(bytes<32_000_000L||bytes>2_048_000_000L)throw new IllegalArgumentException("Invalid memory budget");
            }
        } catch(java.io.FileNotFoundException missing) { /* Legacy embedding without package assets. */ }
          catch(java.io.IOException missing) {throw new IllegalStateException("Cannot read memory budget",missing);}
          catch(org.json.JSONException invalid) {throw new IllegalArgumentException("Invalid package memory budget",invalid);}
        instance=new PodProcessMemoryBudget(context.getApplicationContext(),bytes);
        if(bytes>0)PodMediaMemoryAccounting.install();
    }
    public static long limit(){PodProcessMemoryBudget b=instance;return b==null?0:b.limit;}
    public static Runnable observe(java.util.function.IntConsumer listener) {
        PodProcessMemoryBudget b=instance;if(b==null||b.limit==0)return ()->{};
        b.listeners.add(listener);return ()->b.listeners.remove(listener);
    }
    private void pressure(int level) {
        main.post(()->{
            long now=android.os.SystemClock.elapsedRealtime();
            if(level==lastLevel&&now-lastPressure<2000)return;
            lastPressure=now;lastLevel=level;
            for(java.util.function.IntConsumer listener:listeners){try{listener.accept(level);}catch(RuntimeException unavailable){android.util.Log.w("PodMemoryBudget","trimCallbackUnavailable");}}
        });
    }
    private void sample() {
      try {
        long start=nativeSampleStart();
        PodProcessMemoryReader.Snapshot value=memory.snapshot();
        long measured=Math.multiplyExact(value.totalKiB,1024L);
        if(measured<=0)throw new IllegalStateException("PSS unavailable");
        pss=measured;nativeSample(pss,start);
        int level=pss>=limit-12_000_000?3:pss>=limit-32_000_000?2:0;
        boolean denied=nativePressure();if(denied)level=Math.max(level,2);
        if(denied)android.util.Log.i("PodMemoryBudget","allocationPressure pssBytes="+pss+" upperBytes="+nativeUpper()+" "+nativeResidents());
        if(level!=0||lastLevel!=0)pressure(level);
        long now=android.os.SystemClock.elapsedRealtime();
        if(level>0&&now-lastPurge>=3000){nativePurge();lastPurge=now;}
      }catch(RuntimeException unavailable){pressure(3);}
    }
    public static boolean admitBackground(){return limit()==0||nativeUpper()+8_000_000<=limit()&&nativeJsAvailable()>=8_000_000;}
    static boolean deferBackground(){return limit()>0&&(nativeUpper()+4_000_000>limit()||nativeJsAvailable()<4_000_000);}
    static Lease reserveMedia(long bytes,boolean optional){
        if(bytes<0)return null;
        if(limit()!=0&&!(optional?nativeReserveOptionalMedia(bytes):nativeReserve(MEDIA,bytes)))return null;
        return new Lease(MEDIA,bytes);
    }
    public static Lease reserve(int owner,long bytes) {
        if(bytes<0)return null;
        if(limit()!=0&&!nativeReserve(owner,bytes))return null;
        return new Lease(owner,bytes);
    }
    public static final class Lease implements AutoCloseable {
        private final int owner;private final long bytes;private long generation;private boolean closed;
        private Lease(int owner,long bytes){this.owner=owner;this.bytes=bytes;}
        public synchronized void commit(){if(!closed&&generation==0)generation=limit()==0?-1:nativeCommit(owner,bytes);}
        @Override public synchronized void close(){if(closed)return;closed=true;if(limit()==0)return;if(generation==0)nativeCancel(owner,bytes);else nativeRelease(owner,bytes,generation);}
    }
    @Override public void onTrimMemory(int level){if(level==TRIM_MEMORY_UI_HIDDEN)pressure(3);else if(level>=TRIM_MEMORY_RUNNING_LOW)pressure(3);}
    @Override public void onLowMemory(){pressure(3);}
    @Override public void onConfigurationChanged(Configuration configuration){}
    private static native void nativeConfigure(long bytes);
    private static native long nativeSampleStart();
    private static native void nativeSample(long pss,long generation);
    private static native boolean nativePressure();
    private static native void nativePurge();
    private static native long nativeUpper();
    private static native long nativeJsAvailable();
    private static native boolean nativeReserveOptionalMedia(long bytes);
    private static native String nativeResidents();
    private static native boolean nativeReserve(int owner,long bytes);
    private static native long nativeCommit(int owner,long bytes);
    private static native void nativeCancel(int owner,long bytes);
    private static native void nativeRelease(int owner,long bytes,long generation);
}
