# Invoked by name from jni_bridge.cpp with one reused 64 KiB byte array.
-keepclassmembers class dev.podjs.runtime.PodPakReader {
    int readAt(long, byte[], int);
}
