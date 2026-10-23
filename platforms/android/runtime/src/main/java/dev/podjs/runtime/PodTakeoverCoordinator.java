package dev.podjs.runtime;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Properties;

/** Host-only publication protocol. Callbacks must be idempotent and validate durable
 * receipts on replay. ACTIVE is terminal: never re-import over new user writes. */
public final class PodTakeoverCoordinator {
    public enum Phase { DETECTED, SNAPSHOTTED, COPYING, VERIFYING, READY, ACTIVATING, ACTIVE, PAUSED, BLOCKED }
    public interface Converter {
        String version();
        /** Explicit compatibility transition; unrelated converter changes remain blocked. */
        default boolean reclassifies(File generation,String previous)throws Exception{return false;}
        default boolean complete() { return true; }
        /** Fixed non-sensitive code; null only when every blocking verification is cleared. */
        default String blocker(File generation) throws Exception { return null; }
        /** Read current production stores without comparing them to the old import. */
        default void active(File generation) throws Exception {}
        default void activeFast(File generation) throws Exception {active(generation);}
        default void activeDeep(File generation) throws Exception {active(generation);}
        void snapshot(File generation) throws Exception;
        void copy(File generation) throws Exception;
        /** Recheck fixed snapshot identity, converter version, cursors and batch receipts. */
        void resume(File generation, Phase phase) throws Exception;
        String verify(File generation) throws Exception;
    }
    /** P2b provides real callbacks. An absent converter must never publish empty data. */
    public static final Converter EMPTY = new Converter() {
        public String version() { return "unavailable"; }
        public void snapshot(File g) throws Exception { throw new IOException("converter_unavailable"); }
        public void resume(File g, Phase p) throws Exception { throw new IOException("converter_unavailable"); }
        public void copy(File g) throws Exception { throw new IOException("converter_unavailable"); }
        public String verify(File g) throws Exception { throw new IOException("converter_unavailable"); }
    };
    public static final class Source {
        public final boolean present;
        public final String block;
        public final long requiredBytes, availableBytes;
        public final int schema;
        public final Properties inventory = new Properties();
        public Source(boolean present, String block, int schema, long required, long available) {
            this.present=present; this.block=block; this.schema=schema; requiredBytes=required; availableBytes=available;
        }
    }
    public interface Probe { Source detect() throws Exception; }
    public interface Checkpoint {
        void saved(Phase phase);
        default void published() {}
        /** Sanitized release diagnostics are supplied by the Android host. */
        default void failed(Phase phase, Exception error) {}
        default void timed(String step,long nanos) {}
    }
    public interface Publisher {
        default void beforeReclassification() throws Exception {}
        default void afterSnapshot(File generation) throws Exception {}
        void beforePublish(File generation) throws Exception;
        default void afterActive(File generation) throws Exception {}
    }
    private static final Publisher NO_PUBLISHER=g -> {};
    private final Publisher publisher;
    private static final Object PROCESS_LOCK = new Object();
    private final File root;
    private final Probe probe;
    private final Converter converter;
    private final Checkpoint checkpoint;
    public PodTakeoverCoordinator(File root, Probe probe, Converter converter) { this(root,probe,converter,p -> {},NO_PUBLISHER); }
    PodTakeoverCoordinator(File root, Probe probe, Converter converter, Checkpoint checkpoint) {
        this(root,probe,converter,checkpoint,NO_PUBLISHER);
    }
    PodTakeoverCoordinator(File root, Probe probe, Converter converter, Checkpoint checkpoint,Publisher publisher) {
        this.root=root; this.probe=probe; this.converter=converter; this.checkpoint=checkpoint;this.publisher=publisher;
    }
    private PodActiveValidation activeValidation;
    PodTakeoverCoordinator withActiveValidation(PodActiveValidation validation){activeValidation=validation;return this;}
    public Properties run() throws Exception {
        long runBegan=System.nanoTime();
        synchronized (PROCESS_LOCK) {
            // Read-only preflight also covers an existing empty takeover directory
            // (or empty journal). A NONE result must never create even a lock file.
            if (read(new File(root,"journal")).isEmpty()) {
                if (new File(root,"active").exists()) throw new IOException("orphan_pointer");
                if (!probe.detect().present) return fresh();
            }
            Files.createDirectories(root.toPath());
            syncDirectory(root.getParentFile());
            safeFile(new File(root,"lock"));
            try (FileChannel lock=FileChannel.open(new File(root,"lock").toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS); FileLock held=lock.lock()) {
                Properties p=read(new File(root,"journal"));
                if (p.isEmpty()) {
                    Source source=probe.detect();
                    if (!source.present) {
                        if (new File(root,"active").exists()) throw new IOException("orphan_pointer");
                        return fresh();
                    }
                    p.putAll(source.inventory);
                    p.setProperty("protocol","1"); p.setProperty("converter",converter.version());
                    p.setProperty("generation","g-"+java.util.UUID.randomUUID());
                    p.setProperty("schema",Integer.toString(source.schema));
                    p.setProperty("requiredBytes",Long.toString(source.requiredBytes));
                    p.setProperty("availableBytes",Long.toString(source.availableBytes));
                    save(p,Phase.DETECTED);
                }
                if (!"1".equals(p.getProperty("protocol"))) throw new IOException("protocol_unsupported");
                Phase phase=Phase.valueOf(p.getProperty("phase"));
                if (phase==Phase.ACTIVE) {
                    checkpoint.timed("activeJournalLock",System.nanoTime()-runBegan);
                    long began=System.nanoTime(); validatePointer(p);checkpoint.timed("activePointer",System.nanoTime()-began);
                    File g=generation(p.getProperty("generation"));
                    began=System.nanoTime();
                    try {
                        if(activeValidation==null)converter.active(g);
                        else activeValidation.require(g,p.getProperty("receipt"),()->converter.activeFast(g));
                    } catch(Exception error) {
                        if(activeValidation==null)throw error;
                        checkpoint.failed(Phase.ACTIVE,error);
                        p.setProperty("blockedFromPhase","ACTIVE");
                        p.setProperty("reason","active_deep_validation_failed".equals(error.getMessage())?"active_deep_validation_failed":"active_validation_failed");
                        save(p,Phase.BLOCKED);return p;
                    }
                    checkpoint.timed("activeConverters",System.nanoTime()-began);
                    began=System.nanoTime();publisher.afterActive(g);checkpoint.timed("activePublisher",System.nanoTime()-began);return p;
                }
                // A fixed host may recognize a guest schema rejected by an older
                // fast check. Revalidate the published generation in place. Never
                // copy/import, change the pointer, or override a durable deep FAIL.
                if (phase==Phase.BLOCKED && activeValidation!=null && "ACTIVE".equals(p.getProperty("blockedFromPhase")) && "active_validation_failed".equals(p.getProperty("reason"))) {
                    validatePointer(p);File published=generation(p.getProperty("generation"));
                    try {
                        activeValidation.require(published,p.getProperty("receipt"),()->converter.activeFast(published));
                        converter.active(published);
                    } catch(Exception error) { checkpoint.failed(Phase.ACTIVE,error);return p; }
                    activeValidation.completed(published,p.getProperty("receipt"),true);
                    save(p,Phase.ACTIVE);publisher.afterActive(published);return p;
                }
                if (phase==Phase.BLOCKED) {
                    if(!"unknown_user_files".equals(p.getProperty("reason")))return p;
                    File previous=generation(p.getProperty("generation"));
                    if(!converter.reclassifies(previous,p.getProperty("converter","")))return p;
                    // A verification blocker cannot authorize replay after publication started.
                    if(p.containsKey("receipt")||(!p.getProperty("resumePhase","").isEmpty()&&!"VERIFYING".equals(p.getProperty("resumePhase")))||(!p.getProperty("blockedFromPhase","").isEmpty()&&!"VERIFYING".equals(p.getProperty("blockedFromPhase"))))throw new IOException("unexpected_activation");
                    if(Files.exists(new File(root,"active").toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("unexpected_pointer");
                    File candidates=new File(root,"generations");safeFile(candidates);
                    File[] retained=candidates.listFiles();if(retained==null)throw new IOException("candidate_unreadable");
                    for(File candidate:retained) {
                        safeFile(candidate);
                        if(!Files.isDirectory(candidate.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("candidate_invalid");
                        for(String receipt:new String[]{"activation-receipt","native-backup-receipt"})
                            if(Files.exists(new File(candidate,receipt).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("unexpected_activation");
                    }
                    publisher.beforeReclassification();
                    Source refreshed=probe.detect();
                    if(!refreshed.present||refreshed.block!=null)return p;
                    p.putAll(refreshed.inventory);
                    p.setProperty("converter",converter.version());p.setProperty("generation","g-"+java.util.UUID.randomUUID());
                    p.setProperty("schema",Integer.toString(refreshed.schema));
                    save(p,Phase.DETECTED);phase=Phase.DETECTED;
                }
                if (!converter.version().equals(p.getProperty("converter"))) {
                    p.setProperty("reason","converter_changed_candidate_retained"); save(p,Phase.BLOCKED); return p;
                }
                if (phase==Phase.PAUSED) {
                    phase=Phase.valueOf(p.getProperty("resumePhase"));
                    if (phase==Phase.DETECTED && "stage_failed".equals(p.getProperty("reason"))) {
                        // No complete bundle exists yet and publication has never started.
                        // Retain the failed private candidate; a changed file must not collide
                        // with its old copy on every retry. Journal the fresh identity BEFORE
                        // writing any new snapshot so process death replays only that attempt.
                        File previous=generation(p.getProperty("generation"));
                        if (Files.exists(new File(root,"active").toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IOException("unexpected_pointer");
                        for(String receipt:new String[]{"activation-receipt","native-backup-receipt"})
                            if(Files.exists(new File(previous,receipt).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("unexpected_activation");
                        p.setProperty("generation","g-"+java.util.UUID.randomUUID());
                        save(p,Phase.DETECTED);
                    }
                }
                File generation=generation(p.getProperty("generation"));
                try {
                    if (phase!=Phase.DETECTED) converter.resume(generation,phase);
                    while (true) {
                        switch (phase) {
                            case DETECTED:
                                Source source=probe.detect();
                                p.setProperty("requiredBytes",Long.toString(source.requiredBytes));
                                p.setProperty("availableBytes",Long.toString(source.availableBytes));
                                if ("user_locked".equals(source.block)) throw new IOException("user_locked");
                                if (!source.present || source.block!=null) {
                                    p.setProperty("reason",source.block==null?"source_missing":source.block);
                                    save(p,Phase.BLOCKED); return p;
                                }
                                if (source.availableBytes<source.requiredBytes) throw new IOException("space_insufficient");
                                Files.createDirectories(generation.toPath());
                                converter.snapshot(generation);
                                phase=Phase.SNAPSHOTTED; break;
                            case SNAPSHOTTED: publisher.afterSnapshot(generation); phase=Phase.COPYING; break;
                            case COPYING: converter.copy(generation); phase=Phase.VERIFYING; break;
                            case VERIFYING:
                                String receipt=converter.verify(generation);
                                if (receipt==null || !receipt.matches("[a-f0-9]{64}")) throw new IOException("receipt_invalid");
                                if (blocked(p,generation)) return p;
                                if (!converter.complete()) {
                                    p.setProperty("reason","converters_incomplete");
                                    p.setProperty("resumePhase",Phase.VERIFYING.name());
                                    save(p,Phase.PAUSED); return p;
                                }
                                p.setProperty("receipt",receipt); phase=Phase.READY; break;
                            case READY:
                                if (blocked(p,generation)) return p;
                                phase=Phase.ACTIVATING; break;
                            case ACTIVATING:
                                if (blocked(p,generation)) return p;
                                if (!p.getProperty("receipt","").equals(converter.verify(generation))) throw new IOException("receipt_changed");
                                if (!converter.complete()) throw new IOException("converters_incomplete");
                                publisher.beforePublish(generation);
                                Properties activation=new Properties();
                                activation.setProperty("generation",p.getProperty("generation"));
                                activation.setProperty("receipt",p.getProperty("receipt"));
                                atomic(new File(generation,"activation-receipt"),activation);
                                syncTree(generation);
                                syncDirectory(generation.getParentFile());
                                syncDirectory(root);
                                Properties pointer=new Properties(); pointer.setProperty("generation",p.getProperty("generation"));
                                pointer.setProperty("receipt",p.getProperty("receipt"));
                                atomic(new File(root,"active"),pointer);
                                checkpoint.published();
                                validatePointer(p);
                                phase=Phase.ACTIVE; break;
                            case ACTIVE:
                                validatePointer(p);
                                try{converter.active(generation);} // READY->ACTIVE still full.
                                catch(Exception error){if(activeValidation!=null)activeValidation.completed(generation,p.getProperty("receipt"),false);throw error;}
                                if(activeValidation!=null)activeValidation.completed(generation,p.getProperty("receipt"),true);
                                publisher.afterActive(generation);return p;
                            default: throw new IOException("phase_invalid");
                        }
                        save(p,phase);
                    }
                } catch (Exception error) {
                    if(phase==Phase.ACTIVE)throw error; // committed generation is terminal, even if readback fails
                    checkpoint.failed(phase,error);
                    p.setProperty("resumePhase",phase.name());
                    // Converter exceptions can contain secrets or URLs; persist only fixed codes.
                    p.setProperty("reason",error instanceof IOException && ("space_insufficient".equals(error.getMessage()) || "user_locked".equals(error.getMessage()))?error.getMessage():converter==EMPTY?"converter_unavailable":"stage_failed");
                    save(p,Phase.PAUSED); return p;
                }
            }
        }
    }
    private boolean blocked(Properties p,File generation)throws Exception {
        String reason=converter.blocker(generation);
        if(reason==null)return false;
        if(!reason.matches("[a-z0-9_]{1,96}"))reason="verification_blocked";
        p.setProperty("blockedFromPhase",p.getProperty("phase"));p.setProperty("reason",reason);save(p,Phase.BLOCKED);return true;
    }
    private static Properties fresh() { Properties p=new Properties(); p.setProperty("phase","NONE"); return p; }
    private void save(Properties p, Phase phase) throws IOException {
        if (phase!=Phase.PAUSED && phase!=Phase.BLOCKED) { p.remove("reason"); p.remove("resumePhase"); p.remove("blockedFromPhase"); }
        p.setProperty("phase",phase.name()); atomic(new File(root,"journal"),p); checkpoint.saved(phase);
    }
    private void validatePointer(Properties p) throws IOException {
        Properties active=read(new File(root,"active"));
        if (!p.getProperty("generation","").equals(active.getProperty("generation")) ||
            !p.getProperty("receipt","").equals(active.getProperty("receipt"))) throw new IOException("pointer_mismatch");
        File g=generation(active.getProperty("generation"));
        if (!g.isDirectory()) throw new IOException("generation_missing");
        Properties receipt=read(new File(g,"activation-receipt"));
        if(!active.equals(receipt))throw new IOException("activation_receipt_mismatch");
        for(String path:new String[]{"podjs.sqlite","podjs/podjs-kv.json","podjs/secure.store"}) {
            File file=new File(g,path);
            if(!file.getCanonicalFile().equals(file.getAbsoluteFile())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()==0)throw new IOException("active_store_missing");
        }
        File files=new File(g,"podjs/files");
        if(!files.getCanonicalFile().equals(files.getAbsoluteFile())||!Files.isDirectory(files.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("active_files_missing");
    }
    File generation(String id) throws IOException {
        if (id==null || !id.matches("g-[a-zA-Z0-9-]+")) throw new IOException("generation_invalid");
        File directory=new File(root,"generations/"+id);
        if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile())) throw new IOException("generation_symlink");
        return directory;
    }
    public File activeGeneration(Properties admitted) throws IOException {
        if ("NONE".equals(admitted.getProperty("phase"))) return null;
        if (!"ACTIVE".equals(admitted.getProperty("phase"))) throw new IOException("takeover_pending");
        long began=System.nanoTime();validatePointer(admitted);
        checkpoint.timed("activeResolvePointer",System.nanoTime()-began);
        return generation(admitted.getProperty("generation"));
    }
    private static void safeFile(File file)throws IOException {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile()))throw new IOException("takeover_symlink");
        for(Path path=file.toPath().toAbsolutePath();path!=null;path=path.getParent())if(Files.isSymbolicLink(path))throw new IOException("takeover_symlink");
    }
    static Properties read(File file) throws IOException {
        safeFile(file);Properties p=new Properties();
        if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){p.load(in);}return p;
    }
    static void atomic(File file, Properties p) throws IOException {
        safeFile(file);File temporary=new File(file.getParentFile(),file.getName()+".tmp");safeFile(temporary);
        try(FileChannel channel=FileChannel.open(temporary.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            OutputStream out=java.nio.channels.Channels.newOutputStream(channel);p.store(out,null);out.flush();channel.force(true);
        }
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(file.getParentFile());
    }
    static void syncDirectory(File directory) throws IOException {
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ)){channel.force(true);}
    }
    private static void syncTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("candidate_symlink");
        if (file.isDirectory()) {
            File[] children=file.listFiles(); if(children==null)throw new IOException("candidate_unreadable");
            for(File child:children)syncTree(child); syncDirectory(file);
        } else try(RandomAccessFile data=new RandomAccessFile(file,"rw")){data.getFD().sync();}
    }
}
