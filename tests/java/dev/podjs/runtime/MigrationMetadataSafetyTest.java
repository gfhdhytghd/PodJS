package dev.podjs.runtime;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Metadata must not become a write-through alias after the DB guard succeeds. */
public final class MigrationMetadataSafetyTest {
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static void rejectMetadataAliases()throws Exception {
        for(String name:new String[]{"reader-catalog.json","snapshot.properties","room-verification.properties","podjs/podjs-kv.json.tmp","stores-source/shared_prefs/settings.xml","files-source/files/offline/asset.bin","store-results/example/kv.json.tmp"})for(boolean symbolic:new boolean[]{true,false}) {
            File root=Files.createTempDirectory("migration-metadata-").toFile();
            try {
                MigrationPathSafetyTest.copyFixture(root);File source=new File(root,"source.sqlite"),g=new File(root,"g"),bad=new File(g,name);Files.createDirectories(bad.getParentFile().toPath());Files.deleteIfExists(bad.toPath());
                String before=PodRoomV18Converter.hash(source);
                if(symbolic)Files.createSymbolicLink(bad.toPath(),source.toPath());else Files.createLink(bad.toPath(),source.toPath());
                int[] opened={0};PodRoomV18Converter converter=new PodRoomV18Converter(source,file->{opened[0]++;return new RoomV18Test.Jdbc(file);},"fixture");
                try{converter.verify(g);throw new AssertionError("metadata alias admitted");}catch(IOException failure){check("migration_storage_invalid".equals(failure.getMessage()),"unexpected metadata failure");}
                check(opened[0]==0,"opened SQLite before metadata preflight");check(before.equals(PodRoomV18Converter.hash(source)),"source changed through metadata");check(Files.exists(bad.toPath(),LinkOption.NOFOLLOW_LINKS),"metadata alias removed");
            }finally{MigrationPathSafetyTest.remove(root);}
        }
    }
    static void atomicTemporaryAlias()throws Exception {
        File root=Files.createTempDirectory("migration-atomic-alias-").toFile();
        try {
            MigrationPathSafetyTest.copyFixture(root);File source=new File(root,"source.sqlite"),target=new File(root,"journal"),tmp=new File(root,"journal.tmp");String before=PodRoomV18Converter.hash(source);Files.createLink(tmp.toPath(),source.toPath());
            try{PodTakeoverCoordinator.atomic(target,new Properties());throw new AssertionError("atomic truncated a shared inode");}catch(IOException failure){check("migration_storage_invalid".equals(failure.getMessage()),"unexpected atomic failure");}
            check(Files.isSameFile(tmp.toPath(),source.toPath()),"rejected temporary removed");check(before.equals(PodRoomV18Converter.hash(source)),"atomic modified source");
        }finally{MigrationPathSafetyTest.remove(root);}
    }
    static void identicalSourceAndTarget()throws Exception {
        File root=Files.createTempDirectory("migration-identical-path-").toFile();
        try {
            File source=new File(root,"podjs.sqlite");try(RoomV18Test.Jdbc db=new RoomV18Test.Jdbc(source)){RoomV18Test.fixture(db);}String before=PodRoomV18Converter.hash(source);int[] opens={0};
            PodRoomV18Converter room=new PodRoomV18Converter(source,file->{opens[0]++;return new RoomV18Test.Jdbc(file);},"fixture");Method target=PodRoomV18Converter.class.getDeclaredMethod("target",File.class);target.setAccessible(true);
            try{target.invoke(room,root);throw new AssertionError("original source opened as target");}catch(InvocationTargetException failure){check("migration_storage_invalid".equals(failure.getCause().getMessage()),"unexpected identical source failure");}
            check(opens[0]==0&&before.equals(PodRoomV18Converter.hash(source)),"identical source path modified");
        }finally{MigrationPathSafetyTest.remove(root);}
    }
    public static void main(String[]args)throws Exception {
        try{rejectMetadataAliases();atomicTemporaryAlias();identicalSourceAndTarget();System.out.println("MigrationMetadataSafetyTest PASS (14 metadata aliases, atomic temporary, identical source path)");}
        finally{if(MigrationPathSafetyTest.pristine!=null){MigrationPathSafetyTest.remove(MigrationPathSafetyTest.pristine);MigrationPathSafetyTest.pristine=null;}}
    }
}
