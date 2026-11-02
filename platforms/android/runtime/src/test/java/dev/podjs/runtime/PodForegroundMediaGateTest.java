package dev.podjs.runtime;

import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

/** Real production service dispatch; no restored account, network or device. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @LooperMode(LooperMode.Mode.PAUSED)
public class PodForegroundMediaGateTest {
    private static void drain(int millis)throws Exception {
        for(int i=0;i<millis;i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);}
    }
    private static void query(PodServices host,int id)throws Exception {
        host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method","uri.parse").put("args",new JSONObject().put("value","https://fixture.invalid/video")));
    }
    @Test public void foregroundRequestsPassWhileHeadlessRequestsWaitAndResumeWithoutReplay()throws Exception {
        Object owner=new Object();List<String> bg=Collections.synchronizedList(new ArrayList<>()),fg=Collections.synchronizedList(new ArrayList<>());
        try(PodServices background=new PodServices(ApplicationProvider.getApplicationContext(),bg::add);PodServices foreground=new PodServices(ApplicationProvider.getApplicationContext(),fg::add)){
            background.headlessWork();HostWorkPriority.DEVICE.foregroundMedia(owner,true);
            query(background,1);query(foreground,2);drain(100);
            assertEquals(0,bg.size());assertEquals(1,fg.size());assertEquals(2,new JSONObject(fg.get(0)).getInt("id"));assertTrue(new JSONObject(fg.get(0)).getBoolean("ok"));
            HostWorkPriority.DEVICE.foregroundMedia(owner,false);drain(100);
            assertEquals(1,bg.size());assertEquals(1,new JSONObject(bg.get(0)).getInt("id"));assertTrue(new JSONObject(bg.get(0)).getBoolean("ok"));
            drain(20);assertEquals(1,bg.size());
        }finally{HostWorkPriority.DEVICE.foregroundMedia(owner,false);shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void cancellationDuringMediaWaitCannotWriteALateReply()throws Exception {
        Object owner=new Object();List<String> replies=Collections.synchronizedList(new ArrayList<>());
        try(PodServices background=new PodServices(ApplicationProvider.getApplicationContext(),replies::add)){
            background.headlessWork();HostWorkPriority.DEVICE.foregroundMedia(owner,true);query(background,3);drain(20);
            background.dispatch(new JSONObject().put("t","service.cancel").put("id",3));HostWorkPriority.DEVICE.foregroundMedia(owner,false);drain(100);assertTrue(replies.isEmpty());
        }finally{HostWorkPriority.DEVICE.foregroundMedia(owner,false);shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test(timeout=15000) public void secureReceiptAfterReadPrefixSurvivesMediaPauseAndServiceRestart()throws Exception {
        // Real dispatcher, encrypted atomic secure.update and admission lane.
        // Only the key source/files are synthetic; no AndroidKeyStore/user data.
        Object owner=new Object();List<String> replies=Collections.synchronizedList(new ArrayList<>());
        java.io.File directory=java.nio.file.Files.createTempDirectory("restore-receipt-").toFile();
        java.io.File files=new java.io.File(directory,"files");
        javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        try(PodServices background=new PodServices(ApplicationProvider.getApplicationContext(),replies::add)){
            java.lang.reflect.Field field=PodServices.class.getDeclaredField("device");field.setAccessible(true);
            ((PodDeviceServices)field.get(background)).close();field.set(background,new PodDeviceServices(files,()->key));
            background.headlessWork();
            for(int id=1;id<=488;id++){
                background.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method","secure.get").put("args",new JSONObject().put("key","fixture")));
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while(replies.size()<id&&System.nanoTime()<until)drain(1);
                assertEquals(id,replies.size());assertTrue(new JSONObject(replies.get(id-1)).getBoolean("ok"));
            }
            HostWorkPriority.DEVICE.foregroundMedia(owner,true);
            background.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",489).put("method","secure.update").put("args",new JSONObject().put("values",new JSONObject().put("fixture","receipt")).put("removePrefixes",new org.json.JSONArray())));
            drain(30);assertEquals(488,replies.size());
            HostWorkPriority.DEVICE.foregroundMedia(owner,false);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(replies.size()<489&&System.nanoTime()<until)drain(1);
            assertEquals(489,replies.size());assertTrue(new JSONObject(replies.get(488)).getBoolean("ok"));
        }finally{HostWorkPriority.DEVICE.foregroundMedia(owner,false);shadowOf(Looper.getMainLooper()).idle();}
        try(PodDeviceServices reopened=new PodDeviceServices(files,()->key)){
            assertEquals("receipt",reopened.execute("secure.get",new JSONObject().put("key","fixture")).getString("value"));
        }finally{
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{java.nio.file.Files.delete(path);}catch(java.io.IOException error){throw new RuntimeException(error);}});}
        }
    }
}
