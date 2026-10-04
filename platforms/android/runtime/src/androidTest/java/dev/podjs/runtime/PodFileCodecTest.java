package dev.podjs.runtime;

import android.content.Context;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PodFileCodecTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private File file(String p){return new File(context().getFilesDir(),"podjs/files/"+p);}
    private String create(PodDeviceServices s,byte[] bytes)throws Exception{
        String path=s.execute("file.createTemp",new JSONObject().put("directory","codec-test")).getString("path");
        for(int offset=0;offset<bytes.length;offset+=65536){
            byte[] chunk=java.util.Arrays.copyOfRange(bytes,offset,Math.min(offset+65536,bytes.length));
            s.execute("file.write",new JSONObject().put("path",path).put("append",true).put("expectedOffset",offset).put("dataBase64",Base64.encodeToString(chunk,Base64.NO_WRAP)));
        }
        return path;
    }
    private JSONObject transcode(String path,long max)throws Exception{return new JSONObject().put("path",path).put("maxOutputBytes",max);}
    private void remove(PodDeviceServices s,String path)throws Exception{s.execute("file.delete",new JSONObject().put("path",path));}
    @Test public void gzipBytesAndDecodedTextMatchProductionAndroidReference()throws Exception{
        String json;try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("gzip-native-reference.json")){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);json=new String(out.toByteArray(),StandardCharsets.UTF_8);}
        JSONArray cases=new JSONObject(json).getJSONArray("cases");assertEquals(8,cases.length());
        try(PodDeviceServices s=new PodDeviceServices(context())){
            for(int i=0;i<cases.length();i++){
                JSONObject row=cases.getJSONObject(i);byte[] plain=Base64.decode(row.getString("jsonBase64"),Base64.DEFAULT),expected=Base64.decode(row.getString("encodedBase64"),Base64.DEFAULT);
                String source=create(s,plain);JSONObject zipped=s.execute("file.gzip",transcode(source,32*1024*1024));String compressed=zipped.getString("path");
                assertArrayEquals("compressed case "+i,expected,Files.readAllBytes(file(compressed).toPath()));
                if(plain.length<=64*1024){
                    JSONObject direct=s.execute("file.gzipBytes",new JSONObject().put("dataBase64",Base64.encodeToString(plain,Base64.NO_WRAP)));
                    assertArrayEquals("direct compressed case "+i,expected,Base64.decode(direct.getString("dataBase64"),Base64.DEFAULT));
                }
                JSONObject expanded=s.execute("file.gunzip",transcode(compressed,32*1024*1024));String output=expanded.getString("path");
                assertArrayEquals(plain,Files.readAllBytes(file(output).toPath()));
                String id=s.execute("file.openText",new JSONObject().put("path",output)).getString("id");StringBuilder text=new StringBuilder();
                while(true){JSONObject part=s.execute("file.readTextChunk",new JSONObject().put("id",id).put("maxChars",16384));text.append(part.getString("text"));if(part.getBoolean("eof"))break;}
                assertEquals(new String(plain,StandardCharsets.UTF_8),text.toString());
                assertFalse(s.execute("file.closeText",new JSONObject().put("id",id)).getBoolean("closed"));
                remove(s,source);remove(s,compressed);remove(s,output);
            }
        }
    }
    @Test public void decompressionLimitsAndCrcFailureRemoveOnlyOutput()throws Exception{
        try(PodDeviceServices s=new PodDeviceServices(context())){
            byte[] plain=new byte[200000];java.util.Arrays.fill(plain,(byte)'A');String source=create(s,plain);
            String compressed=s.execute("file.gzip",transcode(source,32*1024*1024)).getString("path");
            for(long maximum:new long[]{0,199999}){
                int before=file("codec-test").list().length;
                try{s.execute("file.gunzip",transcode(compressed,maximum));fail("expansion limit ignored");}catch(java.io.IOException expected){}
                assertEquals(before,file("codec-test").list().length);assertTrue(file(compressed).isFile());
            }
            String exact=s.execute("file.gunzip",transcode(compressed,200000)).getString("path");assertEquals(200000,file(exact).length());remove(s,exact);
            byte[] corrupt=Files.readAllBytes(file(compressed).toPath());corrupt[corrupt.length-8]^=1;Files.write(file(compressed).toPath(),corrupt);
            int before=file("codec-test").list().length;
            try{s.execute("file.gunzip",transcode(compressed,32*1024*1024).put("allowPlain",true));fail("CRC failure treated as plain data");}catch(java.io.IOException expected){}
            assertEquals(before,file("codec-test").list().length);assertArrayEquals(plain,Files.readAllBytes(file(source).toPath()));
        }
    }
    @Test public void plainFallbackAndStreamingUtf8HaveJavaReplacementAndUnsplitPairs()throws Exception{
        byte[] bytes=new byte[]{65,(byte)0xf0,(byte)0x9f,(byte)0x98,(byte)0x80,(byte)0xed,(byte)0xa0,(byte)0x80,(byte)0xe2,(byte)0x82,66};
        try(PodDeviceServices s=new PodDeviceServices(context())){
            String source=create(s,bytes),output=s.execute("file.gunzip",transcode(source,bytes.length).put("allowPlain",true)).getString("path");
            assertArrayEquals(bytes,Files.readAllBytes(file(output).toPath()));
            for(int max=1;max<=4;max++){
                String id=s.execute("file.openText",new JSONObject().put("path",output)).getString("id");StringBuilder text=new StringBuilder();
                while(true){JSONObject part=s.execute("file.readTextChunk",new JSONObject().put("id",id).put("maxChars",max));String value=part.getString("text");assertTrue(value.length()<=max+1);if(!value.isEmpty())assertFalse(Character.isHighSurrogate(value.charAt(value.length()-1)));text.append(value);if(part.getBoolean("eof"))break;}
                assertEquals(new String(bytes,StandardCharsets.UTF_8),text.toString());
            }
        }
    }
    @Test public void textReadersAreBoundedCancelledAndClosed()throws Exception{
        PodDeviceServices s=new PodDeviceServices(context());String source=create(s,new byte[]{65});String[] ids=new String[8];
        for(int i=0;i<8;i++)ids[i]=s.execute("file.openText",new JSONObject().put("path",source)).getString("id");
        try{s.execute("file.openText",new JSONObject().put("path",source));fail("unbounded readers");}catch(IllegalStateException expected){}
        s.discardTextReader(new JSONObject().put("id",ids[0]));
        try{s.execute("file.readTextChunk",new JSONObject().put("id",ids[0]));fail("cancelled reader kept");}catch(IllegalArgumentException expected){}
        ids[0]=s.execute("file.openText",new JSONObject().put("path",source)).getString("id");s.close();assertFalse(file(source).exists());
        try{s.execute("file.readTextChunk",new JSONObject().put("id",ids[0]));fail("closed service usable");}catch(IllegalStateException expected){}
    }
}
