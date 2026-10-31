package android.util;
/** JVM-only Android Base64 API shim. No Android/device behavior is claimed. */
public final class Base64 {
 public static final int DEFAULT=0,NO_WRAP=2;
 public static byte[] decode(String value,int flags){return java.util.Base64.getMimeDecoder().decode(value);}
 public static String encodeToString(byte[] value,int flags){return java.util.Base64.getEncoder().encodeToString(value);}
}
