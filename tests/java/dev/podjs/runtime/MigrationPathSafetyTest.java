package dev.podjs.runtime;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Pre-open checks: rejected recovery input must remain intact and never reach SQLite. */
public final class MigrationPathSafetyTest {
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static void remove(File root)throws Exception {if(root.exists())try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    static File pristine;
    static void copyFixture(File root)throws Exception {
        if(pristine==null) {
            pristine=Files.createTempDirectory("migration-pristine-").toFile();File source=new File(pristine,"source.sqlite"),g=new File(pristine,"g");g.mkdirs();
            try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(source)){RoomV18Test.fixture(db);}
            PodRoomV18Converter normal=new PodRoomV18Converter(source,RoomV18Test.Jdbc::new,"fixture");normal.snapshot(g);normal.copy(g);
        }
        try(java.util.stream.Stream<Path> paths=Files.walk(pristine.toPath())) {
            for(Path path:(Iterable<Path>)paths::iterator){Path to=root.toPath().resolve(pristine.toPath().relativize(path));if(Files.isDirectory(path))Files.createDirectories(to);else Files.copy(path,to);}
        }
    }
    static void fixed(String mode,String directory,String name,String protectedKind)throws Exception {
        File root=Files.createTempDirectory("migration-path-").toFile(),source=new File(root,"source.sqlite"),g=new File(root,"g");g.mkdirs();
        try {
            copyFixture(root);
            File protectedFile=protectedKind.equals("source")?source:protectedKind.equals("snapshot")?new File(g,"room.sqlite"):new File(root,"other-generation/room.sqlite");
            if(protectedKind.equals("other")){protectedFile.getParentFile().mkdirs();Files.copy(source.toPath(),protectedFile.toPath());}
            String original=PodRoomV18Converter.hash(source),snapshot=PodRoomV18Converter.hash(new File(g,"room.sqlite")),protectedHash=PodRoomV18Converter.hash(protectedFile);
            File work=new File(g,directory),bad=new File(work,name);work.mkdirs();
            if(mode.equals("symlink"))Files.createSymbolicLink(bad.toPath(),protectedFile.toPath());
            else if(mode.equals("hardlink"))Files.createLink(bad.toPath(),protectedFile.toPath());
            else if(mode.equals("directory-link")){work.delete();Files.createSymbolicLink(work.toPath(),new File(root,"other-generation").toPath());bad=work;}
            else Files.write(bad.toPath(),new byte[]{1,2,3});
            final int[] opens={0};PodRoomV18Converter guarded=new PodRoomV18Converter(source,path->{opens[0]++;return new RoomV18Test.Jdbc(path);},"fixture");
            String failure="";try{guarded.verify(g);}catch(IOException e){failure=e.getMessage();}
            check(failure.equals("migration_storage_invalid")||failure.equals("verification_scratch_invalid"),"missing fixed preflight error: "+failure);
            check(opens[0]==0,"SQLite opened before validating the complete recovery directory");
            check(Files.exists(bad.toPath(),LinkOption.NOFOLLOW_LINKS),"rejected file was deleted");
            check(original.equals(PodRoomV18Converter.hash(source)),"source modified");
            check(snapshot.equals(PodRoomV18Converter.hash(new File(g,"room.sqlite"))),"source snapshot modified");
            check(protectedHash.equals(PodRoomV18Converter.hash(protectedFile)),"other protected inode modified");
        }finally{remove(root);}
    }
    static void initializationFailureClosesAndPreserves()throws Exception {
        Method target=PodRoomV18Converter.class.getDeclaredMethod("target",File.class);target.setAccessible(true);
        for(int point=1;point<=10;point++)for(String kind:new String[]{"cancel","timeout","io","memory"})for(boolean closeFails:new boolean[]{false,true}) {
            File g=Files.createTempDirectory("migration-close-").toFile();
            try {
                Throwable original=kind.equals("memory")?new OutOfMemoryError("synthetic"):new IOException(kind.equals("cancel")?"migration_interrupted":kind.equals("timeout")?"migration_timeout":"synthetic_io");
                IOException closing=new IOException("synthetic_close");int[] opened={0},closed={0},step={0};final int failAt=point;
                PodRoomV18Converter converter=new PodRoomV18Converter(new File(g,"source.sqlite"),file->{opened[0]++;return new PodRoomV18Converter.Db(){
                    void next()throws Exception {if(++step[0]==failAt){if(original instanceof Error)throw(Error)original;throw(Exception)original;}}
                    public List<Map<String,Object>> query(String sql,Object...args)throws Exception{next();return Collections.emptyList();}
                    public void exec(String sql,Object...args)throws Exception{next();}
                    public void close()throws Exception{closed[0]++;if(closeFails)throw closing;}
                };},"fixture");
                Throwable caught=null;try{target.invoke(converter,g);}catch(InvocationTargetException e){caught=e.getCause();}
                check(caught==original,"cleanup replaced the primary failure");check(opened[0]==1&&closed[0]==1,"initialization leaked at step "+point+"/"+kind);
                check(!closeFails||Arrays.asList(caught.getSuppressed()).contains(closing),"close failure was lost");
            }finally{remove(g);}
        }
    }
    static void pausedAdmission()throws Exception {
        File root=Files.createTempDirectory("migration-paused-path-").toFile(),base=new File(root,"files/package-takeover"),g=new File(base,"generations/g-bad"),source=new File(root,"source.sqlite");g.mkdirs();
        try {
            try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(source)){RoomV18Test.fixture(db);}
            PodRoomV18Converter normal=new PodRoomV18Converter(source,RoomV18Test.Jdbc::new,"fixture");normal.snapshot(g);normal.copy(g);
            File work=new File(g,"verification-work");work.mkdirs();File alias=new File(work,"room-progress.sqlite-wal");Files.createLink(alias.toPath(),source.toPath());String hash=PodRoomV18Converter.hash(source);
            int[] opens={0};PodRoomV18Converter guarded=new PodRoomV18Converter(source,file->{opens[0]++;return new RoomV18Test.Jdbc(file);},"fixture");
            Properties p=new Properties();p.setProperty("protocol","1");p.setProperty("phase","COPYING");p.setProperty("generation","g-bad");p.setProperty("converter",guarded.version());PodTakeoverCoordinator.atomic(new File(base,"journal"),p);
            PodTakeoverCoordinator c=new PodTakeoverCoordinator(base,()->new PodTakeoverCoordinator.Source(true,null,18,0,Long.MAX_VALUE),guarded);
            for(int i=0;i<2;i++){Properties paused=c.run();check("PAUSED".equals(paused.getProperty("phase")),"bad recovery admitted");check("migration_storage_invalid".equals(paused.getProperty("reason")),"unstable failure code");}
            check(opens[0]==0&&!new File(base,"active").exists(),"opened or published invalid recovery");check(Files.isSameFile(alias.toPath(),source.toPath())&&hash.equals(PodRoomV18Converter.hash(source)),"rejected source alias not retained");
        }finally{remove(root);}
    }
    public static void main(String[]args)throws Exception {
        try {
        int cases=0;
        for(String directory:new String[]{"capture-work","capture-00000000-0000-0000-0000-000000000001","verification-work","verify-00000000-0000-0000-0000-000000000001"}) {
            String[] names=directory.startsWith("capture")?new String[]{"room.sqlite"}:new String[]{"podjs.sqlite","room-progress.sqlite","snapshot.properties"};
            for(String name:names)for(String suffix:new String[]{"","-journal","-wal","-shm",".tmp"})for(String mode:new String[]{"symlink","hardlink"})for(String original:new String[]{"source","snapshot","other"}){fixed(mode,directory,name+suffix,original);cases++;}
            fixed("unknown",directory,"unknown-user-file","source");cases++;
            fixed("directory-link",directory,"ignored","other");cases++;
        }
        initializationFailureClosesAndPreserves();pausedAdmission();System.out.println("MigrationPathSafetyTest PASS ("+cases+" pre-open cases, 80 setup/close failures, durable PAUSED retry)");
        }finally{if(pristine!=null)remove(pristine);}
    }
}
