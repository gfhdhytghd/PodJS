package dev.podjs.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Regression for exits inside verification, rather than only at phase boundaries. */
public final class MigrationRecoveryTest {
    static final class PowerLoss extends Error {}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void remove(File f)throws Exception {
        if(!f.exists())return;
        try(java.util.stream.Stream<Path> paths=Files.walk(f.toPath())) {
            for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);
        }
    }
    static void verificationReplay()throws Exception {
        File root=Files.createTempDirectory("migration-recovery-").toFile();
        try {
            File source=new File(root,"source.sqlite"),g=new File(root,"g");g.mkdirs();
            try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(source)){RoomV18Test.fixture(db);}
            PodRoomV18Converter normal=new PodRoomV18Converter(source,RoomV18Test.Jdbc::new,"fixture",2,c->{});
            normal.snapshot(g);normal.copy(g);
            for(int i=0;i<3;i++) {
                final boolean[] stopped={false};
                PodRoomV18Converter interrupted=new PodRoomV18Converter(source,path->{
                    RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(path);
                    boolean audit=path.getParentFile().getName().startsWith("verify-")||path.getParentFile().getName().equals("verification-work");
                    return new PodRoomV18Converter.Db(){
                        public List<Map<String,Object>> query(String sql,Object... args)throws Exception{return db.query(sql,args);}
                        public void exec(String sql,Object... args)throws Exception {
                            db.exec(sql,args);
                            if(audit&&sql.equals("COMMIT")&&!stopped[0]){stopped[0]=true;throw new PowerLoss();}
                        }
                        public void close()throws Exception{db.close();}
                    };
                },"fixture",2,c->{});
                try{interrupted.verify(g);throw new AssertionError("interruption missing");}catch(PowerLoss expected){}
            }
            File[] scratch=g.listFiles(f->f.getName().startsWith("verify-")||f.getName().equals("verification-work"));
            check(scratch!=null&&scratch.length==1,"verification exit leaked "+(scratch==null?-1:scratch.length)+" audit databases");
            String receipt=normal.verify(g);check(receipt.equals(normal.verify(g)),"replay receipt changed");
            try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(new File(g,"podjs.sqlite"))) {
                check(PodRoomV18Converter.number(PodRoomV18Converter.scalar(db,"SELECT count(*) FROM articles"))==3,"duplicate rows");
            }
        }finally{remove(root);}
    }
    static void timeoutAndMemoryPause()throws Exception {
        for(String failure:new String[]{"timeout","memory"}) {
            File root=Files.createTempDirectory("migration-budget-").toFile();
            try {
                boolean[] fail={true};
                PodTakeoverCoordinator.Converter converter=new PodTakeoverCoordinator.Converter(){
                    public String version(){return "budget-fixture";}
                    public void snapshot(File g)throws Exception{Files.writeString(new File(g,"snapshot").toPath(),"fixed");}
                    public void resume(File g,PodTakeoverCoordinator.Phase p){}
                    public void copy(File g)throws Exception {
                        if(fail[0]){if(failure.equals("memory"))throw new OutOfMemoryError("synthetic");Thread.sleep(10000);}
                        for(String path:new String[]{"podjs.sqlite","podjs/podjs-kv.json","podjs/secure.store","podjs/files/body"}){
                            File f=new File(g,path);f.getParentFile().mkdirs();Files.writeString(f.toPath(),"fixture");
                        }
                    }
                    public String verify(File g){return "a".repeat(64);}
                };
                PodTakeoverCoordinator c=new PodTakeoverCoordinator(root,()->new PodTakeoverCoordinator.Source(true,null,18,0,Long.MAX_VALUE),converter).withTimeout(200);
                Properties paused=c.run();check("PAUSED".equals(paused.getProperty("phase")),"failure must pause");
                check((failure.equals("memory")?"migration_memory_limit":"migration_timeout").equals(paused.getProperty("reason")),"stable error code");
                check(!new File(root,"active").exists(),"failed import admitted");
                String generation=paused.getProperty("generation");fail[0]=false;
                Properties active=c.withTimeout(10000).run();check("ACTIVE".equals(active.getProperty("phase")),"retry did not recover");
                check(generation.equals(active.getProperty("generation")),"retry changed generation");
                check(!Thread.currentThread().isInterrupted(),"deadline interrupted a later attempt");
            }finally{remove(root);}
        }
    }
    static void legacyScratchBeforeTargetWrite()throws Exception {
        File root=Files.createTempDirectory("migration-legacy-scratch-").toFile();
        try {
            File source=new File(root,"source.sqlite"),g=new File(root,"g");g.mkdirs();
            try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(source)){RoomV18Test.fixture(db);}
            PodRoomV18Converter room=new PodRoomV18Converter(source,RoomV18Test.Jdbc::new,"fixture");room.snapshot(g);room.copy(g);
            File legacy=new File(g,"verify-00000000-0000-0000-0000-000000000001");legacy.mkdirs();
            Files.copy(new File(g,"podjs.sqlite").toPath(),new File(legacy,"podjs.sqlite").toPath());
            PodRoomV18Converter constrained=new PodRoomV18Converter(source,path->{
                if(path.equals(new File(g,"podjs.sqlite"))&&legacy.exists())throw new IOException("simulated_disk_full");
                return new RoomV18Test.Jdbc(path);
            },"fixture");
            constrained.resume(g,PodTakeoverCoordinator.Phase.COPYING);check(!legacy.exists(),"legacy audit retained until after a target write");
            String digest=PodRoomV18Converter.hash(source);
            legacy.mkdirs();File unknown=new File(legacy,"unknown-user-file");Files.writeString(unknown.toPath(),"retain");
            try{constrained.resume(g,PodTakeoverCoordinator.Phase.COPYING);throw new AssertionError("unknown scratch admitted");}
            catch(IOException expected){check("verification_scratch_invalid".equals(expected.getMessage()),"unexpected scratch failure");}
            check(unknown.isFile(),"unknown file deleted");check(digest.equals(PodRoomV18Converter.hash(source)),"source changed during scratch recovery");
        }finally{remove(root);}
    }
    public static void main(String[] args)throws Exception {
        verificationReplay();timeoutAndMemoryPause();legacyScratchBeforeTargetWrite();System.out.println("MigrationRecoveryTest PASS (verification replay, timeout, memory, retry, legacy scratch ownership)");
    }
}
