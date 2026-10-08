package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Device-free complete P2d pipeline; only synthetic sandbox bytes are read. */
public final class FileTakeoverTest {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static File fixture()throws Exception {return fixture(false);}
    static File fixture(boolean migrated)throws Exception {
        File root=Files.createTempDirectory("takeover-files-source").toFile();
        StoreTest.xml(root,"watch_device_identity","<string name=\"device_id\">synthetic-device</string>");
        File db=new File(root,"databases/watchrss.db");db.getParentFile().mkdirs();
        StoreTest.write(new File(root,"files/article_content/fixture.txt"),"<p>合成正文 😀</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StoreTest.write(new File(root,"files/offline/rss/2/image.jpg"),new byte[]{1,2,3});
        StoreTest.write(new File(root,"files/notes/assets/shared.png"),new byte[]{4,5,6});
        StoreTest.write(new File(root,"files/offline/rss/3/incomplete.part"),new byte[]{1});
        StoreTest.write(new File(root,"files/app_log.txt"),"synthetic private log".getBytes());
        StoreTest.write(new File(root,"files/bili-smoke-00000000-0000-0000-0000-000000000000.preferences_pb"),StoreTest.pref("reading_font_size_sp",StoreTest.concat(StoreTest.var(24),StoreTest.var(18))));
        StoreTest.write(new File(root,"files/profileinstaller_profileWrittenFor_lastUpdateTime.dat"),new byte[8]);
        StoreTest.write(new File(root,"cache/rss_images/image"),new byte[]{5});
        // Synthetic font loader seam below validates these bytes; Android uses Typeface.Builder.
        byte[] font=new byte[]{0,1,0,0,1,2,3,4};
        StoreTest.write(new File(root,"files/reader_assets/fonts/font.ttf"),font);
        StoreTest.write(new File(root,"files/reader_assets/variants/watch.png"),new byte[]{7,8,9});
        StoreTest.write(new File(root,"files/reader_assets/backgrounds/"+"z".repeat(251)+".png"),new byte[]{8});
        try(RoomV18Test.Jdbc d=new RoomV18Test.Jdbc(db)) {
            RoomV18Test.fixture(d,migrated);
            d.exec("UPDATE offline_media SET localPath=replace(localPath,?,?)", "com.lightningstudio.watchrss", "dev.podjs.source");
            d.exec("UPDATE rss_items SET content='watchrss-watch-content:fixture.txt',link='https://example.invalid/body' WHERE id=1");
            String articleId=PodStoreConverter.slot("https://example.invalid/body");
            d.exec("UPDATE saved_sync_states SET articleId=? WHERE itemId=1 AND saveType='FAVORITE'",articleId);
            d.exec("DELETE FROM reader_font_assets WHERE id!='id-1'");
            d.exec("UPDATE reader_font_assets SET fileName='font.ttf',sha256=?,byteCount=?,faceCount=1",PodRoomV18Converter.hash(new File(root,"files/reader_assets/fonts/font.ttf")),font.length);
            JSONObject variant=new JSONObject().put("fileName","watch.png").put("byteCount",3).put("sha256",PodRoomV18Converter.hash(new File(root,"files/reader_assets/variants/watch.png")));
            d.exec("DELETE FROM reader_background_assets WHERE id!='id-1'");
            d.exec("UPDATE reader_background_assets SET masterFileName='absent.png',sha256=?,byteCount=3,variantsJson=?,posterAssetId=NULL","a".repeat(64),new JSONObject().put("watch",variant).toString());
            for(int i=1;i<=3;i++)d.exec("UPDATE reader_presets SET payloadJson=? WHERE id=?",new JSONObject().put("schemaVersion",2).put("id","id-"+i).put("name","fixture "+i).put("body",new JSONObject().put("fontAssetId","id-1").put("fontFaceIndex",0)).put("background",new JSONObject().put("assetId",JSONObject.NULL).put("posterAssetId",JSONObject.NULL)).put("title",new JSONObject().put("fontAssetId",JSONObject.NULL)).toString(),"id-"+i);
            d.exec("UPDATE watch_notes SET markdown='![shared](assets/shared.png) ![missing](assets/missing.png)',baseMarkdown='![shared](assets/shared.png)'");
        }
        return root;
    }
    static PodFileConverter files(File source,PodFileConverter.Hook hook) {
        return new PodFileConverter(source,RoomV18Test.Jdbc::new,(file,faces)->{check(faces==1,"font faces");check(Files.readAllBytes(file.toPath())[1]==1,"font reader bytes");},hook,bytes->{},"dev.podjs.source","dev.podjs.rehearsal");
    }
    static PodTakeoverConverters composite(File source,PodFileConverter.Hook hook,StoreTest.Crypto crypto) {
        return new PodTakeoverConverters(new PodRoomV18Converter(new File(source,"databases/watchrss.db"),RoomV18Test.Jdbc::new,(File g)->PodStoreConverter.identity(new File(g,"stores-source"))),new PodStoreConverter(source,crypto),files(source,hook));
    }
    static PodTakeoverCoordinator.Probe probe=()->new PodTakeoverCoordinator.Source(true,null,18,0,Long.MAX_VALUE);
    static File generation(File root,Properties p){return new File(root,"generations/"+p.getProperty("generation"));}
    static File root()throws Exception{return Files.createTempDirectory("takeover-files-target").toFile();}
    static Properties run(File source,File root)throws Exception{return new PodTakeoverCoordinator(root,probe,composite(source,b->{},new StoreTest.Crypto())).run();}
    static class PowerLoss extends Error {}
    static void activeAndRestart(File source,File root,String expected)throws Exception {
        Properties p=run(source,root);check(p.getProperty("phase").equals("ACTIVE"),"expected ACTIVE: "+p);
        File g=generation(root,p),body=new File(g,"podjs/files/article_content/fixture.txt");
        check(Files.readString(body.toPath()).equals(expected),"copied body");
        check(!new File(g,"podjs/files/offline/rss/3/incomplete.part").exists(),"partial unpublished");
        check(!new File(g,"podjs/files/app_log.txt").exists(),"private log unpublished");
        check(new File(g,"podjs/files/reader_assets/backgrounds/"+"z".repeat(251)+".png").length()==1,"255-byte filename retained");
        JSONObject report=PodStoreConverter.json(new File(g,"files-report.json"));check(report.getJSONArray("blockers").length()==0,"no blockers");check(report.getJSONArray("degraded").length()>=3,"missing media explicitly degraded");
        Files.writeString(body.toPath(),"new user edit");
        PodTakeoverCoordinator next=new PodTakeoverCoordinator(root,()->{throw new AssertionError("ACTIVE must not inspect source");},PodTakeoverCoordinator.EMPTY);
        check(next.run().getProperty("phase").equals("ACTIVE"),"terminal active");check(Files.readString(body.toPath()).equals("new user edit"),"no reimport");
    }
    static void aliasesAndLocks()throws Exception {
        File source=fixture();PodFileConverter converter=files(source,b->{});
        String relative="offline/rss/2/image.jpg";
        for(String name:new String[]{"dev.podjs.source","dev.podjs.rehearsal"}) {
            check(converter.ownSourcePath("/data/user/0/"+name+"/files/"+relative,relative),"source/target user alias");
            check(converter.ownSourcePath("/data/data/"+name+"/files/"+relative,relative),"source/target legacy alias");
        }
        check(converter.ownSourcePath(new File(source,"files/"+relative).getPath(),relative),"canonical source");
        for(String path:new String[]{"/data/user/0/dev.podjs.other/files/"+relative,"/data/user/1/dev.podjs.source/files/"+relative,"/tmp/files/"+relative,"/data/user/0/dev.podjs.source.extra/files/"+relative,"/data/user/0/dev.podjs.source/files/offline/rss/2/../image.jpg"})
            check(!converter.ownSourcePath(path,relative),"alien/traversal alias rejected");
        check(!converter.ownSourcePath("/data/data/dev.podjs.source/files/../escape","../escape"),"unsafe relative rejected");
        String[] locks={"cache/watchrss.db.lck","cache/data/user/0/dev.podjs.rehearsal/no_backup/androidx.work.workdb.lck","cache/data/data/dev.podjs.source/databases/watchrss.db.lck"};
        for(String path:locks){check(converter.classify(path).equals("database-lock"),"exact DB lock");StoreTest.write(new File(source,path),new byte[]{0});}
        Map<String,String> before=StoreTest.digests(source);File target=root();Properties active=run(source,target);
        check(active.getProperty("phase").equals("ACTIVE"),"locks do not block");check(before.equals(StoreTest.digests(source)),"locks left untouched");
        for(String path:locks)check(!new File(generation(target,active),"podjs/files/"+path).exists(),"locks not migrated to assets");
        StoreTest.remove(target);StoreTest.remove(source);
        for(String path:new String[]{"cache/other.db.lck","cache/watchrss.db.lck.user","cache/data/user/0/dev.podjs.other/no_backup/androidx.work.workdb.lck","cache/data/user/1/dev.podjs.rehearsal/no_backup/androidx.work.workdb.lck","files/recovery-rehearsal/seed.json","files/recovery-rehearsal/business.json","files/recovery-rehearsal/interrupt.bin","files/watchrss.db.lck"}){
            source=fixture();converter=files(source,b->{});check(converter.classify(path).equals("unknown-user-content"),"unknown lock/marker still blocked");
            StoreTest.write(new File(source,path),new byte[]{0});target=root();check(run(source,target).getProperty("phase").equals("BLOCKED"),"unknown user files still block ACTIVE");
            StoreTest.remove(target);StoreTest.remove(source);
        }
    }
    static void v1000PausedCompletedPhases()throws Exception {
        for(PodTakeoverCoordinator.Phase phase:new PodTakeoverCoordinator.Phase[]{PodTakeoverCoordinator.Phase.SNAPSHOTTED,PodTakeoverCoordinator.Phase.COPYING,PodTakeoverCoordinator.Phase.VERIFYING,PodTakeoverCoordinator.Phase.READY,PodTakeoverCoordinator.Phase.ACTIVATING}) {
            File source=fixture(true),target=new File(source,"files/package-takeover");
            try {
                PodTakeoverConverters converter=composite(source,b->{},new StoreTest.Crypto());
                try{new PodTakeoverCoordinator(target,probe,converter,p->{if(p==phase)throw new PowerLoss();}).run();throw new AssertionError("missing interruption");}
                catch(PowerLoss expected){}
                Properties paused=PodTakeoverCoordinator.read(new File(target,"journal"));
                paused.setProperty("phase","PAUSED");paused.setProperty("resumePhase",phase.name());paused.setProperty("reason","stage_failed");
                PodTakeoverCoordinator.atomic(new File(target,"journal"),paused);
                Map<String,String> before=CanonicalTakeoverTest.tree(source,true);
                Properties active=new PodTakeoverCoordinator(target,probe,composite(source,b->{},new StoreTest.Crypto())).run();
                check(active.getProperty("phase").equals("ACTIVE"),"v1000 paused "+phase);
                check(active.getProperty("generation").equals(paused.getProperty("generation")),"complete generation retained "+phase);
                check(before.equals(CanonicalTakeoverTest.tree(source,true)),"source bytes unchanged "+phase);
            }finally{StoreTest.remove(source);}
        }
    }
    static void migratedNativeV18ForwardRetry()throws Exception {
        File source=fixture(true),target=new File(source,"files/package-takeover");
        try {
            Map<String,String> before=CanonicalTakeoverTest.tree(source,true);
            PodTakeoverConverters converters=composite(source,b->{},new StoreTest.Crypto());
            // Exact protocol/version of v1000; stage failure occurred before Room receipt.
            Files.createDirectories(target.toPath());
            Properties legacy=new Properties();legacy.setProperty("protocol","1");
            legacy.setProperty("converter",converters.version());legacy.setProperty("generation","g-v1000");
            legacy.setProperty("phase","PAUSED");legacy.setProperty("resumePhase","DETECTED");legacy.setProperty("reason","stage_failed");
            PodTakeoverCoordinator.atomic(new File(target,"journal"),legacy);
            Properties active=new PodTakeoverCoordinator(target,probe,converters).run();
            check(active.getProperty("phase").equals("ACTIVE"),"migrated native v1000 PAUSED must publish: "+active);
            check(before.equals(CanonicalTakeoverTest.tree(source,true)),"migrated native source bytes unchanged");
        }finally{StoreTest.remove(source);}
    }
    static void v1000PausedSnapshotForwardRetry()throws Exception {
        File source=fixture(),target=new File(source,"files/package-takeover");
        try {
            // v1000 failed after an atomic private copy, before files-snapshot.json.
            int[] renamed={0};
            PodTakeoverConverters broken=composite(source,b->{if(b.equals("snapshot.rename")&&++renamed[0]==3)throw new IOException("synthetic_interrupt");},new StoreTest.Crypto());
            PodTakeoverCoordinator c=new PodTakeoverCoordinator(target,probe,broken);
            Properties paused=c.run();check(paused.getProperty("phase").equals("PAUSED"),"v1000 PAUSED");
            check(paused.getProperty("resumePhase").equals("DETECTED"),"v1000 DETECTED");
            File old=generation(target,paused);String version=paused.getProperty("converter");
            File changed=new File(source,"files/article_content/fixture.txt");
            Files.writeString(changed.toPath(),"new synthetic native body");
            Map<String,String> oldCandidate=StoreTest.digests(old),nativeBefore=CanonicalTakeoverTest.tree(source,true);
            PodTakeoverConverters repaired=composite(source,b->{},new StoreTest.Crypto());
            check(version.equals(repaired.version()),"v1000 protocol and converter versions compatible");
            // Old behavior: same incomplete generation collides with the stale private copy.
            try {repaired.snapshot(old);throw new AssertionError("stale copy must conflict");}
            catch(IOException expected){check(expected.getMessage().equals("file_target_conflict"),"reproduced conflict");}
            // Simulate process death after journaling the replacement generation.
            try {new PodTakeoverCoordinator(target,probe,repaired,p->{if(p==PodTakeoverCoordinator.Phase.DETECTED)throw new PowerLoss();}).run();throw new AssertionError("missing kill");}
            catch(PowerLoss expected){}
            Properties restart=PodTakeoverCoordinator.read(new File(target,"journal"));
            check(!restart.getProperty("generation").equals(paused.getProperty("generation")),"fresh private attempt");
            check(oldCandidate.equals(StoreTest.digests(old)),"failed private candidate retained byte for byte");
            Properties active=new PodTakeoverCoordinator(target,probe,repaired).run();
            check(active.getProperty("phase").equals("ACTIVE"),"v1000 PAUSED to ACTIVE");
            check(nativeBefore.equals(CanonicalTakeoverTest.tree(source,true)),"all source bytes retained");
            check(Files.readString(new File(generation(target,active),"podjs/files/article_content/fixture.txt").toPath()).equals("new synthetic native body"),"fresh body imported");
        }finally{StoreTest.remove(source);}
        // A completed snapshot is immutable. Later paused phases keep their generation
        // even if the live source changes: resume verifies only the fixed candidate.
        source=fixture();target=new File(source,"files/package-takeover");
        try {
            PodTakeoverConverters broken=composite(source,b->{if(b.equals("copy.rename"))throw new IOException("synthetic_interrupt");},new StoreTest.Crypto());
            Properties paused=new PodTakeoverCoordinator(target,probe,broken).run();
            check(paused.getProperty("resumePhase").equals("COPYING"),"v1000 COPYING pause");
            Map<String,String> nativeBefore=CanonicalTakeoverTest.tree(source,true);
            Properties active=new PodTakeoverCoordinator(target,probe,composite(source,b->{},new StoreTest.Crypto())).run();
            check(active.getProperty("phase").equals("ACTIVE"),"completed snapshot resumed");
            check(active.getProperty("generation").equals(paused.getProperty("generation")),"completed snapshot not replaced");
            check(nativeBefore.equals(CanonicalTakeoverTest.tree(source,true)),"resumed source unchanged");
        }finally{StoreTest.remove(source);}
    }
    static void platformMetadataAndUserMutation()throws Exception {
        File source=fixture(true),target=new File(source,"files/package-takeover");
        try {
            File marker=new File(source,"files/profileinstaller_profileWrittenFor_lastUpdateTime.dat");
            // Exact metadata paths only, regardless of bytes/length; unknown lookalikes remain data.
            StoreTest.write(new File(source,"files/profileInstalled"),new byte[24]);
            File verificationMarker=new File(source,"files/profileInstalled");
            int[] writes={0};
            PodTakeoverConverters converters=composite(source,b->{if(b.equals("snapshot.part")) {
                StoreTest.write(marker,new byte[++writes[0]]);
                Files.deleteIfExists(verificationMarker.toPath());
            }},new StoreTest.Crypto());
            Properties active=new PodTakeoverCoordinator(target,probe,converters).run();
            check(active.getProperty("phase").equals("ACTIVE"),"metadata mutations never block business snapshot");
            JSONObject entries=PodStoreConverter.json(new File(generation(target,active),"files-snapshot.json")).getJSONObject("entries");
            check(!entries.has("files/profileInstalled")&&!entries.has("files/profileinstaller_profileWrittenFor_lastUpdateTime.dat"),"metadata excluded");
            check(marker.length()==writes[0]&&writes[0]>0,"platform wrote during snapshot");
            check(!new File(generation(target,active),"files-source/files/profileinstaller_profileWrittenFor_lastUpdateTime.dat").exists(),"metadata not snapshotted or imported");
        }finally{StoreTest.remove(source);}
        for(String path:new String[]{"files/profileInstalled.user","files/nested/profileInstalled","files/profileinstaller_profileWrittenFor_lastUpdateTime.dat.user"}) {
            source=fixture();target=root();try {
                StoreTest.write(new File(source,path),new byte[]{1});check(run(source,target).getProperty("phase").equals("BLOCKED"),"lookalike still user data");
            }finally{StoreTest.remove(source);StoreTest.remove(target);}
        }
        source=fixture(true);target=new File(source,"files/package-takeover");
        try {
            File body=new File(source,"files/article_content/fixture.txt");
            // Two failed generations, as on v1000/v1001. Both stay byte-for-byte retained.
            PodTakeoverConverters broken=composite(source,b->{if(b.equals("snapshot.part"))Files.writeString(body.toPath(),"changed-"+System.nanoTime());},new StoreTest.Crypto());
            Properties first=new PodTakeoverCoordinator(target,probe,broken).run();
            check(first.getProperty("phase").equals("PAUSED")&&first.getProperty("resumePhase").equals("DETECTED"),"user mutation pauses first candidate");
            File old=generation(target,first);Map<String,String> oldBytes=StoreTest.digests(old);
            Properties second=new PodTakeoverCoordinator(target,probe,broken).run();
            check(second.getProperty("phase").equals("PAUSED")&&!first.getProperty("generation").equals(second.getProperty("generation")),"second failed candidate");
            File newer=generation(target,second);Map<String,String> newerBytes=StoreTest.digests(newer);
            Properties active=run(source,target);check(active.getProperty("phase").equals("ACTIVE"),"two PAUSED generations resume to ACTIVE");
            check(oldBytes.equals(StoreTest.digests(old))&&newerBytes.equals(StoreTest.digests(newer)),"both failed candidates retained");
        }finally{StoreTest.remove(source);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("crash")) {
            File source=new File(args[1]),root=new File(args[2]);String boundary=args[3];
            PodTakeoverConverters converter=composite(source,b->{if(b.equals(boundary))Runtime.getRuntime().halt(91);},new StoreTest.Crypto());
            new PodTakeoverCoordinator(root,probe,converter,new PodTakeoverCoordinator.Checkpoint(){
                public void saved(PodTakeoverCoordinator.Phase phase){if(phase.name().equals(boundary))Runtime.getRuntime().halt(91);}
                public void published(){if(boundary.equals("published"))Runtime.getRuntime().halt(91);}
            }).run();throw new AssertionError("missing crash: "+boundary);
        }
        platformMetadataAndUserMutation();
        v1000PausedCompletedPhases();
        migratedNativeV18ForwardRetry();
        v1000PausedSnapshotForwardRetry();
        aliasesAndLocks();
        File source=fixture();Map<String,String> before=StoreTest.digests(source);String body=Files.readString(new File(source,"files/article_content/fixture.txt").toPath());
        File full=root();Properties p=run(source,full);check(p.getProperty("phase").equals("ACTIVE"),"full pipeline: "+p);
        File g=generation(full,p);
        JSONObject kv=PodStoreConverter.json(new File(g,"podjs/podjs-kv.json"));check(kv.getJSONObject("watchrss.reader-presets").getJSONArray("records").getJSONObject(0).get("deleted") instanceof Boolean,"production reader bool");
        // Keep one complete synthetic output for the production TypeScript consumer oracle.
        if(args.length==1){File oracle=new File(args[0]);oracle.mkdirs();for(String path:new String[]{"podjs.sqlite","podjs/podjs-kv.json"}){File target=new File(oracle,path);target.getParentFile().mkdirs();Files.copy(new File(g,path).toPath(),target.toPath());}File dest=new File(oracle,"podjs/files/article_content/fixture.txt");dest.getParentFile().mkdirs();Files.copy(new File(g,"podjs/files/article_content/fixture.txt").toPath(),dest.toPath());}
        activeAndRestart(source,full,body);StoreTest.remove(full);
        int crashes=0;
        for(String boundary:new String[]{"DETECTED","SNAPSHOTTED","COPYING","VERIFYING","READY","ACTIVATING","ACTIVE","snapshot.part","snapshot.rename","copy.part","copy.rename","snapshot","file","report","published"}) {
            File target=root();Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-ea","-cp",System.getProperty("java.class.path"),FileTakeoverTest.class.getName(),"crash",source.toString(),target.toString(),boundary).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            check(child.waitFor()==91,"crash boundary "+boundary);activeAndRestart(source,target,body);check(before.equals(StoreTest.digests(source)),"source unchanged after "+boundary);StoreTest.remove(target);crashes++;
        }
        // Native recovers a missing account key to logged-out; preserve source and publish other data.
        File account=fixture();StoreTest.xml(account,"watchrss_account_state","<string name=\"state_json\">synthetic-invalid</string>");File target=root();StoreTest.Crypto badCrypto=new StoreTest.Crypto();badCrypto.unavailable=true;
        p=new PodTakeoverCoordinator(target,probe,composite(account,b->{},badCrypto)).run();check(p.getProperty("phase").equals("ACTIVE"),"native reauth fallback");check(new File(target,"active").exists(),"other data published");StoreTest.remove(account);StoreTest.remove(target);
        for(String path:new String[]{"files/imports/book.txt","files/watch-cloud-cache/snapshot","files/article_content/fixture.txt","files/bili-smoke-user.preferences_pb","files/other-00000000-0000-0000-0000-000000000000.preferences_pb"}) {
            File s=fixture();if(path.endsWith("fixture.txt"))Files.delete(new File(s,path).toPath());else StoreTest.write(new File(s,path),new byte[]{1});target=root();p=run(s,target);check(p.getProperty("phase").equals("BLOCKED"),"missing/unknown/cloud blocks: "+p);check(!new File(target,"active").exists(),"no blocked pointer");StoreTest.remove(s);StoreTest.remove(target);
        }
        // A recognized synthetic store is parsed during COPY. Malformed protobuf
        // pauses the stage before publication, rather than producing a VERIFY blocker.
        File malformed=fixture();
        StoreTest.write(new File(malformed,"files/bili-smoke-00000000-0000-0000-0000-000000000000.preferences_pb"),new byte[]{1});
        Map<String,String> malformedBefore=StoreTest.digests(malformed);
        target=root();p=run(malformed,target);
        check(p.getProperty("phase").equals("PAUSED")&&p.getProperty("reason").equals("stage_failed")&&p.getProperty("resumePhase").equals("COPYING"),"malformed synthetic store pauses COPY: "+p);
        check(!new File(target,"active").exists(),"malformed synthetic store not published");
        check(malformedBefore.equals(StoreTest.digests(malformed)),"malformed source preserved");
        StoreTest.remove(malformed);StoreTest.remove(target);
        // Traversal and both valid/dangling links are rejected without touching their targets.
        for(String path:new String[]{"../escape","/absolute","notes/assets/../escape","notes//asset","notes/./asset","notes\\asset"})try{PodFileConverter.safe(source,path);throw new AssertionError("unsafe accepted");}catch(IOException expected){}
        File outside=Files.createTempFile("takeover-outside","").toFile();Files.writeString(outside.toPath(),"untouched");
        for(boolean dangling:new boolean[]{false,true}) {
            File s=fixture();Files.createSymbolicLink(new File(s,"files/notes/assets/link").toPath(),dangling?new File(outside+"-missing").toPath():outside.toPath());target=root();p=run(s,target);check(!p.getProperty("phase").equals("ACTIVE"),"symlink refused");check(Files.readString(outside.toPath()).equals("untouched"),"link target untouched");StoreTest.remove(s);StoreTest.remove(target);
        }
        Files.delete(outside.toPath());
        // Corrupted copied bytes and target collision cannot be silently reused.
        target=root();PodTakeoverConverters c=composite(source,b->{},new StoreTest.Crypto());
        try{new PodTakeoverCoordinator(target,probe,c,phase->{if(phase==PodTakeoverCoordinator.Phase.VERIFYING)throw new PowerLoss();}).run();}catch(PowerLoss expected){}
        p=PodTakeoverCoordinator.read(new File(target,"journal"));Files.writeString(new File(generation(target,p),"podjs/files/article_content/fixture.txt").toPath(),"corruption");p=run(source,target);check(p.getProperty("phase").equals("PAUSED"),"tampered files fail");check(!new File(target,"active").exists(),"tamper not published");StoreTest.remove(target);
        // ACTIVE failure remains terminal, never falling back to empty/default stores or the old source.
        for(String missing:new String[]{"podjs.sqlite","podjs/podjs-kv.json","podjs/secure.store","activation-receipt"}) {
            target=root();p=run(source,target);File truncated=new File(generation(target,p),"podjs.sqlite");Files.write(truncated.toPath(),new byte[0]);
        try{run(source,target);throw new AssertionError("empty active SQLite admitted");}catch(IOException expected){}
        check(truncated.length()==0,"empty active DB not initialized");check(PodTakeoverCoordinator.read(new File(target,"journal")).getProperty("phase").equals("ACTIVE"),"empty active DB stays terminal");StoreTest.remove(target);
        target=root();p=run(source,target);File active=generation(target,p);Files.delete(new File(active,missing).toPath());
            try{run(source,target);throw new AssertionError("missing active store admitted");}catch(IOException expected){}
            check(!new File(active,missing).exists(),"missing store not recreated");check(PodTakeoverCoordinator.read(new File(target,"journal")).getProperty("phase").equals("ACTIVE"),"ACTIVE terminal on failure");StoreTest.remove(target);
        }
        target=root();p=run(source,target);File active=generation(target,p);
        try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(new File(active,"podjs.sqlite"))){db.exec("UPDATE articles SET title='new title' WHERE id=1");}
        JSONObject changedKv=PodStoreConverter.json(new File(active,"podjs/podjs-kv.json")).put("user.new",42);PodStoreConverter.json(new File(active,"podjs/podjs-kv.json"),changedKv);
        check(run(source,target).getProperty("phase").equals("ACTIVE"),"real converters accept valid current user data");
        check(PodStoreConverter.json(new File(active,"podjs/podjs-kv.json")).getInt("user.new")==42,"new KV preserved");StoreTest.remove(target);
        check(before.equals(StoreTest.digests(source)),"source bytes unchanged");StoreTest.remove(source);
        System.out.println("FileTakeoverTest PASS: full ACTIVE, "+crashes+" process crashes, source unchanged, terminal restart, blockers/degradation, traversal/links, corruption");
    }
}
