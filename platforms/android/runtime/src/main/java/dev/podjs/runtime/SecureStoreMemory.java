package dev.podjs.runtime;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/** Process-lifetime secure-store snapshots, shared by foreground and workers.
 * The host is the sole writer during this process. External file replacement
 * is observed on the next process start, not by polling secrets on each read.
 * Published objects are immutable; writers clone before changing anything. */
final class SecureStoreMemory {
    private static final ConcurrentHashMap<File,State> stores=new ConcurrentHashMap<>();
    static State at(File canonicalFile){return stores.computeIfAbsent(canonicalFile,f->new State());}
    static final class Snapshot {
        final JSONObject values;
        final String json;
        Snapshot(JSONObject value)throws Exception {json=value.toString();values=new JSONObject(json);}
    }
    static final class State {
        volatile Snapshot snapshot;
        SecretKey key;
        final java.util.Map<String,SecretKey> aliasKeys=new java.util.HashMap<>();
        final java.util.Map<String,java.security.KeyPair> identityKeys=new java.util.HashMap<>();
        Exception loadFailure;
        long retryAfterNanos;
    }
}
