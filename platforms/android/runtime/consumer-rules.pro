# Invoked by name from jni_bridge.cpp with one reused 64 KiB byte array.
-keepclassmembers class dev.podjs.runtime.PodPakReader {
    int readAt(long, byte[], int);
}
# Android's system fallback is called from the retained UI core through JNI.
-keep class dev.podjs.runtime.PodSystemFontFallback {
    static byte[] raster(java.lang.String, float, int, int, int, int, int);
    static int[] metrics(float, int, int);
}

# Synchronous closed event bridge used by QuickJS and JNI.
-keep class dev.podjs.runtime.PodBenchmark {
    public static long dispatch(java.lang.String);
}

# JNI exports encode both the declaring class name and method name.
-keepclasseswithmembers,includedescriptorclasses class dev.podjs.runtime.** {
    native <methods>;
}
# Guest JS calls PodServices through the typed host dispatcher, not reflection.
# These Java upcalls are the remaining string-based JNI/reflection boundaries.
-keep class dev.podjs.debug.PodDebugExternal {
    public static *** execute(...);
    public static void close(...);
}
# Preserve the annotated WebView JS boundary if an embedding app adds one.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Java serialization's implicit protocol members are looked up by name.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
# Room/WorkManager and Media3 carry their own consumer rules. The migration
# converters use explicit SQLite APIs and JSONObject, not reflective entities.
