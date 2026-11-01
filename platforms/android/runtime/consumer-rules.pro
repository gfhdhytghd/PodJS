# Invoked by name from jni_bridge.cpp with one reused 64 KiB byte array.
-keepclassmembers class dev.podjs.runtime.PodPakReader {
    int readAt(long, byte[], int);
}
# Android's system fallback is called from the retained UI core through JNI.
-keep class dev.podjs.runtime.PodSystemFontFallback {
    static byte[] raster(java.lang.String, float, int, int, int, int, int);
}

# Synchronous closed event bridge used by QuickJS and JNI.
-keep class dev.podjs.runtime.PodBenchmark {
    public static long dispatch(java.lang.String);
}
