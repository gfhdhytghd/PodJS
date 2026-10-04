package dev.podjs.runtime;

import android.content.Context;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class PodFilePublicationTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private File file(String path) { return new File(context().getFilesDir(),"podjs/files/"+path); }
    private String temporary(PodDeviceServices s) throws Exception { return s.execute("file.createTemp",new JSONObject().put("directory","publication-test")).getString("path"); }
    private String digest(byte[] b) throws Exception { StringBuilder out=new StringBuilder(); for(byte v:MessageDigest.getInstance("SHA-256").digest(b))out.append(String.format("%02x",v&255));return out.toString(); }
    private JSONObject write(String p,byte[] b) throws Exception { return new JSONObject().put("path",p).put("dataBase64",Base64.encodeToString(b,Base64.NO_WRAP)); }
    private JSONObject commit(String p,String target,byte[] b) throws Exception { return new JSONObject().put("path",p).put("target",target).put("expectedSize",b.length).put("expectedSha256",digest(b)); }

    @Test public void multiMegabytePublicationIsVerifiedAndReusesMatchingTarget() throws Exception {
        byte[] bytes=new byte[2*1024*1024+19];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*17);
        String target="publication-test/final.bin";
        try(PodDeviceServices s=new PodDeviceServices(context())){
            for(int attempt=0;attempt<2;attempt++){
                String path=temporary(s);
                for(int offset=0;offset<bytes.length;offset+=65536){
                    byte[] part=java.util.Arrays.copyOfRange(bytes,offset,Math.min(offset+65536,bytes.length));
                    JSONObject result=s.execute("file.write",write(path,part).put("append",true).put("expectedOffset",offset));
                    assertEquals(offset+part.length,result.getLong("size"));
                }
                JSONObject h=s.execute("file.hash",new JSONObject().put("path",path));
                assertEquals(bytes.length,h.getLong("size"));assertEquals(digest(bytes),h.getString("sha256"));
                JSONObject result=s.execute("file.commit",commit(path,target,bytes));
                assertEquals(attempt==1,result.getBoolean("reused"));assertFalse(file(path).exists());
                assertArrayEquals(bytes,Files.readAllBytes(file(target).toPath()));
            }
        }finally{file(target).delete();}
    }
    @Test public void rejectedAppendAndChecksumNeverDamagePublishedFile() throws Exception {
        byte[] old="old complete body".getBytes("UTF-8"),replacement="new body".getBytes("UTF-8");String target="publication-test/old.bin";
        try(PodDeviceServices s=new PodDeviceServices(context())){
            s.execute("file.write",write(target,old));String path=temporary(s);s.execute("file.write",write(path,replacement));
            for(Object offset:new Object[]{0,-1,0.5,"8",Double.NaN}){
                try{s.execute("file.write",write(path,new byte[]{1}).put("append",true).put("expectedOffset",offset));fail("invalid append accepted");}catch(Exception expected){}
                assertArrayEquals(replacement,Files.readAllBytes(file(path).toPath()));
            }
            for(JSONObject args:new JSONObject[]{commit(path,target,replacement).put("expectedSize",0),commit(path,target,replacement).put("expectedSha256",digest(old)),commit(path,target,replacement).put("expectedSize",0.5),commit(path,target,replacement).put("target","../outside")}){
                try{s.execute("file.commit",args);fail("invalid commit accepted");}catch(Exception expected){}
                assertArrayEquals(old,Files.readAllBytes(file(target).toPath()));assertTrue(file(path).isFile());
            }
            s.execute("file.commit",commit(path,target,replacement));
            assertArrayEquals(replacement,Files.readAllBytes(file(target).toPath()));
        }finally{file(target).delete();}
    }
    @Test public void temporaryHandlesAreBoundedAndCleanedOnCloseOrCancelledReply() throws Exception {
        String[] paths=new String[8];PodDeviceServices s=new PodDeviceServices(context());
        for(int i=0;i<8;i++)paths[i]=temporary(s);
        try{s.execute("file.createTemp",new JSONObject().put("directory","publication-test"));fail("unbounded staging");}catch(IllegalStateException expected){}
        s.discardTemporary(new JSONObject().put("path",paths[0]));assertFalse(file(paths[0]).exists());
        paths[0]=temporary(s);s.close();for(String p:paths)assertFalse(file(p).exists());
    }
    @Test public void changedStagedFileIsRecheckedBeforeCommitAndEmptyFileWorks() throws Exception {
        String target="publication-test/empty.bin";
        try(PodDeviceServices s=new PodDeviceServices(context())){
            String path=temporary(s);JSONObject first=s.execute("file.hash",new JSONObject().put("path",path));assertEquals(digest(new byte[0]),first.getString("sha256"));
            s.execute("file.write",write(path,new byte[]{1}));
            try{s.execute("file.commit",commit(path,target,new byte[0]));fail("stale hash accepted");}catch(java.io.IOException expected){}
            assertFalse(file(target).exists());
            s.execute("file.write",write(path,new byte[0]));s.execute("file.commit",commit(path,target,new byte[0]));
            assertTrue(file(target).isFile());assertEquals(0,file(target).length());
        }finally{file(target).delete();}
    }
}
