package dev.podjs.runtime;

/** Callback-entry timing is independent of work scheduled after presentation. */
final class PodFirstTextureTrace {
    static void record(long callbackAtMs,long surfaceBegan,long viewBegan,Runnable startWarm) {
        android.util.Log.i("PodJSPerf","firstFrame surfaceToFirstFrameMs="+(callbackAtMs-surfaceBegan)
            +" viewToFirstTextureMs="+(callbackAtMs-viewBegan)+" callbackAtMs="+callbackAtMs);
        long began=System.nanoTime();
        try{startWarm.run();}finally{
            android.util.Log.i("PodJSPerf","systemFontWarmStart callerMs="+(System.nanoTime()-began)/1e6);
        }
    }
}
