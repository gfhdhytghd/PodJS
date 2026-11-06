package dev.podjs.runtime;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class PodContentCryptoTest {
 @Test public void v2DecryptAcceptsFullBlobPlusTagWithoutChangingLegacyLimit()throws Exception{
  File root=Files.createTempDirectory("content-crypto-").toFile();byte[] key=new byte[32],nonce=new byte[12],plain=new byte[262144];Arrays.fill(plain,(byte)7);
  try(PodDeviceServices host=new PodDeviceServices(root,()->new SecretKeySpec(key,"AES"))){String k=Base64.encodeToString(key,Base64.NO_WRAP),n=Base64.encodeToString(nonce,Base64.NO_WRAP);JSONObject a=new JSONObject().put("keyBase64",k).put("nonceBase64",n).put("aadBase64","").put("dataBase64",Base64.encodeToString(plain,Base64.NO_WRAP));String sealed=host.execute("crypto.v2.encrypt",a).getString("ciphertextBase64");a.put("ciphertextBase64",sealed);assertEquals(262160,Base64.decode(sealed,Base64.DEFAULT).length);assertArrayEquals(plain,Base64.decode(host.execute("crypto.v2.decrypt",a).getString("dataBase64"),Base64.DEFAULT));assertThrows(IllegalArgumentException.class,()->host.execute("crypto.aesGcmDecrypt",a));a.put("ciphertextBase64",Base64.encodeToString(new byte[262161],Base64.NO_WRAP));assertThrows(IllegalArgumentException.class,()->host.execute("crypto.v2.decrypt",a));}
  finally{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(p);}}
 }
}
