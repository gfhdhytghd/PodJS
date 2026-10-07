package dev.podjs.takeover;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic sandbox tests; no real Context/user data/Keystore/device. Also copied
 * into the native recovery repository and called by JUnit. */
public final class NativeBackupTest {
    static final String G="g-fixture",CERT="a".repeat(64);
    static final Map<String,String> NATIVE=new TreeMap<>(),PODJS=new TreeMap<>();
    static {
        NATIVE.put("databases/watchrss.db","native room");NATIVE.put("databases/watchrss.db-wal","uncheckpointed wal");NATIVE.put("databases/watchrss.db-shm","raw shm");
        NATIVE.put("databases/androidx.work.workdb","old workmanager");
        NATIVE.put("shared_prefs/account.xml","ciphertext");
        NATIVE.put("files/datastore/settings.preferences_pb","protobuf");NATIVE.put("files/notes/assets/picture","picture");
        NATIVE.put("files/native-unknown/file","unknown retained bytes");NATIVE.put("no_backup/androidx.work.workdb","native bookkeeping");
        PODJS.put("files/package-takeover/generations/"+G+"/podjs/files/new-note","post-ACTIVE user changes");
        PODJS.put("files/package-takeover/active","pointer");PODJS.put("files/podjs/secure.store","podjs ciphertext");
        PODJS.put("databases/podjs.sqlite","podjs db");PODJS.put("no_backup/podjs-inbox/item","transport state");
    }
    static String readText(Path p)throws IOException {return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void write(File root,String path,String text)throws IOException {File f=new File(root,path);Files.createDirectories(f.getParentFile().toPath());Files.write(f.toPath(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static File fixture()throws IOException {
        File root=Files.createTempDirectory("native-backup-synthetic-").toFile();
        for(Map.Entry<String,String> e:NATIVE.entrySet())write(root,e.getKey(),e.getValue());
        for(Map.Entry<String,String> e:PODJS.entrySet())write(root,e.getKey(),e.getValue());
        Files.createDirectories(new File(root,"files/native-empty/subdir").toPath());
        Files.setPosixFilePermissions(new File(root,"files/notes/assets/picture").toPath(),java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));return root;
    }
    static void remove(File f)throws IOException {if(f.isDirectory())for(File c:Objects.requireNonNull(f.listFiles()))remove(c);Files.deleteIfExists(f.toPath());}
    static NativeBackup engine(File root)throws IOException {return new NativeBackup(root);}
    static void checkBytes(File root,String prefix,Map<String,String> expected)throws IOException {
        for(Map.Entry<String,String> e:expected.entrySet())check(readText(new File(root,prefix+e.getKey()).toPath()).equals(e.getValue()));
    }
    static void checkBackup(File root)throws IOException {
        engine(root).verify(G);checkBytes(root,NativeBackup.BACKUP+"/"+G+"/data/",NATIVE);
        for(String r:NATIVE.keySet())check(!new File(root,r).exists());
        check(new File(root,NativeBackup.BACKUP+"/"+G+"/data/files/native-empty/subdir").isDirectory());
    }
    static void checkRestored(File root)throws IOException {
        checkBytes(root,"",NATIVE);checkBytes(root,NativeBackup.RECOVERED+"/"+G+"/",PODJS);
        checkBytes(root,NativeBackup.BACKUP+"/"+G+"/data/",NATIVE);
        check(new File(root,"files/native-empty/subdir").isDirectory());
        check(new File(root,"files/.podjs-recovery-state/"+G+"/complete").isFile());
    }
    static void expectFailure(Io action)throws Exception {try{action.run();throw new AssertionError("accepted invalid state");}catch(IOException expected){}}
    interface Io {void run()throws Exception;}
    public static void runAll()throws Exception {
        File root=fixture();try {
            Object inode=Files.getAttribute(new File(root,"files/notes/assets/picture").toPath(),"unix:ino");
            engine(root).prepare(G,40,CERT);checkBackup(root);
            check(inode.equals(Files.getAttribute(new File(root,NativeBackup.BACKUP+"/"+G+"/data/files/notes/assets/picture").toPath(),"unix:ino")));
            engine(root).prepare(G,40,CERT);checkBackup(root);checkBytes(root,"",PODJS);
            engine(root).restore(G,CERT);checkRestored(root);
            write(root,"databases/watchrss.db","new native write");engine(root).restore(G,CERT);
            check(readText(new File(root,"databases/watchrss.db").toPath()).equals("new native write"));
        } finally {remove(root);}
        root=fixture();try {
            File r=root;expectFailure(()->new NativeBackup(r,b->{},x->0).prepare(G,40,CERT));checkBytes(root,"",NATIVE);
            engine(root).prepare(G,40,CERT);
            expectFailure(()->new NativeBackup(r,b->{},x->0).restore(G,CERT));checkBackup(root);checkBytes(root,"",PODJS);
            expectFailure(()->engine(r).restore(G,"b".repeat(64)));checkBackup(root);
            File mf=new File(root,NativeBackup.BACKUP+"/"+G+"/manifest");String text=readText(mf.toPath());
            Files.write(mf.toPath(),text.replace("nativeVersionCode=40","nativeVersionCode=41").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            expectFailure(()->engine(r).verify(G));expectFailure(()->engine(r).restore(G,CERT));expectFailure(()->engine(r).purge(G));
            Files.write(mf.toPath(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            write(root,NativeBackup.BACKUP+"/"+G+"/data/databases/watchrss.db","tampered");
            expectFailure(()->engine(r).verify(G));expectFailure(()->engine(r).restore(G,CERT));checkBytes(root,"",PODJS);
        } finally {remove(root);}
        root=fixture();try {
            File r=root;File link=new File(root,"files/evil-link");Files.createSymbolicLink(link.toPath(),Path.of("/tmp"));
            expectFailure(()->engine(r).prepare(G,40,CERT));Files.delete(link.toPath());
            expectFailure(()->engine(r).prepare("g-../outside",40,CERT));checkBytes(root,"",NATIVE);
        } finally {remove(root);}
        root=fixture();try {
            engine(root).prepare(G,40,CERT);engine(root).restore(G,CERT);
            write(root,"files/package-takeover/active","generation=g-second\n");
            engine(root).prepare("g-second",900000,CERT);
            check(engine(root).retainedGenerations().size()==2);
            check(engine(root).recoveryGeneration().equals("g-second"));
            engine(root).restore("g-second",CERT);
            check(engine(root).recoveryGeneration().equals("g-second"));
            for(String generation:engine(root).retainedGenerations())engine(root).purge(generation);
            check(engine(root).retainedGenerations().isEmpty());checkBytes(root,"",NATIVE);
        }finally{remove(root);}
        // Every durability boundary is resumed from a new instance after an exception.
        for(String mode:new String[]{"backup","restore","purge"}) {
            File sample=fixture();List<String> boundaries=new ArrayList<>();
            try {setup(sample,mode);perform(sample,mode,new NativeBackup(sample,boundaries::add,File::getUsableSpace));}
            finally {remove(sample);}
            for(int cut=0;cut<boundaries.size();cut++) {
                File r=fixture();try {
                    setup(r,mode);AtomicInteger n=new AtomicInteger();int boundary=cut;
                    expectFailure(()->perform(r,mode,new NativeBackup(r,b->{if(n.getAndIncrement()==boundary)throw new IOException("interrupted");},File::getUsableSpace)));
                    perform(r,mode,engine(r));result(r,mode);
                } finally {remove(r);}
            }
        }
        // A recovery package can finish a partially renamed pre-publication backup.
        root=fixture();try {
            File r=root;expectFailure(()->new NativeBackup(r,b->{if(b.equals("backup-move-0"))throw new IOException("interrupted");},File::getUsableSpace).prepare(G,40,CERT));
            check(G.equals(engine(root).recoveryGeneration()));engine(root).restore(G,CERT);checkRestored(root);
        }finally{remove(root);}
        System.out.println("NativeBackup synthetic byte/space/tamper/rename/restart checks PASS");
    }
    static void setup(File root,String mode)throws Exception {
        if(!mode.equals("backup"))engine(root).prepare(G,40,CERT);
        if(mode.equals("restore")){write(root,"shared_prefs/new-podjs-platform.xml","new platform state");write(root,"files/.podjs-recovery-lock","stable lock inode");}
        if(mode.equals("purge"))write(root,"files/package-takeover/generations/"+G+"/stores-source/ciphertext","duplicate");
    }
    static void perform(File root,String mode,NativeBackup b)throws Exception {
        if(mode.equals("backup"))b.prepare(G,40,CERT);
        else if(mode.equals("restore"))b.restore(G,CERT);
        else {b.purge(G);b.purgeImportSnapshots(G);}
    }
    static void result(File root,String mode)throws Exception {
        if(mode.equals("backup"))checkBackup(root);
        else if(mode.equals("restore")){checkRestored(root);check(readText(new File(root,"files/.podjs-recovery-lock").toPath()).equals("stable lock inode"));check(readText(new File(root,NativeBackup.RECOVERED+"/"+G+"/shared_prefs/new-podjs-platform.xml").toPath()).equals("new platform state"));}
        else {
            check(!new File(root,NativeBackup.BACKUP+"/"+G).exists());check(!new File(root,NativeBackup.BACKUP+"/.purging-"+G).exists());
            check(!new File(root,"files/package-takeover/generations/"+G+"/stores-source").exists());checkBytes(root,"",PODJS);
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("crash")) {
            File root=new File(args[1]);String mode=args[2];int cut=Integer.parseInt(args[3]);AtomicInteger n=new AtomicInteger();
            perform(root,mode,new NativeBackup(root,b->{if(n.getAndIncrement()==cut)Runtime.getRuntime().halt(71);},File::getUsableSpace));return;
        }
        runAll();
        // Terminate a separate real JVM at every observed boundary (no shutdown hooks).
        for(String mode:new String[]{"backup","restore","purge"}) {
            File sample=fixture();AtomicInteger boundaries=new AtomicInteger();
            try{setup(sample,mode);perform(sample,mode,new NativeBackup(sample,b->boundaries.incrementAndGet(),File::getUsableSpace));}finally{remove(sample);}
            for(int cut=0;cut<boundaries.get();cut++) {
                File root=fixture();try {
                    setup(root,mode);
                    Process p=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),NativeBackupTest.class.getName(),"crash",root.getAbsolutePath(),mode,Integer.toString(cut)).inheritIO().start();
                    check(p.waitFor()==71);perform(root,mode,engine(root));result(root,mode);
                }finally{remove(root);}
            }
        }
        System.out.println("NativeBackup real process termination at all boundaries PASS");
    }
}
