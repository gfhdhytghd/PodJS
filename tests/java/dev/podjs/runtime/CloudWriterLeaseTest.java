package dev.podjs.runtime;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CloudWriterLeaseTest {
    static void check(boolean value) { if(!value)throw new AssertionError("writer exclusion violated"); }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("podjs-cloud-writer-");
        try (CloudWriterLease local=new CloudWriterLease(); CloudWriterLease worker=new CloudWriterLease()) {
            check(!local.acquire(dir.toFile(),true,false));
            check(local.acquire(dir.toFile(),true,true));
            check(!worker.acquire(dir.toFile(),false,false)); // real FileLock, not a mock busy flag
            boolean duplicate=false;try {local.acquire(dir.toFile(),true,true);} catch(IllegalStateException expected){duplicate=true;}check(duplicate);
            local.close();check(worker.acquire(dir.toFile(),false,false));
            check(!local.acquire(dir.toFile(),true,true)); // owner exception cannot bypass a running cloud writer
            worker.close();check(local.acquire(dir.toFile(),false,false));local.close();
            check(!worker.acquire(dir.toFile(),true,false)); // other local holders still block
        } finally { Files.deleteIfExists(dir.resolve("podjs-cloud-writer.lock"));Files.delete(dir); }
        System.out.println("CloudWriterLeaseTest passed");
    }
}
