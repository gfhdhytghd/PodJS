package dev.podjs.runtime;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
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
        for(String name:new String[]{"r1-legacy","r1-expanded","r1-fallback","exact-limit","newline-overflow","newline-fallback","r2-appendix"}) {
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
                    for(Read reader:new Read[]{background,sync,font,takeover}) {
                        if(bytes.length<=PodManifest.MAX_BYTES)reader.run();else rejects(reader);
                    }
                } finally { assets.close(); }
            } finally { Files.deleteIfExists(apk); }
        }
    }
}
