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
        Snapshot(JSONObject value)throws Exception {this(value,value.toString());}
        Snapshot(JSONObject value,String encoded)throws Exception {json=encoded;values=copy(value);}
    }
    /** Secure API values are strings. Copy those by reference; retain isolation
     * for legacy nested JSON without reparsing every unchanged secret. */
    static JSONObject copy(JSONObject value)throws Exception {
        JSONObject result=new JSONObject();
        for(java.util.Iterator<String> keys=value.keys();keys.hasNext();){
            String key=keys.next();Object item=value.get(key);
            if(item instanceof JSONObject)item=new JSONObject(item.toString());
            else if(item instanceof org.json.JSONArray)item=new org.json.JSONArray(item.toString());
            result.put(key,item);
        }
        return result;
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
