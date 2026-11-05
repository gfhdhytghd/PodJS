package dev.podjs.runtime;

import dev.podjs.takeover.NativeBackup;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Host-approved inactive generations only. Caller holds the package admission lock.
 * Never reads user payloads or deletes backup/recovery entries. No default policy. */
public final class PodInactiveGenerationCollector {
    public static final class Result {
        public final int deletedEntries; public final long deletedBytes;
        Result(int entries,long bytes){deletedEntries=entries;deletedBytes=bytes;}
    }
    interface Hook {
        void deleted() throws IOException;
        default void listed(File directory) throws IOException {}
    }
    private final File sandbox,root; private final Hook hook;
    private int budget,deleted;private long bytes;
    PodInactiveGenerationCollector(File sandbox,Hook hook)throws IOException {
        this.sandbox=sandbox.getCanonicalFile();root=new File(this.sandbox,"files/package-takeover");this.hook=hook;
    }
    static Result collect(File sandbox,Collection<String> approved,int maxEntries)throws IOException {
        return new PodInactiveGenerationCollector(sandbox,()->{}).collect(approved,maxEntries);
    }
    private static boolean exists(File f){return Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS);}
    private static void safe(File f)throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("generation_gc_symlink");
        for(Path p=f.toPath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("generation_gc_symlink");
    }
    private File[] children(File f)throws IOException {safe(f);hook.listed(f);File[] a=f.listFiles();if(a==null)throw new IOException("generation_gc_unreadable");Arrays.sort(a,Comparator.comparing(File::getName));return a;}
    private static boolean validId(String id){return id!=null&&id.matches("g-[a-zA-Z0-9-]+");}
    private static void id(String id)throws IOException {if(!validId(id))throw new IOException("generation_gc_invalid_id");}
    private int visited;
    private void preflight(File f)throws IOException {
        safe(f);if(++visited>100000)throw new IOException("generation_gc_capacity");
        if(Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS))for(File c:children(f))preflight(c);
        else if(!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("generation_gc_file_type");
    }
    Result collect(Collection<String> approved,int maxEntries)throws IOException {
        if(approved==null||approved.size()>128||maxEntries<1||maxEntries>4096)throw new IOException("generation_gc_policy_invalid");
        budget=maxEntries;safe(root);
        Properties journal=PodTakeoverCoordinator.read(new File(root,"journal")),active=PodTakeoverCoordinator.read(new File(root,"active"));
        String current=active.getProperty("generation");id(current);
        if(!"1".equals(journal.getProperty("protocol"))||!"ACTIVE".equals(journal.getProperty("phase"))||!current.equals(journal.getProperty("generation"))||!active.getProperty("receipt","").matches("[a-f0-9]{64}")||!active.getProperty("receipt").equals(journal.getProperty("receipt")))throw new IOException("generation_gc_not_active");
        File generations=new File(root,"generations");safe(generations);
        if(!active.equals(PodTakeoverCoordinator.read(new File(generations,current+"/activation-receipt"))))throw new IOException("generation_gc_receipt_mismatch");
        // Recovery may archive the entire takeover tree; do not overlap a recovery,
        // including completed history whose external retention policy is unknown.
        for(String p:new String[]{NativeBackup.RECOVERED,"files/.podjs-restore-stage","files/.podjs-recovery-state"}){
            File f=new File(sandbox,p);safe(f);if(exists(f))throw new IOException("generation_gc_recovery_present");
        }
        Set<String> protectedIds=new HashSet<>(new NativeBackup(sandbox).retainedGenerations());protectedIds.add(current);
        // The singleton policy "inactive" is portable across users. All selection,
        // protection and preflight happen under both admission/recovery locks.
        SortedSet<String> ids=new TreeSet<>();
        if(approved.size()==1&&approved.contains("inactive")) {
            for(File child:children(generations)) {
                String value=child.getName();
                if(!validId(value)||protectedIds.contains(value))continue;
                safe(child);
                if(Files.isDirectory(child.toPath(),LinkOption.NOFOLLOW_LINKS))ids.add(value);
            }
            // No completion marker: a later generation must be discovered. When
            // clean, this lists only the root, never protected/deleted trees.
            if(ids.isEmpty())return new Result(0,0);
        } else {
            for(String value:approved){id(value);if(!ids.add(value))throw new IOException("generation_gc_duplicate_id");if(protectedIds.contains(value))throw new IOException("generation_gc_protected");}
        }
        // Validate every selected tree before the first unlink. Failed/partial
        // generations need not have activation receipts of their own.
        for(String value:ids){File f=new File(generations,value);safe(f);if(exists(f)){if(!Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("generation_gc_file_type");preflight(f);}}
        for(String value:ids){File f=new File(generations,value);if(exists(f)&&budget>0)remove(f);}
        return new Result(deleted,bytes);
    }
    private boolean remove(File f)throws IOException {
        if(budget==0)return false;safe(f);
        if(Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS))for(File c:children(f))if(!remove(c))return false;
        if(budget==0)return false;
        long size=Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)?f.length():0;
        Files.delete(f.toPath());PodTakeoverCoordinator.syncDirectory(f.getParentFile());budget--;deleted++;bytes+=size;hook.deleted();return true;
    }
}
