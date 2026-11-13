package dev.podjs.runtime;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
public class PodScratchIdentityTest {
    static void remove(File root)throws Exception {
        try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
        }
    }
    static Throwable invoke(Method method,Object owner,Object...args)throws Exception {
        try{method.invoke(owner,args);return null;}catch(InvocationTargetException failure){return failure.getCause();}
    }
    static final class SwappedDirectory extends File {
        final File replacement,held;int listings;
        SwappedDirectory(File file,File replacement){super(file.getPath());this.replacement=replacement;held=new File(file+"-held");}
        @Override public File[] listFiles() {
            File[] entries=super.listFiles();
            if(++listings==1)try{Files.move(toPath(),held.toPath());Files.createSymbolicLink(toPath(),replacement.toPath());}
            catch(IOException failure){throw new UncheckedIOException(failure);}
            return entries;
        }
    }
    @Test public void clearUsesOnlyTheValidatedListingAndRetainsReplacedDirectories()throws Exception {
        File root=Files.createTempDirectory("scratch-listing-").toFile();
        try {
            Method clear=PodRoomV18Converter.class.getDeclaredMethod("clearScratch",File.class,boolean.class);clear.setAccessible(true);
            for(String name:new String[]{"capture-work","verification-work","capture-00000000-0000-0000-0000-000000000001","verify-00000000-0000-0000-0000-000000000001"}) {
                File original=new File(root,name),protectedDir=new File(root,"protected-"+name);original.mkdir();protectedDir.mkdir();
                File user=new File(protectedDir,"unknown-user-file");byte[] bytes={1,2,3};Files.write(user.toPath(),bytes);
                SwappedDirectory work=new SwappedDirectory(original,protectedDir);
                Throwable failure=invoke(clear,null,work,name.startsWith("capture"));
                assertTrue("unknown file deleted for "+name,user.isFile());assertArrayEquals(bytes,Files.readAllBytes(user.toPath()));
                assertEquals("cleanup re-enumerated the directory",1,work.listings);
                assertNotNull("replaced directory was admitted",failure);assertEquals("verification_scratch_invalid",failure.getMessage());
                assertTrue(Files.isSymbolicLink(original.toPath()));assertTrue(work.held.isDirectory());
            }
        } finally {remove(root);}
    }
    @Test public void attachRechecksDirectoryAndProgressIdentityBeforeAnyWrite()throws Exception {
        for(String mode:new String[]{"directory-link","directory-regular","progress-link","progress-regular"}) {
            File root=Files.createTempDirectory("scratch-attach-").toFile();
            try {
                File source=new File(root,"source.sqlite"),work=new File(root,"verification-work"),progress=new File(work,"room-progress.sqlite");work.mkdir();
                try(PodRoomAndroidDb db=new PodRoomAndroidDb(source)){db.exec("CREATE TABLE original(value TEXT)");db.exec("INSERT INTO original VALUES('retained')");}
                Files.write(progress.toPath(),new byte[0]);String before=PodRoomV18Converter.hash(source);
                List<String> sqls=new ArrayList<>();int[] closed={0};boolean[] swapped={false};
                PodRoomV18Converter room=new PodRoomV18Converter(source,file->{
                    PodRoomV18Converter.Db actual=new PodRoomAndroidDb(file);
                    return new PodRoomV18Converter.Db(){
                        public List<Map<String,Object>> query(String sql,Object...args)throws Exception {
                            List<Map<String,Object>> result=actual.query(sql,args);
                            if(!swapped[0]) {
                                swapped[0]=true;
                                if(mode.startsWith("directory")) {
                                    Files.move(work.toPath(),new File(root,"held").toPath());File replacement=new File(root,"replacement");replacement.mkdir();
                                    if(mode.endsWith("link")) {Files.createSymbolicLink(new File(replacement,"room-progress.sqlite").toPath(),source.toPath());Files.createSymbolicLink(work.toPath(),replacement.toPath());}
                                    else {work.mkdir();Files.copy(source.toPath(),progress.toPath());}
                                } else {
                                    Files.move(progress.toPath(),new File(work,"held-progress").toPath());
                                    if(mode.endsWith("link"))Files.createSymbolicLink(progress.toPath(),source.toPath());else Files.copy(source.toPath(),progress.toPath());
                                }
                            }
                            return result;
                        }
                        public void exec(String sql,Object...args)throws Exception{sqls.add(sql);actual.exec(sql,args);}
                        public void close()throws Exception{closed[0]++;actual.close();}
                    };
                },"fixture");
                Method target=PodRoomV18Converter.class.getDeclaredMethod("target",File.class);target.setAccessible(true);
                Throwable failure=invoke(target,room,work);
                assertTrue("swap not injected",swapped[0]);assertNotNull("replaced object admitted",failure);
                assertEquals(mode,"verification_scratch_invalid",failure.getMessage());
                assertEquals(mode+" connection leaked",1,closed[0]);
                assertTrue(mode+" missing DETACH",sqls.contains("DETACH DATABASE migration"));
                assertFalse(mode+" wrote after replacement",sqls.stream().anyMatch(sql->sql.startsWith("CREATE ")||sql.startsWith("PRAGMA migration.")));
                assertEquals(mode+" source modified",before,PodRoomV18Converter.hash(source));
            } finally {remove(root);}
        }
    }
}
