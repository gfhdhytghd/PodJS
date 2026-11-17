package dev.podjs.runtime;
import android.content.*;
import android.os.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.concurrent.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PodShellReadonlyTest {
 @Test public void coldDiagnosticsNeverLaunchOrChangeEmptyMigrationRecoveryDatabases() throws Exception {
  Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
  for(String fixture:new String[]{"empty","migration_pending","recovery_pending"}){
   java.io.File file=new java.io.File(context.getCacheDir(),fixture+".db");SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);db.execSQL("CREATE TABLE IF NOT EXISTS fixture(value TEXT)");if(!fixture.equals("empty"))db.execSQL("INSERT INTO fixture VALUES ('pending')");db.close();byte[] before=java.nio.file.Files.readAllBytes(file.toPath());
   PodShellProvider provider=new PodShellProvider();provider.attachInfo(context,new android.content.pm.ProviderInfo());
   java.lang.reflect.Method method=PodShellProvider.class.getDeclaredMethod("authorizedCall",String.class,String.class);method.setAccessible(true);
   ExecutorService executor=Executors.newSingleThreadExecutor();
   try {for(String command:new String[]{"library.books","library.summary","sync.last"}){
    Future<Bundle> result=executor.submit(()->(Bundle)method.invoke(provider,command,"{}"));
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!result.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield();}
    assertEquals("not_ready",result.get(1,TimeUnit.SECONDS).getString("error"));
    assertNull(Shadows.shadowOf((android.app.Application)context).getNextStartedActivity());assertArrayEquals(before,java.nio.file.Files.readAllBytes(file.toPath()));
   }}finally{executor.shutdownNow();file.delete();}
  }
 }
}
