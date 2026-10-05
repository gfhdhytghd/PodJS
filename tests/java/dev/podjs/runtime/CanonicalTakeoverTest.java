package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Root aliases model Android dataDir mount aliases; links below that boundary are not trusted. */
public final class CanonicalTakeoverTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    interface Action { void run()throws Exception; }
    static void rejects(Action action,String reason)throws Exception {
        try{action.run();throw new AssertionError("accepted "+reason);}catch(IOException expected){check(expected.getMessage().equals(reason),"wrong rejection: "+expected);}
    }
    /** No-follow snapshot: directories and links included for the zero-write test. */
    static Map<String,String> tree(File root,boolean sourceOnly)throws Exception {
        Map<String,String> result=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) {
            for(Path path:(Iterable<Path>)paths::iterator) {
                String relative=root.toPath().relativize(path).toString();
                if(sourceOnly&&(relative.equals("files/package-takeover")||relative.startsWith("files/package-takeover/")))continue;
                BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isSymbolicLink())result.put(relative,"link:"+Files.readSymbolicLink(path));
                else if(a.isRegularFile())result.put(relative,"file:"+a.size()+":"+PodRoomV18Converter.hash(path.toFile())+(sourceOnly?"":":"+a.lastModifiedTime()));
                else if(!sourceOnly)result.put(relative,"dir:"+a.lastModifiedTime());
            }
        }
        return result;
    }
    static File canonical(File raw)throws Exception {
        check(!raw.getAbsoluteFile().equals(raw.getCanonicalFile()),"fixture must use a noncanonical root");
        // Same boundary function used by PodTakeover.sandbox(Context), not a test-only normalization.
        File root=PodTakeoverSource.canonicalSandbox(raw);
        check(root.equals(root.getAbsoluteFile())&&root.equals(root.getCanonicalFile()),"canonical boundary");return root;
    }
    static PodTakeoverCoordinator coordinator(File root,List<PodTakeoverCoordinator.Phase> phases) {
        return new PodTakeoverCoordinator(new File(root,"files/package-takeover"),
                ()->PodTakeoverSource.detect(root,true),
                FileTakeoverTest.composite(root,b->{},new StoreTest.Crypto()),phases::add);
    }
    static void fresh()throws Exception {
        File holder=Files.createTempDirectory("canonical-fresh").toFile();
        try {
            File real=new File(holder,"real/app");real.mkdirs();
            File alias=new File(holder,"alias"),parentAlias=new File(holder,"parent-alias");
            Files.createSymbolicLink(alias.toPath(),real.toPath());Files.createSymbolicLink(parentAlias.toPath(),real.getParentFile().toPath());
            for(boolean populated:new boolean[]{false,true}) {
                if(populated)for(String p:new String[]{"shared_prefs/WebViewChromiumPrefs.xml","databases/podjs.sqlite","files/podjs/podjs-kv.json","files/podjs/secure.store","files/podjs/files/article_content/own.txt","cache/host-cache"})StoreTest.write(new File(real,p),"host-owned".getBytes());
                Map<String,String> before=tree(holder,false);
                for(File raw:new File[]{alias,new File(parentAlias,"app")}) {
                    // Reproduce the pre-20911e2 failure, then exercise the actual fixed boundary.
                    rejects(()->PodTakeoverCoordinator.read(new File(raw,"files/package-takeover/journal")),"takeover_symlink");
                    File root=canonical(raw);List<PodTakeoverCoordinator.Phase> phases=new ArrayList<>();
                    for(int i=0;i<3;i++) {
                        PodTakeoverCoordinator c=coordinator(root,phases);check(c.run().getProperty("phase").equals("NONE"),"fresh NONE");
                        PodTakeoverAdmission.Paths paths=PodTakeoverAdmission.Paths.resolve(c,new File(root,"files/podjs"),new File(root,"databases/podjs.sqlite"));
                        check(paths.data.equals(new File(real,"files/podjs")),"canonical fresh data");check(paths.database.equals(new File(real,"databases/podjs.sqlite")),"canonical fresh SQL");check(paths.files.equals(new File(real,"files/podjs/files")),"canonical fresh files");
                    }
                    check(phases.isEmpty(),"fresh must not persist DETECTED");check(!new File(real,"files/package-takeover").exists(),"no journal or lock directory");
                }
                check(before.equals(tree(holder,false)),"fresh bytes, paths, mtimes and links unchanged");
            }
        }finally{StoreTest.remove(holder);}
    }
    static void nativeConversion()throws Exception {
        File real=FileTakeoverTest.fixture(),holder=Files.createTempDirectory("canonical-native").toFile();
        try {
            File alias=new File(holder,"alias");Files.createSymbolicLink(alias.toPath(),real.toPath());File root=canonical(alias);
            Map<String,String> before=tree(real,true);List<PodTakeoverCoordinator.Phase> phases=new ArrayList<>();
            check(PodTakeoverSource.detect(root,true).present,"native detected");PodTakeoverCoordinator c=coordinator(root,phases);
            Properties state=c.run();check(state.getProperty("phase").equals("ACTIVE"),"native conversion: "+state);
            check(phases.equals(Arrays.asList(PodTakeoverCoordinator.Phase.DETECTED,PodTakeoverCoordinator.Phase.SNAPSHOTTED,PodTakeoverCoordinator.Phase.COPYING,PodTakeoverCoordinator.Phase.VERIFYING,PodTakeoverCoordinator.Phase.READY,PodTakeoverCoordinator.Phase.ACTIVATING,PodTakeoverCoordinator.Phase.ACTIVE)),"all durable phases");
            File generation=c.activeGeneration(state);check(generation.toPath().startsWith(root.toPath()),"generation inside canonical root");
            PodTakeoverAdmission.Paths paths=PodTakeoverAdmission.Paths.resolve(c,new File(root,"files/podjs"),new File(root,"databases/podjs.sqlite"));
            for(File path:new File[]{paths.data,paths.files,paths.database})check(path.equals(path.getCanonicalFile())&&path.toPath().startsWith(generation.toPath()),"canonical admitted roots");
            check(PodRoomV18Converter.hash(new File(paths.files,"article_content/fixture.txt")).equals(PodRoomV18Converter.hash(new File(real,"files/article_content/fixture.txt"))),"body converted");
            check(before.equals(tree(real,true)),"native source unchanged");
            Files.writeString(new File(paths.files,"article_content/fixture.txt").toPath(),"new user content");
            check(coordinator(canonical(alias),new ArrayList<>()).run().getProperty("phase").equals("ACTIVE"),"restart through root alias");
            check(Files.readString(new File(paths.files,"article_content/fixture.txt").toPath()).equals("new user content"),"restart does not reimport");
        }finally{StoreTest.remove(holder);StoreTest.remove(real);}
    }
    static void internalLinks()throws Exception {
        for(String path:new String[]{"databases/watchrss.db","databases/watchrss.db-wal","databases/watchrss.db-shm","shared_prefs/watch_device_identity.xml","files/article_content/fixture.txt","files/article_content"}) {
            File real=FileTakeoverTest.fixture(),holder=Files.createTempDirectory("canonical-internal").toFile();
            try {
                File alias=new File(holder,"alias");Files.createSymbolicLink(alias.toPath(),real.toPath());File root=canonical(alias),link=new File(real,path),outside=new File(holder,"outside");
                if(link.exists())Files.move(link.toPath(),outside.toPath());else Files.writeString(outside.toPath(),"outside sentinel");
                Files.createSymbolicLink(link.toPath(),outside.toPath());Map<String,String> before=tree(holder,false),source=tree(real,true);
                try{Properties p=coordinator(root,new ArrayList<>()).run();check(!p.getProperty("phase").equals("ACTIVE"),"internal link admitted: "+path);}catch(IOException expected){check(expected.getMessage().contains("symlink"),"internal link rejection reason");}
                check(!new File(root,"files/package-takeover/active").exists(),"no linked-source publication");
                check(before.equals(tree(holder,false)),"outside link target untouched");check(source.equals(tree(real,true)),"linked source unchanged");
                // Also verify the relevant converter in isolation, bypassing the source probe.
                File g=new File(holder,"candidate");g.mkdirs();
                if(path.startsWith("shared_prefs/"))rejects(()->new PodStoreConverter(root,new StoreTest.Crypto()).snapshot(g),"source_not_regular");
                if(path.startsWith("files/"))rejects(()->FileTakeoverTest.files(root,b->{}).snapshot(g),"file_symlink");
                if(path.startsWith("databases/"))rejects(()->new PodRoomV18Converter(new File(root,"databases/watchrss.db"),RoomV18Test.Jdbc::new,"synthetic-device").snapshot(g),"source_symlink");
            }finally{StoreTest.remove(holder);StoreTest.remove(real);}
        }
        // Coordinator and ACTIVE admission must reject links below the normalized root too.
        File real=FileTakeoverTest.fixture(),holder=Files.createTempDirectory("canonical-controls").toFile();
        try {
            File alias=new File(holder,"alias");Files.createSymbolicLink(alias.toPath(),real.toPath());File root=canonical(alias),takeover=new File(root,"files/package-takeover");takeover.mkdirs();
            File outside=new File(holder,"outside");Files.writeString(outside.toPath(),"untouched");
            for(String name:new String[]{"journal","journal.tmp","lock"}) {
                File link=new File(takeover,name);Files.deleteIfExists(link.toPath());Files.createSymbolicLink(link.toPath(),outside.toPath());
                rejects(()->coordinator(root,new ArrayList<>()).run(),"takeover_symlink");Files.delete(link.toPath());
                check(Files.readString(outside.toPath()).equals("untouched"),"control link target unchanged");
            }
            PodTakeoverCoordinator c=coordinator(root,new ArrayList<>());Properties p=c.run();check(p.getProperty("phase").equals("ACTIVE"),"conversion after rejected control links");
            File g=c.activeGeneration(p),store=new File(g,"podjs/podjs-kv.json"),saved=new File(g,"podjs/saved-kv.json");Files.move(store.toPath(),saved.toPath());Files.createSymbolicLink(store.toPath(),saved.toPath());
            rejects(()->PodTakeoverAdmission.Paths.resolve(coordinator(root,new ArrayList<>()),new File(root,"files/podjs"),new File(root,"databases/podjs.sqlite")),"active_store_missing");
        }finally{StoreTest.remove(holder);StoreTest.remove(real);}
    }
    public static void main(String[] args)throws Exception {
        fresh();nativeConversion();internalLinks();
        System.out.println("CanonicalTakeoverTest PASS: root/ancestor aliases, empty and PodJS-only NONE zero writes, DETECTED-to-ACTIVE conversion, canonical admission/restart, internal source/control/store links rejected");
    }
}
