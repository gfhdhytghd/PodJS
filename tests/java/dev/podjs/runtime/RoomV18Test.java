package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.json.*;

public final class RoomV18Test {
    static final class Jdbc implements PodRoomV18Converter.Db {
        final Connection c;
        Jdbc(File f)throws Exception {c=DriverManager.getConnection("jdbc:sqlite:"+f);}
        public void exec(String sql,Object... args)throws Exception {try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.execute();}}
        public List<Map<String,Object>> query(String sql,Object... args)throws Exception {
            try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(ResultSet rs=p.executeQuery()){
                List<Map<String,Object>> out=new ArrayList<>();while(rs.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnName(i),rs.getObject(i));out.add(row);}return out;
            }}
        }
        public void close()throws Exception{c.close();}
    }
    static File child(File parent,String name)throws Exception{File f=new File(parent,name);Files.createDirectories(f.toPath());return f;}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void fixture(Jdbc db)throws Exception {
        JSONObject c=new JSONObject(PodRoomV18Converter.resource("room-v18.json"));JSONArray sql=c.getJSONArray("sql");for(int i=0;i<sql.length();i++)db.exec(sql.getString(i));db.exec("PRAGMA user_version=18");
        for(String table:c.getJSONObject("tables").keySet()) {
            if(table.equals("room_master_table"))continue;
            JSONArray cols=c.getJSONObject("tables").getJSONArray(table);
            for(int n=1;n<=3;n++){
                Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<cols.length();i++){
                    JSONObject col=cols.getJSONObject(i);String name=col.getString("name"),type=col.getString("type");
                    Object value=type.equals("TEXT")?name+"-"+n:type.equals("REAL")?(double)n/10:n;
                    if(col.getInt("notnull")==0 && col.getInt("pk")==0)value=null;
                    r.put(name,value);
                }
                if(r.containsKey("deleted"))r.put("deleted",n==3?1:0);
                if(r.containsKey("isPinned"))r.put("isPinned",n==1?1:0);
                if(r.containsKey("saveType"))r.put("saveType",n==1?"FAVORITE":n==2?"WATCH_LATER":"ARTICLE_DELETE");
                if(table.equals("saved_entries")&&n==3)r.put("saveType","FAVORITE");
                if(table.equals("saved_sync_states")){r.put("itemId",n==2?999:n);r.put("saved",n==1?1:0);}
                if(table.equals("rss_items")){
                    r.put("isRead",n==1?1:0);r.put("isLiked",0);r.put("content",n==1?"watchrss-watch-content:fixture.html":"body "+n);r.put("originalContent",n==2?"<p>"+String.join("",Collections.nCopies(10000,"合成正文😀"))+"</p>":null);r.put("summary",n==1?"synthetic":null);
                }
                if(table.equals("offline_media")){r.put("mediaType","IMAGE");r.put("localPath",n==1?null:"/data/user/0/com.lightningstudio.watchrss/files/offline/rss/"+n+"/image.jpg");}
                if(table.equals("watch_notes")){r.put("folderId",n==1?"orphan-folder":"folderId-"+n);r.put("deleted",n==3?1:0);}
                if(table.equals("llm_token_usage")&&n==2){r.put("promptTokens",0);r.put("cachedPromptTokens",7);r.put("totalTokenCount",22);}
                if(table.equals("reader_presets"))r.put("payloadJson",n==3?"undecodable-retained":"{}");
                if(table.equals("reader_background_assets"))r.put("variantsJson","[]");
                if(table.equals("reader_font_assets"))r.put("metadataJson","{}");
                PodRoomV18Converter.insert(db,table,String.join(",",r.keySet()),r.values().toArray());
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length>1 && args[1].equals("crash")) {
            int at=Integer.parseInt(args[3]);boolean when=Boolean.parseBoolean(args[4]);int[] seen={0};
            new PodRoomV18Converter(new File("unused"),Jdbc::new,"unused",2,c->{if(c==when&&++seen[0]==at)Runtime.getRuntime().halt(91);}).copy(new File(args[2]));
            throw new AssertionError("crash not reached");
        }
        File root=new File(args[0]);Files.createDirectories(root.toPath());File source=new File(root,"synthetic-v18.sqlite");
        // Keep writer open: newest rows and schema version exist ONLY in WAL.
        try(Jdbc writer=new Jdbc(source)){
            writer.query("PRAGMA journal_mode=WAL");writer.exec("PRAGMA wal_autocheckpoint=0");fixture(writer);
            String dbHash=PodRoomV18Converter.hash(source),walHash=PodRoomV18Converter.hash(new File(source+"-wal")),shmHash=PodRoomV18Converter.hash(new File(source+"-shm"));
            int[] batches={0};PodRoomV18Converter converter=new PodRoomV18Converter(source,Jdbc::new,"fixture-设备😀",2,committed->{if(committed)batches[0]++;});
            File full=child(root,"full");converter.snapshot(full);converter.copy(full);String expected=converter.verify(full);
            require(batches[0]==30,"all 15 tables paged");
            require(dbHash.equals(PodRoomV18Converter.hash(source))&&walHash.equals(PodRoomV18Converter.hash(new File(source+"-wal")))&&shmHash.equals(PodRoomV18Converter.hash(new File(source+"-shm"))),"source bytes unchanged with live WAL");
            // Interrupt before and after EACH batch transaction, then use a new converter.
            for(boolean committed:new boolean[]{false,true})for(int stop=1;stop<=batches[0];stop++){
                final int at=stop;int[] seen={0};File g=child(root,"resume-"+committed+"-"+stop);
                PodRoomV18Converter broken=new PodRoomV18Converter(source,Jdbc::new,"fixture-设备😀",2,c->{if(c==committed&&++seen[0]==at)throw new IOException("synthetic_interrupt");});
                broken.snapshot(g);try{broken.copy(g);throw new AssertionError("no interrupt");}catch(IOException interrupted){}
                PodRoomV18Converter resumed=new PodRoomV18Converter(source,Jdbc::new,"fixture-设备😀",2,c->{});resumed.copy(g);require(expected.equals(resumed.verify(g)),"resumption differs at "+stop);
            }
            // Actual process termination leaves SQLite rollback/super journals.
            // Exercise every batch boundary on both sides of COMMIT.
            for(boolean committed:new boolean[]{false,true})for(int stop=1;stop<=batches[0];stop++) {
                File g=child(root,"crash-"+committed+"-"+stop);converter.snapshot(g);
                Process process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-ea","-cp",System.getProperty("java.class.path"),RoomV18Test.class.getName(),root.toString(),"crash",g.toString(),Integer.toString(stop),Boolean.toString(committed)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                require(process.waitFor()==91,"child terminated at batch");
                PodRoomV18Converter resumed=new PodRoomV18Converter(source,Jdbc::new,"fixture-设备😀",2,c->{});resumed.copy(g);require(expected.equals(resumed.verify(g)),"crash recovery differs");
            }
            // Forward compatibility with v1 partial candidates: that converter
            // persisted a full receipt after every committed page. Completing the
            // remaining pages must replace the stale-but-valid partial receipt.
            File legacy=child(root,"legacy-partial-receipt");int[] stopped={0};
            PodRoomV18Converter legacyWriter=new PodRoomV18Converter(source,Jdbc::new,"fixture-设备😀",2,committed->{if(committed&&stopped[0]++==0)throw new IOException("legacy_stop");});
            legacyWriter.snapshot(legacy);try{legacyWriter.copy(legacy);throw new AssertionError("legacy partial not stopped");}catch(IOException expectedFailure){}
            try(Jdbc partial=new Jdbc(new File(legacy,"podjs.sqlite"))) {
                partial.exec("ATTACH DATABASE ? AS migration",new File(legacy,"room-progress.sqlite").getAbsolutePath());
                PodRoomV18Converter.seal(partial);
                partial.exec("UPDATE migration.receipt SET digest='legacy-object-identity-digest' WHERE id=1");
            }
            PodRoomV18Converter forward=new PodRoomV18Converter(source,Jdbc::new,"fixture-设备😀",2,c->{});forward.copy(legacy);
            require(expected.equals(forward.verify(legacy)),"legacy partial receipt not resealed");
            Properties legacyVerified=PodTakeoverCoordinator.read(new File(legacy,"room-verification.properties"));legacyVerified.remove("version");
            PodTakeoverCoordinator.atomic(new File(legacy,"room-verification.properties"),legacyVerified);
            try(Jdbc partial=new Jdbc(new File(legacy,"podjs.sqlite"))) {
                partial.exec("ATTACH DATABASE ? AS migration",new File(legacy,"room-progress.sqlite").getAbsolutePath());
                partial.exec("UPDATE migration.receipt SET digest='legacy-verified-object-identity' WHERE id=1");
            }
            require(expected.equals(forward.verify(legacy)),"legacy final verification not rebuilt");
            File blobFile=new File(root,"blob-digest.sqlite");try(Jdbc blob=new Jdbc(blobFile)) {
                blob.exec("CREATE TABLE blobs(id INTEGER PRIMARY KEY,payload BLOB NOT NULL)");blob.exec("INSERT INTO blobs VALUES(1,?)",new byte[]{1,2,3,4});
                require(PodRoomV18Converter.tableDigest(blob,"blobs").equals(PodRoomV18Converter.tableDigest(blob,"blobs")),"BLOB digest is process-object dependent");
            }
            // Real cross-process SQLite byte-range write lock blocks snapshot.
            Process locker=new ProcessBuilder("python3","-c","import fcntl,sys; f=open(sys.argv[1],'r+b'); fcntl.lockf(f,fcntl.LOCK_EX,1,1073741825); print('locked',flush=True); sys.stdin.read()",source.toString()).start();
            require("locked".equals(new BufferedReader(new InputStreamReader(locker.getInputStream())).readLine()),"lock acquired");
            try{converter.snapshot(child(root,"busy-source"));throw new AssertionError("busy source accepted");}catch(IOException expectedFailure){}finally{locker.getOutputStream().close();require(locker.waitFor()==0,"locker exit");}
            PodRoomV18Converter lazy=new PodRoomV18Converter(source,Jdbc::new,()->{throw new IOException("must_not_read_identity_during_resume");});
            lazy.resume(full,PodTakeoverCoordinator.Phase.VERIFYING);
            File gate=child(root,"gate");Properties status=new PodTakeoverCoordinator(gate,()->new PodTakeoverCoordinator.Source(true,null,18,0,Long.MAX_VALUE),converter).run();
            require("PAUSED".equals(status.getProperty("phase"))&&"converters_incomplete".equals(status.getProperty("reason")),"no activation");require(!new File(gate,"active").exists(),"no pointer");
            require("PAUSED".equals(new PodTakeoverCoordinator(gate,()->new PodTakeoverCoordinator.Source(true,null,18,0,Long.MAX_VALUE),converter).run().getProperty("phase")),"repeat incomplete gate");
            // Tampering in copied rows is detected on restart, before another write.
            try(Jdbc d=new Jdbc(new File(full,"podjs.sqlite"))){d.exec("UPDATE articles SET title='tampered' WHERE id=1");}
            try{converter.resume(full,PodTakeoverCoordinator.Phase.COPYING);throw new AssertionError("tamper accepted");}catch(IOException expectedFailure){}
            // Regenerate clean oracle candidate.
            File oracle=child(root,"oracle-java");converter.snapshot(oracle);converter.copy(oracle);converter.verify(oracle);
            writer.exec("UPDATE room_master_table SET identity_hash='invalid' WHERE id=42");
            try{converter.snapshot(child(root,"invalid-identity"));throw new AssertionError("bad Room accepted");}catch(IOException expectedFailure){}
            writer.exec("UPDATE room_master_table SET identity_hash=? WHERE id=42",new JSONObject(PodRoomV18Converter.resource("room-v18.json")).getString("identity"));
            writer.exec("ALTER TABLE rss_items ADD COLUMN unknown_semantics TEXT");
            try{converter.snapshot(child(root,"unknown-column"));throw new AssertionError("unknown schema accepted");}catch(IOException expectedFailure){}
        }
        System.out.println("RoomV18Test: WAL, 60 exceptions + 60 process crashes, source locks, identity/schema, tamper and incomplete activation passed");
    }
}
