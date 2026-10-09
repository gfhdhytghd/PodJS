package dev.podjs.runtime;

import java.io.*;
import java.util.Properties;
import java.util.function.LongSupplier;

/** Receipt-bound history. No guest data is read here; only a bounded record is durable.
 * The later deadline wins: updates cannot bypass the seven-day global interval. */
final class PodActiveValidation {
    static final long INTERVAL=7L*24*60*60*1000;
    interface Check { void run() throws Exception; }
    private final long version;
    private final LongSupplier clock;
    PodActiveValidation(long version,LongSupplier clock){this.version=version;this.clock=clock;}
    private static File file(File g){return new File(g,"active-validation.properties");}
    private Properties read(File g,String receipt)throws IOException {
        File f=file(g);
        if(f.exists()&&(f.length()==0||f.length()>4096))throw new IOException("active_validation_record_invalid");
        Properties p=PodTakeoverCoordinator.read(f);
        if(!p.isEmpty()&&(!"1".equals(p.getProperty("protocol"))||!receipt.equals(p.getProperty("receipt"))))
            throw new IOException("active_validation_record_invalid");
        if(!p.isEmpty()&&!("PASS".equals(p.getProperty("result"))||"FAIL".equals(p.getProperty("result"))))
            throw new IOException("active_validation_record_invalid");
        if(!p.isEmpty()){number(p,"checkedAt");number(p,"version");}
        return p;
    }
    private static long number(Properties p,String key)throws IOException {
        try{long v=Long.parseLong(p.getProperty(key));if(v<0)throw new NumberFormatException();return v;}
        catch(RuntimeException invalid){throw new IOException("active_validation_record_invalid");}
    }
    void require(File g,String receipt,Check fast)throws Exception {
        Properties p=read(g,receipt);
        if("FAIL".equals(p.getProperty("result")))throw new IOException("active_deep_validation_failed");
        // Pre-T24 ACTIVE receipts were published only after full verification.
        // Missing history is legacy verification, not a fabricated fresh PASS.
        fast.run();
    }
    boolean due(File g,String receipt)throws IOException {
        Properties p=read(g,receipt);
        if("FAIL".equals(p.getProperty("result")))return false;
        if(p.isEmpty())return true;
        long now=clock.getAsLong(),last=number(p,"checkedAt");
        // A backwards wall clock postpones work; it must not create a tight loop.
        return now>=last&&now-last>=INTERVAL;
    }
    void completed(File g,String receipt,boolean pass)throws IOException {
        Properties p=new Properties();p.setProperty("protocol","1");p.setProperty("receipt",receipt);
        p.setProperty("result",pass?"PASS":"FAIL");p.setProperty("version",Long.toString(version));
        p.setProperty("checkedAt",Long.toString(Math.max(0,clock.getAsLong())));
        PodTakeoverCoordinator.atomic(file(g),p);
    }
    boolean deep(File g,String receipt,Check check)throws Exception {
        if(!due(g,receipt))return false;
        try{check.run();}catch(Yield busy){throw busy;}
        catch(Exception failed){completed(g,receipt,false);throw failed;}
        completed(g,receipt,true);return true;
    }
    /** Cancellation/busy is contention, never evidence of corrupt data. */
    static final class Yield extends IOException {Yield(){super("active_validation_yield");}}
    /** Foreground SQL cancels the optional reader before attempting a write.
     * No writer waits on this monitor for a scan to finish. SQLite releases its
     * read lock when cancellation closes the cursor/connection. */
    static final class Workload {
        private int foreground;
        private Reader reader;
        synchronized Reader read(Runnable cancel)throws Yield {
            if(foreground!=0||reader!=null)throw new Yield();
            reader=new Reader(this,cancel);return reader;
        }
        synchronized AutoCloseable foreground() {
            foreground++;
            if(reader!=null){reader.cancelled=true;reader.cancel.run();}
            return ()->{synchronized(this){foreground--;}};
        }
        static final class Reader implements AutoCloseable {
            final Workload owner;final Runnable cancel;volatile boolean cancelled;
            Reader(Workload owner,Runnable cancel){this.owner=owner;this.cancel=cancel;}
            void check()throws Yield{if(cancelled)throw new Yield();}
            public void close(){synchronized(owner){if(owner.reader==this)owner.reader=null;}}
        }
    }
}
