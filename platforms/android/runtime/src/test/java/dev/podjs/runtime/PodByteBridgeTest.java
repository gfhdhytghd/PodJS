package dev.podjs.runtime;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

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
    @Test public void nativeByteReadKeepsBaselineCodesAcrossTheTransportErrorTable()throws Exception {
        InputStream resource=getClass().getResourceAsStream("/transport-error-cases.json");
        assertNotNull("transport error table",resource);
        JSONArray cases;
        try(Scanner reader=new Scanner(resource,"UTF-8")){
            cases=new JSONArray(reader.useDelimiter("\\A").next());
        }
        LinkedBlockingQueue<String> results=new LinkedBlockingQueue<>();
        try(PodServices host=new PodServices(ApplicationProvider.getApplicationContext(),results::add)){
            host.attachByteBridge(new PodServices.ByteBridge(){
                public byte[] take(int id){throw new AssertionError("unexpected input attachment");}
                public int put(byte[] bytes){throw new AssertionError("unexpected output attachment");}
                public void drop(int id){throw new AssertionError("unexpected dropped attachment");}
            });
            PodByteStreamServices streams=ReflectionHelpers.getField(host,"byteStreams");
            for(int i=0;i<cases.length();i++){
                JSONObject row=cases.getJSONObject(i);
                if(row.isNull("exception"))continue; // Guest and WebView codes do not pass through the Java exception catch.
                String name=row.getString("name"),code=row.getString("baselineCode");
                String message=row.isNull("message")?null:row.getString("message");
                Exception failure=Class.forName(row.getString("exception")).asSubclass(Exception.class)
                        .getConstructor(String.class).newInstance(message);
                String id=streams.attach(new InputStream(){
                    @Override public int read()throws IOException{
                        if(failure instanceof IOException)throw (IOException)failure;
                        throw (RuntimeException)failure;
                    }
                },new ByteArrayOutputStream(),()->{});
                int requestId=i+1;
                host.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",requestId)
                        .put("method","stream.read.bytes").put("args",args(id,1)));

                long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(results.isEmpty()&&System.nanoTime()<expires){
                    shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(1);
                }
                String line=results.poll();
                assertNotNull(name,line);
                JSONObject reply=new JSONObject(line);
                assertEquals(name,"service.result",reply.getString("t"));
                assertEquals(name,requestId,reply.getInt("id"));
                assertFalse(name,reply.getBoolean("ok"));
                assertEquals(name,code,reply.getString("code"));
                assertEquals(name,message==null?failure.getClass().getSimpleName():message,reply.getString("message"));
                assertEquals(name,!code.equals(row.getString("r15Code")),reply.optBoolean("nativeTransportFailure"));
            }
        }
    }
    private static JSONObject args(String id,int max)throws Exception{return new JSONObject().put("id",id).put("maxBytes",max).put("timeoutMs",1000);}
}
