package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Device-free complete P2d pipeline; only synthetic sandbox bytes are read. */
public final class FileTakeoverTest {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static File fixture()throws Exception {
        File root=Files.createTempDirectory("takeover-files-source").toFile();
        StoreTest.xml(root,"watch_device_identity","<string name=\"device_id\">synthetic-device</string>");
        File db=new File(root,"databases/watchrss.db");db.getParentFile().mkdirs();
        StoreTest.write(new File(root,"files/article_content/fixture.txt"),"<p>合成正文 😀</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StoreTest.write(new File(root,"files/offline/rss/2/image.jpg"),new byte[]{1,2,3});
        StoreTest.write(new File(root,"files/notes/assets/shared.png"),new byte[]{4,5,6});
        StoreTest.write(new File(root,"files/offline/rss/3/incomplete.part"),new byte[]{1});
        StoreTest.write(new File(root,"files/app_log.txt"),"synthetic private log".getBytes());
        StoreTest.write(new File(root,"cache/rss_images/image"),new byte[]{5});
        // Synthetic font loader seam below validates these bytes; Android uses Typeface.Builder.
        byte[] font=new byte[]{0,1,0,0,1,2,3,4};
        StoreTest.write(new File(root,"files/reader_assets/fonts/font.ttf"),font);
        StoreTest.write(new File(root,"files/reader_assets/variants/watch.png"),new byte[]{7,8,9});
        StoreTest.write(new File(root,"files/reader_assets/backgrounds/"+"z".repeat(251)+".png"),new byte[]{8});
        try(RoomV18Test.Jdbc d=new RoomV18Test.Jdbc(db)) {
            RoomV18Test.fixture(d);
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
        return new PodFileConverter(source,RoomV18Test.Jdbc::new,(file,faces)->{check(faces==1,"font faces");check(Files.readAllBytes(file.toPath())[1]==1,"font reader bytes");},hook);
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
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("crash")) {
            File source=new File(args[1]),root=new File(args[2]);String boundary=args[3];
            PodTakeoverConverters converter=composite(source,b->{if(b.equals(boundary))Runtime.getRuntime().halt(91);},new StoreTest.Crypto());
            new PodTakeoverCoordinator(root,probe,converter,new PodTakeoverCoordinator.Checkpoint(){
                public void saved(PodTakeoverCoordinator.Phase phase){if(phase.name().equals(boundary))Runtime.getRuntime().halt(91);}
                public void published(){if(boundary.equals("published"))Runtime.getRuntime().halt(91);}
            }).run();throw new AssertionError("missing crash: "+boundary);
        }
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
        // A damaged account requires an explicit user decision, never automatic guest fallback.
        File account=fixture();StoreTest.xml(account,"watchrss_account_state","<string name=\"state_json\">synthetic-invalid</string>");File target=root();StoreTest.Crypto badCrypto=new StoreTest.Crypto();badCrypto.unavailable=true;
        p=new PodTakeoverCoordinator(target,probe,composite(account,b->{},badCrypto)).run();check(p.getProperty("phase").equals("BLOCKED")&&p.getProperty("reason").equals("store_needs_reauth_user_confirmation"),"reauth block");check(!new File(target,"active").exists(),"no reauth publication");StoreTest.remove(account);StoreTest.remove(target);
        for(String path:new String[]{"files/imports/book.txt","files/watch-cloud-cache/snapshot","files/article_content/fixture.txt"}) {
            File s=fixture();if(path.endsWith("fixture.txt"))Files.delete(new File(s,path).toPath());else StoreTest.write(new File(s,path),new byte[]{1});target=root();p=run(s,target);check(p.getProperty("phase").equals("BLOCKED"),"missing/unknown/cloud blocks: "+p);check(!new File(target,"active").exists(),"no blocked pointer");StoreTest.remove(s);StoreTest.remove(target);
        }
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
