package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class PodSecureReadOnlyTest {
 private Path root;
 @Before public void setup()throws Exception{root=Files.createTempDirectory("pod-secure-readonly-");}
 @After public void cleanup()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(p);}}
 private JSONObject args()throws Exception{return new JSONObject().put("key","identity");}
 private byte[] store()throws Exception{byte[] nonce=new byte[12];Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(new byte[32],"AES"),new GCMParameterSpec(128,nonce));c.updateAAD("PodJS-device-services-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));byte[] encrypted=c.doFinal("{\"identity\":\"synthetic\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),bytes=new byte[12+encrypted.length];System.arraycopy(encrypted,0,bytes,12,encrypted.length);Files.write(root.resolve("secure.store"),bytes);return bytes;}
 @Test public void missingStoreReadNeverCreatesLockStoreOrKey()throws Exception{int[] keys={0};try(PodDeviceServices h=new PodDeviceServices(root.resolve("files").toFile(),()->{keys[0]++;throw new IOException("unexpected_key_creation");})){assertFalse(h.execute("secure.get",args()).getBoolean("exists"));assertEquals(0,keys[0]);assertFalse(Files.exists(root.resolve("secure.store.lock")));assertFalse(Files.exists(root.resolve("secure.store")));}}
 @Test public void coldExistingReadUsesOnlyExistingKeySourceAndLeavesFilesUnchanged()throws Exception{byte[] before=store();int[] writes={0},reads={0};try(PodDeviceServices h=new PodDeviceServices(root.resolve("files").toFile(),()->{writes[0]++;throw new IOException("unexpected_key_creation");},()->{reads[0]++;return new SecretKeySpec(new byte[32],"AES");})){assertEquals("synthetic",h.execute("secure.get",args()).getString("value"));assertEquals(0,writes[0]);assertEquals(1,reads[0]);assertArrayEquals(before,Files.readAllBytes(root.resolve("secure.store")));assertFalse(Files.exists(root.resolve("secure.store.lock")));}}
 @Test public void lostExistingKeyFailsWithoutCallingKeyCreatorOrChangingFiles()throws Exception{byte[] before=store();int[] writes={0};try(PodDeviceServices h=new PodDeviceServices(root.resolve("files").toFile(),()->{writes[0]++;return new SecretKeySpec(new byte[32],"AES");},()->{throw new IOException("secure_key_missing");})){assertThrows(IOException.class,()->h.execute("secure.get",args()));assertEquals(0,writes[0]);assertArrayEquals(before,Files.readAllBytes(root.resolve("secure.store")));assertFalse(Files.exists(root.resolve("secure.store.lock")));}}
 @Test public void missingSigningKeyReadNeverCreatesLockFileOrIdentity()throws Exception{try(PodDeviceServices h=new PodDeviceServices(root.resolve("files").toFile(),()->{throw new IOException("unexpected_key_creation");})){assertThrows(Exception.class,()->h.execute("crypto.v2.signingKey",new JSONObject().put("keyIdSha256",String.join("",Collections.nCopies(64,"c"))).put("create",false)));assertFalse(Files.exists(root.resolve("secure.store.lock")));assertFalse(Files.exists(root.resolve("secure.store")));}}
}
