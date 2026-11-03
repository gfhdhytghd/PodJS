package dev.podjs.runtime;

import java.security.KeyStore;

/** Android's store service is process-owned; failed initialization must not spin. */
final class ProcessKeyStore {
    private static KeyStore store;
    private static final FailureBackoff failure=new FailureBackoff();
    static synchronized KeyStore get()throws Exception {
        if(store!=null)return store;failure.check();
        try {KeyStore next=KeyStore.getInstance("AndroidKeyStore");SecureOperationStats.PROCESS.op("load");next.load(null);store=next;failure.success();return next;}
        catch(Exception error){failure.failed(error);throw error;}
    }
}
