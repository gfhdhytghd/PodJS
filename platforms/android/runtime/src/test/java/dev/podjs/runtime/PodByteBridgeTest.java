package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** JVM-only checks of the actual stream IO and byte envelope, with no radio/device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodByteBridgeTest {
    @Test public void streamReadDifferentialAndEof()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            for(int n:new int[]{1,65535,65536}){
                byte[] bytes=new byte[n];for(int i=0;i<n;i++)bytes[i]=(byte)i;
                String old=streams.attach(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),()->{});
                String raw=streams.attach(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),()->{});
                JSONObject a=args(old,n),b=args(raw,n).put("_podByteMode",true);
                JSONObject legacy=(JSONObject)streams.execute("stream.read",a,new CancellationSignal());
                JSONObject binary=(JSONObject)streams.execute("stream.read",b,new CancellationSignal());
                assertFalse(binary.has("dataBase64"));assertArrayEquals(android.util.Base64.decode(legacy.getString("dataBase64"),0),(byte[])binary.get("_podBytes"));
                JSONObject eof=(JSONObject)streams.execute("stream.read",b,new CancellationSignal());assertTrue(eof.getBoolean("eof"));assertEquals(0,((byte[])eof.get("_podBytes")).length);
                streams.execute("stream.close",new JSONObject().put("id",old),new CancellationSignal());
            }
        }
    }
    @Test public void writeDifferentialAndCancellation()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        try(PodByteStreamServices streams=new PodByteStreamServices(context)){
            for(int n:new int[]{1,65536}){
                byte[] bytes=new byte[n];Arrays.fill(bytes,(byte)255);ByteArrayOutputStream a=new ByteArrayOutputStream(),b=new ByteArrayOutputStream();
                String old=streams.attach(new ByteArrayInputStream(bytes),a,()->{}),raw=streams.attach(new ByteArrayInputStream(bytes),b,()->{});
                streams.execute("stream.write",args(old,n).put("dataBase64",android.util.Base64.encodeToString(bytes,2)),new CancellationSignal());
                streams.execute("stream.write",args(raw,n).put("_podByteMode",true).put("_podBytes",bytes),new CancellationSignal());
                assertArrayEquals(a.toByteArray(),b.toByteArray());
                streams.execute("stream.close",new JSONObject().put("id",old),new CancellationSignal());streams.execute("stream.close",new JSONObject().put("id",raw),new CancellationSignal());
            }
            String id=streams.attach(new ByteArrayInputStream(new byte[1]),new ByteArrayOutputStream(),()->{});CancellationSignal cancellation=new CancellationSignal();cancellation.cancel();
            try{streams.execute("stream.read",args(id,1).put("_podByteMode",true),cancellation);fail();}catch(android.os.OperationCanceledException expected){}
        }
    }
    @Test public void emptyPayloadAndOversizeAreDistinct()throws Exception {
        assertEquals(0,PodBytePayload.input(new JSONObject().put("_podByteMode",true).put("_podBytes",new byte[0])).length);
        for(Object bad:new Object[]{"wrong",new byte[65537]})try{PodBytePayload.input(new JSONObject().put("_podByteMode",true).put("_podBytes",bad));fail();}catch(IllegalArgumentException expected){}
    }
    private static JSONObject args(String id,int max)throws Exception{return new JSONObject().put("id",id).put("maxBytes",max).put("timeoutMs",1000);}
}
