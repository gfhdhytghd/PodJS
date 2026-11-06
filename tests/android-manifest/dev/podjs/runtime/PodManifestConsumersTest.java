package dev.podjs.runtime;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.pm.ApplicationInfo;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real installed-package readers. Registered by test-package-manifest.sh,
 * which supplies the actual build-output fixtures; not a standalone unit suite. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,application=Application.class)
public class PodManifestConsumersTest {
    interface Read { void run()throws Exception; }
    static void rejects(Read read)throws Exception {
        try { read.run();fail("oversized manifest accepted"); }
        catch(InvocationTargetException wrapped) {
            assertTrue(wrapped.getCause() instanceof IllegalArgumentException);
            assertEquals("Manifest too large",wrapped.getCause().getMessage());
        }
        catch(IllegalArgumentException expected) { assertEquals("Manifest too large",expected.getMessage()); }
    }
    @Test public void legacyReadableSharedOverflowAndNewlineContractAcrossConsumers()throws Exception {
        Path cases=Path.of(System.getenv("PODJS_MANIFEST_CASES"));
        Context base=ApplicationProvider.getApplicationContext();
        Method fonts=PodPakFontAssets.class.getDeclaredMethod("manifest",AssetManager.class);fonts.setAccessible(true);
        for(String name:new String[]{"r1-legacy","r1-expanded","r1-fallback","exact-limit","newline-overflow","newline-fallback","r2-appendix","r3-legacy-200k"}) {
            byte[] bytes=Files.readAllBytes(cases.resolve(name+".json"));
            Path apk=Files.createTempFile("manifest-reader-",".apk");
            try {
                try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(apk))) {
                    zip.putNextEntry(new ZipEntry("assets/pod.manifest.json"));zip.write(bytes);zip.closeEntry();
                }
                AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
                AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,apk.toString());
                try {
                    Context context=new ContextWrapper(base){@Override public AssetManager getAssets(){return assets;}};
                    Read background=()->assertNotNull(PodBackgroundPackage.installed(context,"android-watch"));
                    // Empty declared sync capabilities avoid creating business state or connections.
                    Read sync=()->assertNull(PodInstalledSync.open(context,"android-watch"));
                    Read font=()->assertEquals("android-watch",((JSONObject)fonts.invoke(null,assets)).getString("target"));
                    Read takeover=()->assertNull(PodWorkManagerTakeover.readConfig(new ByteArrayInputStream(bytes),false,base.getPackageName()));
                    // Background/sync retain 128 KiB; font and old scheduler keep
                    // their wider historical contracts, independent of build output.
                    for(Read reader:new Read[]{background,sync}) {
                        if(bytes.length<=PodManifest.MAX_BYTES)reader.run();else rejects(reader);
                    }
                    if(bytes.length<=2*1024*1024)font.run();else {
                        try {font.run();fail("oversized font metadata accepted");}
                        catch(InvocationTargetException wrapped) {
                            assertEquals("font manifest exceeds limit",wrapped.getCause().getMessage());
                        }
                    }
                    if(bytes.length<=1024*1024)takeover.run();else {
                        try {takeover.run();fail("oversized legacy scheduler metadata accepted");}
                        catch(java.io.IOException expected) {assertEquals("legacy_config_invalid",expected.getMessage());}
                    }
                } finally { assets.close(); }
            } finally { Files.deleteIfExists(apk); }
        }
    }
    static byte[] padded(JSONObject value,int length)throws Exception {
        byte[] json=value.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(json.length<length);
        byte[] bytes=Arrays.copyOf(json,length);
        Arrays.fill(bytes,json.length,length-1,(byte)' ');bytes[length-1]='\n';
        return bytes;
    }
    @Test public void historicalFontSchedulerBoundsAndInstalledFallback()throws Exception {
        Path cases=Path.of(System.getenv("PODJS_MANIFEST_CASES"));
        Context base=ApplicationProvider.getApplicationContext();
        JSONObject original=new JSONObject(new String(Files.readAllBytes(cases.resolve("r3-legacy-200k.json")),StandardCharsets.UTF_8));
        original.getJSONObject("androidWorkManagerTakeover").put("nativeAppId",base.getPackageName());
        Method fonts=PodPakFontAssets.class.getDeclaredMethod("manifest",AssetManager.class);fonts.setAccessible(true);
        Method installed=PodWorkManagerTakeover.class.getDeclaredMethod("installed",Context.class);installed.setAccessible(true);
        for(int length:new int[]{131072,131073,204800,1048576,1048577,2097152,2097153}) {
            byte[] bytes=padded(original,length);
            Path apk=Files.createTempFile("legacy-manifest-",".apk");
            try {
                try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(apk))) {
                    // Deliberately omit pod.workmanager.json: exercise the old-package fallback.
                    zip.putNextEntry(new ZipEntry("assets/pod.manifest.json"));zip.write(bytes);zip.closeEntry();
                }
                AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
                AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,apk.toString());
                try {
                    ApplicationInfo info=new ApplicationInfo(base.getApplicationInfo());
                    info.dataDir=apk.toString(); // Unique cache key; no business-state writes.
                    Context context=new ContextWrapper(base){
                        @Override public AssetManager getAssets(){return assets;}
                        @Override public ApplicationInfo getApplicationInfo(){return info;}
                    };
                    if(length<=2*1024*1024)assertEquals("android-watch",((JSONObject)fonts.invoke(null,assets)).getString("target"));
                    else {
                        try {fonts.invoke(null,assets);fail("oversized font metadata accepted");}
                        catch(InvocationTargetException wrapped) {assertEquals("font manifest exceeds limit",wrapped.getCause().getMessage());}
                    }
                    if(length<=1024*1024) {
                        PodWorkManagerTakeover.Config config=(PodWorkManagerTakeover.Config)installed.invoke(null,context);
                        assertNotNull("legacy fallback must not report legacy_config_invalid at "+length,config);
                        assertEquals(base.getPackageName(),config.appId);
                        assertEquals("com.example.LegacyWorker",config.classes.get(0));
                    } else {
                        try {installed.invoke(null,context);fail("oversized legacy metadata accepted");}
                        catch(InvocationTargetException wrapped) {assertEquals("legacy_config_invalid",wrapped.getCause().getMessage());}
                    }
                } finally {assets.close();}
            } finally {Files.deleteIfExists(apk);}
        }
        JSONObject compact=new JSONObject().put("protocol",1).put("androidWorkManagerTakeover",original.getJSONObject("androidWorkManagerTakeover"));
        assertNotNull(PodWorkManagerTakeover.readConfig(new ByteArrayInputStream(padded(compact,65536)),true,base.getPackageName()));
        try {PodWorkManagerTakeover.readConfig(new ByteArrayInputStream(padded(compact,65537)),true,base.getPackageName());fail("oversized compact metadata accepted");}
        catch(java.io.IOException expected) {assertEquals("legacy_config_invalid",expected.getMessage());}
    }

}
