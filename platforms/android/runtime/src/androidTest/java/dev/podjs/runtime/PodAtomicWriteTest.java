package dev.podjs.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SyncFailedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real filesystem and output streams; no Android context or storage is needed. */
public class PodAtomicWriteTest {
    private static final byte[] OLD="existing target bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT="complete replacement bytes".getBytes(StandardCharsets.UTF_8);
    private static final class Fixture implements AutoCloseable {
        final Path directory=Files.createTempDirectory("podjs-atomic-write-");
        final File target=directory.resolve("target.bin").toFile();
        final Path unrelated=directory.resolve(".tmp-unrelated.pod-write");
        Fixture()throws IOException{Files.write(target.toPath(),OLD);Files.write(unrelated,OLD);}
        void unchanged()throws IOException{assertArrayEquals(OLD,Files.readAllBytes(target.toPath()));assertArrayEquals(OLD,Files.readAllBytes(unrelated));assertOnlyKnownFiles();}
        void assertOnlyKnownFiles(){String[] names=directory.toFile().list();assertNotNull(names);Arrays.sort(names);assertArrayEquals(new String[]{".tmp-unrelated.pod-write","target.bin"},names);}
        public void close()throws IOException{
            // Every descendant is owned by this fixture, including deliberate
            // cleanup-failure directories. Production only deletes one Path.
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)){
                Path[] entries=paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
                for(Path path:entries)Files.deleteIfExists(path);
            }
        }
    }
    @Test public void completeWriteReplacesTargetWithoutRemovingUnrelatedTemporaryFiles()throws Exception{
        try(Fixture f=new Fixture()){
            PodDeviceServices.atomicWrite(f.target,NEXT,FileOutputStream::new);
            assertArrayEquals(NEXT,Files.readAllBytes(f.target.toPath()));assertArrayEquals(OLD,Files.readAllBytes(f.unrelated));f.assertOnlyKnownFiles();
        }
    }
    @Test public void emptyReplacementIsPublishedAndHasNoTemporaryRemainder()throws Exception{
        try(Fixture f=new Fixture()){
            PodDeviceServices.atomicWrite(f.target,new byte[0],FileOutputStream::new);
            assertEquals(0,Files.size(f.target.toPath()));assertArrayEquals(OLD,Files.readAllBytes(f.unrelated));f.assertOnlyKnownFiles();
        }
    }
    @Test public void openFailureCleansTheAlreadyCreatedEmptyTemporaryAndPreservesOriginalError()throws Exception{
        try(Fixture f=new Fixture()){
            IOException expected=new IOException("injected open failure");
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->{assertTrue(temporary.isFile());throw expected;});fail("Open failure ignored");}
            catch(IOException actual){assertSame(expected,actual);}
            f.unchanged();
        }
    }
    @Test public void partialWriteThenClosedDescriptorFailureCannotPublishOrLeaveTemporaryBytes()throws Exception{
        try(Fixture f=new Fixture()){
            final IOException[] failure={null};
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->new FileOutputStream(temporary){
                @Override public void write(byte[] bytes)throws IOException{
                    super.write(bytes,0,3);super.close();
                    try{super.write(bytes,3,bytes.length-3);fail("Closed descriptor accepted a write");}
                    catch(IOException error){failure[0]=error;throw error;}
                }
            });fail("Write failure ignored");}catch(IOException actual){assertNotNull(failure[0]);assertSame(failure[0],actual);}
            f.unchanged();
        }
    }
    @Test public void realDescriptorSyncFailureRemovesTheTemporaryAndRetainsThePreviousTarget()throws Exception{
        try(Fixture f=new Fixture()){
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->new FileOutputStream(temporary){
                @Override public void write(byte[] bytes)throws IOException{super.write(bytes);super.close();}
            });fail("Sync failure ignored");}catch(SyncFailedException expected){}
            f.unchanged();
        }
    }
    @Test public void flushAndCloseFailuresDoNotPublishAlreadyWrittenBytes()throws Exception{
        for(boolean failFlush:new boolean[]{true,false})try(Fixture f=new Fixture()){
            IOException expected=new IOException(failFlush?"injected flush failure":"injected close failure");
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->new FileOutputStream(temporary){
                @Override public void flush()throws IOException{super.flush();if(failFlush)throw expected;}
                @Override public void close()throws IOException{super.close();if(!failFlush)throw expected;}
            });fail("Stream failure ignored");}catch(IOException actual){assertSame(expected,actual);}
            f.unchanged();
        }
    }
    @Test public void writeFailureRemainsPrimaryWhenClosingTheStreamAlsoFails()throws Exception{
        try(Fixture f=new Fixture()){
            IOException writing=new IOException("injected partial write failure"),closing=new IOException("injected close failure");
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->new FileOutputStream(temporary){
                @Override public void write(byte[] bytes)throws IOException{super.write(bytes,0,3);throw writing;}
                @Override public void close()throws IOException{super.close();throw closing;}
            });fail("Write failure ignored");}catch(IOException actual){assertSame(writing,actual);assertArrayEquals(new Throwable[]{closing},actual.getSuppressed());}
            f.unchanged();
        }
    }
    @Test public void publicationFailurePreservesTheTargetDirectoryAndRemovesOnlyOurTemporary()throws Exception{
        try(Fixture f=new Fixture()){
            Files.delete(f.target.toPath());Files.createDirectory(f.target.toPath());Path child=f.target.toPath().resolve("keep.bin");Files.write(child,OLD);
            try{PodDeviceServices.atomicWrite(f.target,NEXT,FileOutputStream::new);fail("Replaced a nonempty directory");}catch(IOException expected){}
            assertArrayEquals(OLD,Files.readAllBytes(child));assertArrayEquals(OLD,Files.readAllBytes(f.unrelated));f.assertOnlyKnownFiles();
        }
    }
    @Test public void missingTemporaryAtPublicationLeavesTheExistingRegularTargetUntouched()throws Exception{
        try(Fixture f=new Fixture()){
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->new FileOutputStream(temporary){
                @Override public void close()throws IOException{super.close();Files.deleteIfExists(temporary.toPath());}
            });fail("Missing source was published");}catch(java.nio.file.NoSuchFileException expected){}
            f.unchanged();
        }
    }
    @Test public void cleanupFailureIsSuppressedOnTheOriginalErrorWithoutRecursiveDeletion()throws Exception{
        try(Fixture f=new Fixture()){
            IOException expected=new IOException("injected open failure");final Path[] owned={null};
            try{PodDeviceServices.atomicWrite(f.target,NEXT,temporary->{
                owned[0]=temporary.toPath();Files.delete(owned[0]);Files.createDirectory(owned[0]);Files.write(owned[0].resolve("keep.bin"),OLD);throw expected;
            });fail("Open failure ignored");}catch(IOException actual){
                assertSame(expected,actual);assertEquals(1,actual.getSuppressed().length);assertTrue(actual.getSuppressed()[0] instanceof java.nio.file.DirectoryNotEmptyException);
            }
            assertArrayEquals(OLD,Files.readAllBytes(f.target.toPath()));assertArrayEquals(OLD,Files.readAllBytes(f.unrelated));assertArrayEquals(OLD,Files.readAllBytes(owned[0].resolve("keep.bin")));
        }
    }
}
