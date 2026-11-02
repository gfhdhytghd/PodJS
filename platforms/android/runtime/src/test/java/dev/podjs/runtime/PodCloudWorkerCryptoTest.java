package dev.podjs.runtime;

import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise the worker permission boundary with the production in-memory codec,
 * using the same wire fields and 32 KiB cutoff as cloud records/cache callers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class PodCloudWorkerCryptoTest {
    @Test public void deadlinesAndCheckpointsRetryButGuestFailuresStop()throws Exception {
        for(int attempt=0;attempt<4;attempt++) {
            for(String code:new String[]{"cancelled","script_exception"})
                assertEquals(androidx.work.ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject().put("status","failure").put("code",code),attempt).getClass());
            assertEquals(androidx.work.ListenableWorker.Result.retry().getClass(),PodCloudWorker.resultFor(new JSONObject().put("status","retry").put("code","completed"),attempt).getClass());
        }
        assertEquals(androidx.work.ListenableWorker.Result.success().getClass(),PodCloudWorker.resultFor(new JSONObject().put("status","success").put("code","completed"),0).getClass());
        for(String status:new String[]{"success","retry","failure"})
            assertEquals(androidx.work.ListenableWorker.Result.retry().getClass(),PodCloudWorker.resultFor(new JSONObject().put("status",status).put("code","deadline_exceeded"),20).getClass());
        assertEquals(androidx.work.ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject(),0).getClass());
    }
    private static String b64(byte[] bytes){return Base64.encodeToString(bytes,Base64.NO_WRAP);}
    private static JSONObject call(PodDeviceServices host,String method,JSONObject args)throws Exception {
        if(!PodCloudWorker.METHODS.contains(method))throw new SecurityException("permission_denied");
        return host.execute(method,args);
    }
    @Test public void workerRoundTripsEmptyAndBoundaryRecordsAndStillAuthenticates()throws Exception {
        try(PodDeviceServices host=new PodDeviceServices(ApplicationProvider.getApplicationContext())) {
            for(int size:new int[]{0,1,32768}) {
                byte[] input=new byte[size];for(int i=0;i<size;i++)input[i]=(byte)i;
                JSONObject args=new JSONObject().put("keyBase64",b64(new byte[32])).put("aadBase64",b64(new byte[]{1,2,3}));
                JSONObject encrypted=call(host,"crypto.aesGcmEncrypt",new JSONObject(args.toString()).put("dataBase64",b64(input)));
                JSONObject read=new JSONObject(args.toString()).put("nonceBase64",encrypted.getString("nonceBase64")).put("ciphertextBase64",encrypted.getString("ciphertextBase64"));
                assertArrayEquals(input,Base64.decode(call(host,"crypto.aesGcmDecrypt",read).getString("dataBase64"),Base64.DEFAULT));
                read.put("aadBase64",b64(new byte[]{9}));
                try{call(host,"crypto.aesGcmDecrypt",read);fail("unauthenticated record accepted");}catch(javax.crypto.AEADBadTagException expected){}
            }
            for(String denied:new String[]{"crypto.encrypt","crypto.aesGcmDecrypt/unsafe","net.ble.provision","app.openUrl"}) {
                try{call(host,denied,new JSONObject());fail("unapproved method accepted");}catch(SecurityException expected){assertEquals("permission_denied",expected.getMessage());}
            }
        }
    }
}
