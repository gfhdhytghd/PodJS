package dev.podjs.runtime;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import android.util.Base64;
import java.io.File;
import java.security.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;
/** Synthetic aliases and app-private temporary files only. */
public final class SyncV2SigningTest {
 @Test public void persistentSeparateKeyAndMissingKeySentinel()throws Exception {
  File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"syncv2-"+UUID.randomUUID()+"/files");assertTrue(directory.mkdirs());
  byte[] identity=new byte[32];new SecureRandom().nextBytes(identity);StringBuilder digest=new StringBuilder();for(byte b:identity)digest.append(String.format("%02x",b&255));String id=digest.toString(),alias="podjs.signing.v2."+id;
  KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);byte[] input={1,2,3};String data=Base64.encodeToString(input,Base64.NO_WRAP);
  try {
   PodDeviceServices first=new PodDeviceServices(directory,()->new SecretKeySpec(new byte[32],"AES"));
   try{first.execute("crypto.v2.sign",new JSONObject().put("keyIdSha256",id).put("dataBase64",data));fail("missing signer created a key");}catch(IllegalStateException expected){}
   String pub=first.execute("crypto.v2.signingKey",new JSONObject().put("keyIdSha256",id).put("create",true)).getString("publicKeySec1");first.close();
   PodDeviceServices second=new PodDeviceServices(directory,()->new SecretKeySpec(new byte[32],"AES"));
   assertEquals(pub,second.execute("crypto.v2.signingKey",new JSONObject().put("keyIdSha256",id)).getString("publicKeySec1"));
   String signature=second.execute("crypto.v2.sign",new JSONObject().put("keyIdSha256",id).put("dataBase64",data)).getString("signatureBase64");assertTrue(PodSyncV2Crypto.verify(Base64.decode(pub,Base64.DEFAULT),input,Base64.decode(signature,Base64.DEFAULT)));assertFalse(keys.containsAlias("podjs.ecdh.v1."+id));
   keys.deleteEntry(alias);try{second.execute("crypto.v2.signingKey",new JSONObject().put("keyIdSha256",id).put("create",true));fail("silently rotated missing signing key");}catch(IllegalStateException expected){}second.close();
  }finally{keys.deleteEntry(alias);keys.deleteEntry(alias+".created");for(File f:Objects.requireNonNull(directory.getParentFile().listFiles()))f.delete();directory.delete();directory.getParentFile().delete();}
 }
 @Test public void processCreate()throws Exception {
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();android.content.SharedPreferences prefs=c.getSharedPreferences("syncv2-process-fixture",0);
  byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);StringBuilder id=new StringBuilder();for(byte b:raw)id.append(String.format("%02x",b&255));
  File dir=new File(c.getCacheDir(),"syncv2-process/files");dir.mkdirs();PodDeviceServices service=new PodDeviceServices(dir,()->new SecretKeySpec(new byte[32],"AES"));String pub=service.execute("crypto.v2.signingKey",new JSONObject().put("keyIdSha256",id.toString()).put("create",true)).getString("publicKeySec1");service.close();
  assertTrue(prefs.edit().putString("id",id.toString()).putString("pub",pub).putInt("pid",android.os.Process.myPid()).commit());
 }
 @Test public void processReadAndRemove()throws Exception {
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();android.content.SharedPreferences prefs=c.getSharedPreferences("syncv2-process-fixture",0);String id=prefs.getString("id",null);assertNotNull(id);assertNotEquals(prefs.getInt("pid",0),android.os.Process.myPid());
  PodDeviceServices service=new PodDeviceServices(new File(c.getCacheDir(),"syncv2-process/files"),()->new SecretKeySpec(new byte[32],"AES"));
  try{String pub=service.execute("crypto.v2.signingKey",new JSONObject().put("keyIdSha256",id)).getString("publicKeySec1");assertEquals(prefs.getString("pub",null),pub);String data=Base64.encodeToString(new byte[]{3,2,1},Base64.NO_WRAP),sig=service.execute("crypto.v2.sign",new JSONObject().put("keyIdSha256",id).put("dataBase64",data)).getString("signatureBase64");assertTrue(PodSyncV2Crypto.verify(Base64.decode(pub,Base64.DEFAULT),new byte[]{3,2,1},Base64.decode(sig,Base64.DEFAULT)));}
  finally{service.close();KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("podjs.signing.v2."+id);keys.deleteEntry("podjs.signing.v2."+id+".created");prefs.edit().clear().commit();}
 }

}
