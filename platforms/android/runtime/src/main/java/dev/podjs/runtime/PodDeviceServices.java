package dev.podjs.runtime;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small, synchronous native services used by the PodJS bridge.
 *
 * <p>Methods and arguments are deliberately JSON shaped:
 * secure.get/set/delete use {@code key} and (for set) {@code value}; get returns
 * {@code {exists,value}}. crypto.random uses {@code bytes} and returns
 * {@code {bytesBase64}}. crypto.hash uses {@code dataBase64} and optional
 * {@code algorithm} (SHA-256 by default), returning {@code {digestBase64}}.
 * crypto.encrypt/decrypt use {@code inputBase64}; encrypt returns
 * {@code {nonceBase64,ciphertextBase64}} and decrypt takes those two fields.
 * file.read uses {@code path}, optional {@code offset} and {@code maxBytes}, and
 * returns {@code {dataBase64,offset,size,eof}}. file.write uses {@code path},
 * {@code dataBase64}, and optional {@code atomicReplace}; stat uses {@code path};
 * delete uses {@code path}, returning {@code {deleted}}. File chunks are capped
 * at 256 KiB and paths are relative to the app-private podjs/files directory.</p>
 */
public final class PodDeviceServices implements AutoCloseable {
    public static final int MAX_CHUNK_BYTES = 256 * 1024;
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String SECURE_ALIAS = "podjs.secure.v1";
    private static final String CRYPTO_ALIAS = "podjs.crypto.v1";
    private static final String SECURE_FILE = "secure.store";
    private static final Object SECURE_LOCK = new Object();
    private static final String ECDH_PRIVATE_PREFIX = "__podjs_native_ecdh_v1.";
    private static final byte[] AAD = "PodJS-device-services-v1".getBytes(StandardCharsets.UTF_8);

    private final File root;
    private final File secureFile;
    @FunctionalInterface interface SecureKeySource { SecretKey get() throws Exception; }
    private final SecureKeySource secureKey;
    private final PodSecureLease secureLeases;
    private final SecureStoreMemory.State secureMemory;
    private final SecureRandom random = new SecureRandom();
    private boolean closed;
    private final java.util.Map<String,SealedGzipSession> sealedGzip = new java.util.HashMap<>();
    private DeviceServiceTrace secureTrace=new DeviceServiceTrace(false,line->{});
    synchronized void enableServiceTrace(boolean enabled){secureTrace=new DeviceServiceTrace(enabled,line->android.util.Log.i("PodJSService",line));}
    boolean hasWarmSecureStore(){return secureMemory.snapshot!=null;}
    private long nextTextReader;
    private final java.util.Map<String,java.io.BufferedReader> textReaders = new java.util.HashMap<>();
    private final java.util.Set<File> temporaryFiles = new java.util.HashSet<>();

    public PodDeviceServices(Context context) {
        this(serviceFiles(context), null);
        SecureOperationStats.start();
    }
    private static File serviceFiles(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        return PodTakeover.filesRoot(context);
    }
    // Synthetic-key JVM tests use the same operations, locks and atomic writer.
    // Production always uses the public Context constructor and AndroidKeyStore.
    PodDeviceServices(File files, SecureKeySource key) {
        if (!files.exists() && !files.mkdirs()) throw new IllegalStateException("service_root_unavailable");
        try { root = files.getCanonicalFile(); secureFile = new File(root.getParentFile(), SECURE_FILE); }
        catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
        secureLeases = new PodSecureLease(secureFile.getParentFile());
        secureMemory = SecureStoreMemory.at(secureFile);
        secureKey = key == null ? () -> getKey(SECURE_ALIAS) : key;
    }

    public synchronized JSONObject execute(String method, JSONObject args) throws Exception {
        SecureOperationStats.Span span=SecureOperationStats.PROCESS.begin(method,args==null?0:args.optInt("secureSite",0));boolean success=false;
        try {JSONObject result=dispatch(method,args);success=true;return result;}
        finally {SecureOperationStats.PROCESS.end(span,success);}
    }
    private JSONObject dispatch(String method, JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException("service_closed");
        if (method == null || args == null) throw new IllegalArgumentException("method_and_args_required");
        switch (method) {
            case "secure.acquire": return new JSONObject().put("acquired",secureLeases.acquire(required(args,"key")));
            case "secure.release": secureLeases.release(required(args,"key")); return new JSONObject().put("ok",true);
            case "secure.keys": case "secure.get": case "secure.set": case "secure.delete": case "secure.update": case "secure.compareAndSet": return secureOperation(method,args);
            case "crypto.verifyP256": return new JSONObject().put("valid",PodCloudCrypto.verifyP256(decode(args,"publicKeySpki"),decode(args,"dataBase64"),decode(args,"signatureBase64")));
            case "crypto.ecdhPublicKey": return ecdhPublicKey(args);
            case "crypto.ecdhPossessionProof": return ecdhPossessionProof(args);
            case "crypto.ecdhUnwrap": return ecdhUnwrap(args);
            case "crypto.hkdfSha256": return new JSONObject().put("keyBase64", enc(PodCloudCrypto.hkdf(decode(args,"inputBase64"),decode(args,"saltBase64"),decode(args,"infoBase64"))));
            case "crypto.largeAesGcmEncrypt": return largeAes(args,true);
            case "crypto.largeAesGcmDecrypt": return largeAes(args,false);
            case "crypto.gunzipSealedParts": return gunzipSealedParts(args);
            case "crypto.closeSealedGzip": closeSealedGzip(required(args,"outputPrefix")); return new JSONObject();
            case "crypto.openSealedParts": return openSealedParts(args);
            case "crypto.gzip": return cryptoOutput(PodCloudCrypto.gzip(cryptoInput(args)));
            case "crypto.gunzip": return cryptoOutput(PodCloudCrypto.gunzip(cryptoInput(args),args.getInt("maxBytes")));
            case "crypto.random": return cryptoRandom(args);
            case "crypto.hash": return cryptoHash(args);
            case "crypto.hmacSha256": return cryptoHmacSha256(args);
            case "crypto.aesGcmEncrypt": return cryptoAesGcmEncrypt(args);
            case "crypto.aesGcmDecrypt": return cryptoAesGcmDecrypt(args);
            case "crypto.encrypt": return cryptoEncrypt(args);
            case "crypto.decrypt": return cryptoDecrypt(args);
            case "crypto.rsaOaepSha256": return cryptoRsaOaepSha256(args);
            case "file.read": return fileRead(args);
            case "log.append": return new JSONObject().put("bytes",PodAppendLog.append(safeFile(required(args,"path")),required(args,"text"),args.optInt("maxBytes",5*1024*1024)));
            case "file.write": return fileWrite(args);
            case "file.publishBytes": return filePublishBytes(args);
            case "file.hash": return fileHash(args);
            case "uri.parse": return uriParse(args);
            case "file.htmlText": return fileHtmlText(args);
            case "file.gzip": return fileTranscode(args, false);
            case "file.gzipBytes": return fileGzipBytes(args);
            case "file.gunzip": return fileTranscode(args, true);
            case "file.openText": return fileOpenText(args);
            case "file.readTextChunk": return fileReadTextChunk(args);
            case "file.closeText": return fileCloseText(args);
            case "file.createTemp": return fileCreateTemp(args);
            case "file.commit": return fileCommit(args);
            case "file.compose": return fileCompose(args);
            case "file.list": return fileList(args);
            case "file.touch": return fileTouch(args);
            case "file.stat": return fileStat(args);
            case "file.delete": return fileDelete(args);
            default: throw new IllegalArgumentException("unsupported_method:" + method);
        }
    }

    @Override public synchronized void close() {
        for(SealedGzipSession stream:sealedGzip.values())try{stream.close();}catch(IOException ignored){}
        sealedGzip.clear();
        // Secrets belong to the process, not to a foreground view/worker window.
        closed = true;
        secureLeases.close();
        for (File file : temporaryFiles) file.delete();
        temporaryFiles.clear();
        for (java.io.Reader reader : textReaders.values()) try { reader.close(); } catch (IOException ignored) { }
        textReaders.clear();
    }
    synchronized void discardTextReader(JSONObject value) {
        java.io.Reader reader = textReaders.remove(value.optString("id"));
        if (reader != null) try { reader.close(); } catch (IOException ignored) { }
    }
    synchronized void discardTemporary(JSONObject value) {
        try {
            File file = safeFile(value.getString("path"));
            if (temporaryFiles.remove(file)) file.delete();
        } catch (Exception ignored) { }
    }

    // Foreground/background share committed process memory. Persist mutations
    // atomically under the existing OS lease; publish memory only after success.
    private JSONObject secureOperation(String method,JSONObject args) throws Exception {
        if("secure.keys".equals(method)){
            org.json.JSONArray prefixes=args.getJSONArray("prefixes");if(prefixes.length()>8)throw new IllegalArgumentException("secure_batch_limit");
            String after=args.optString("after","");java.util.List<String> names=new java.util.ArrayList<>();
            JSONObject values; synchronized(SECURE_LOCK){values=readSecureValues(args.optInt("requestId",-1));}
            for(java.util.Iterator<String> keys=values.keys();keys.hasNext();){String key=keys.next();if(key.startsWith(ECDH_PRIVATE_PREFIX)||key.compareTo(after)<=0)continue;for(int i=0;i<prefixes.length();i++)if(key.startsWith(prefixes.getString(i))){names.add(key);break;}}
            java.util.Collections.sort(names);org.json.JSONArray page=new org.json.JSONArray();for(int i=0;i<Math.min(128,names.size());i++)page.put(names.get(i));
            return new JSONObject().put("keys",page).put("hasMore",names.size()>128);
        }
        // Internal software private keys never cross the JavaScript secure-storage API.
        if ("secure.update".equals(method)) {
            java.util.Iterator<String> names=args.getJSONObject("values").keys();
            while(names.hasNext()) checkPublicSecureKey(names.next());
        } else checkPublicSecureKey(required(args,"key"));
        // An immutable committed snapshot lets foreground reads bypass even an
        // in-flight background KeyStore write. It linearizes before that write.
        if("secure.get".equals(method)&&secureMemory.snapshot!=null)return secureGet(args);
        long total=secureTrace.begin(),waiting=total;int id=args.optInt("requestId",-1);
        try{synchronized(SECURE_LOCK){
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(new File(secureFile.getParentFile(),"secure.store.lock"),"rw");
                java.nio.channels.FileLock lock=file.getChannel().lock()){
                secureTrace.end("secure.lock",waiting,id);
                if("secure.get".equals(method))return secureGet(args);
                if("secure.set".equals(method))return secureSet(args);
                if("secure.compareAndSet".equals(method)){
                    String key=required(args,"key");
                    JSONObject next=conditionalSecure(readSecureValues(id),new JSONObject().put(key,args.get("expected")),new JSONObject().put(key,args.get("value")),null);
                    if(next==null)return new JSONObject().put("updated",false);
                    writeSecure(next);return new JSONObject().put("updated",true);
                }
                if("secure.update".equals(method)){
                    writeSecure(mergeSecure(readSecureValues(id),args.getJSONObject("values"),args.optJSONArray("removePrefixes")));
                    return new JSONObject().put("ok",true);
                }
                return secureDelete(args);
            }
        }}finally{secureTrace.end("secure.total",total,id);}
    }
    private static void checkPublicSecureKey(String key) {
        if(key.startsWith(ECDH_PRIVATE_PREFIX)) throw new SecurityException("reserved_secure_key");
    }
    static JSONObject conditionalSecure(JSONObject current,JSONObject expected,JSONObject updates,org.json.JSONArray prefixes)throws Exception {
        if(expected!=null&&!secureMatches(current,expected))return null;
        return mergeSecure(current,updates,prefixes);
    }
    /** Compare and update share the encrypted store's process/OS lock. Null
     * means absent; no values or mismatch details cross this boundary. */
    static boolean secureMatches(JSONObject current,JSONObject expected)throws Exception {
        if(expected.length()>128)throw new IllegalArgumentException("secure_batch_limit");
        boolean matches=true;
        for(java.util.Iterator<String> names=expected.keys();names.hasNext();){
            String key=names.next();checkPublicSecureKey(key);
            if(key.isEmpty()||key.length()>1024)throw new IllegalArgumentException("secure_key_invalid");
            Object value=expected.get(key);
            if(value!=JSONObject.NULL&&!(value instanceof String))throw new IllegalArgumentException("secure_value_invalid");
            matches&=value==JSONObject.NULL?!current.has(key):value.equals(current.opt(key));
        }
        return matches;
    }
    /** Validate the whole batch on a copy before the single encrypted atomic publication. */
    static JSONObject mergeSecure(JSONObject current,JSONObject updates)throws Exception {
        if(updates.length()>128)throw new IllegalArgumentException("secure_batch_limit");
        return applySecureUpdates(SecureStoreMemory.copy(current),updates);
    }
    private static JSONObject applySecureUpdates(JSONObject next,JSONObject updates)throws Exception {
        if(updates.length()>128)throw new IllegalArgumentException("secure_batch_limit");
        for(java.util.Iterator<String> keys=updates.keys();keys.hasNext();){
            String key=keys.next();checkPublicSecureKey(key);Object value=updates.get(key);
            if(key.isEmpty()||key.length()>1024)throw new IllegalArgumentException("secure_key_invalid");
            if(value==JSONObject.NULL)next.remove(key);
            else if(value instanceof String)next.put(key,value);
            else throw new IllegalArgumentException("secure_value_invalid");
        }
        SecureStoreLimits.validate(next);
        return next;
    }
    /** Prefix removal and explicit values publish together under the secure-store lock. */
    static JSONObject mergeSecure(JSONObject current,JSONObject updates,org.json.JSONArray prefixes)throws Exception {
        JSONObject next=SecureStoreMemory.copy(current);
        if(prefixes!=null){
            if(prefixes.length()>16)throw new IllegalArgumentException("secure_batch_limit");
            for(int i=0;i<prefixes.length();i++){
                Object value=prefixes.get(i);
                if(!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>1024)
                    throw new IllegalArgumentException("secure_prefix_required");
                String prefix=(String)value;
                // Broad guest cleanup must not erase a native-only identity either.
                if(prefix.startsWith(ECDH_PRIVATE_PREFIX)||ECDH_PRIVATE_PREFIX.startsWith(prefix))throw new SecurityException("reserved_secure_key");
                java.util.ArrayList<String> removed=new java.util.ArrayList<>();
                for(java.util.Iterator<String> names=next.keys();names.hasNext();){String name=names.next();if(name.startsWith(prefix))removed.add(name);}
                for(String name:removed)next.remove(name);
            }
        }
        return applySecureUpdates(next,updates);
    }
    private JSONObject secureGet(JSONObject a) throws Exception {
        JSONObject values = readSecureValues(a.optInt("requestId",-1)); String key = required(a, "key");
        JSONObject out = new JSONObject(); out.put("exists", values.has(key));
        if (values.has(key)) {
            String value=values.getString(key);
            if(a.has("maxChars")){
                // Snapshot copies retain the immutable string by reference. Writes to
                // another key must not invalidate every page of a large read.
                int version=System.identityHashCode(value);
                if(a.has("version")&&a.getInt("version")!=version)return out.put("changed",true);
                int maximum=a.getInt("maxChars"),offset=a.optInt("offset",0);if(maximum<1||maximum>32768||offset<0||offset>value.length())throw new IllegalArgumentException("secure_read_range_invalid");
                if(offset>0&&offset<value.length()&&Character.isLowSurrogate(value.charAt(offset))&&Character.isHighSurrogate(value.charAt(offset-1)))throw new IllegalArgumentException("secure_read_range_invalid");
                int end=Math.min(value.length(),offset+maximum);if(end<value.length()&&end>offset&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
                if(end==offset&&offset<value.length())throw new IllegalArgumentException("secure_read_range_invalid");
                out.put("value",value.substring(offset,end)).put("offset",offset).put("totalChars",value.length()).put("version",version);
            }else out.put("value",value);
        }
        return out;
    }
    private JSONObject secureSet(JSONObject a) throws Exception {
        String key = required(a, "key"); String value = required(a, "value");
        JSONObject current=readSecureValues(a.optInt("requestId",-1));
        if(value.equals(current.opt(key)))return new JSONObject().put("ok",true);
        JSONObject values = SecureStoreMemory.copy(current); values.put(key, value); writeSecure(values);
        return new JSONObject().put("ok", true);
    }
    private JSONObject secureDelete(JSONObject a) throws Exception {
        JSONObject values = readSecure(a.optInt("requestId",-1)); boolean removed = values.remove(required(a, "key")) != null;
        if (removed) writeSecure(values);
        PodBrowserAuthBenchmark.cleared(a);
        return new JSONObject().put("deleted", removed);
    }
    private JSONObject readSecure(int id) throws Exception {
        // Mutations own a deep copy. A quota/fsync failure cannot alter a cached read.
        return SecureStoreMemory.copy(readSecureValues(id));
    }
    private SecretKey secureKey() throws Exception {
        if(secureMemory.key==null)secureMemory.key=secureKey.get();
        return secureMemory.key;
    }
    private JSONObject readSecureValues(int id) throws Exception {
        SecureStoreMemory.Snapshot cached=secureMemory.snapshot;
        if(cached!=null)return cached.values;
        secureMemory.readFailure.check();
        try{return loadSecureValues(id);}
        catch(Exception error){secureMemory.readFailure.failed(error);throw error;}
    }
    private JSONObject loadSecureValues(int id) throws Exception {
        try {
            if (!Files.readAttributes(secureFile.toPath(),java.nio.file.attribute.BasicFileAttributes.class).isRegularFile())
                throw SecureStoreLimits.readFailed();
        } catch (java.nio.file.NoSuchFileException absent) {
            secureMemory.snapshot=new SecureStoreMemory.Snapshot(new JSONObject());
            return secureMemory.snapshot.values;
        }
        catch (IOException failure) { throw SecureStoreLimits.readFailed(); }
        // One cold load per process/store. No timestamp checks or ciphertext IO
        // on the hot read path. Startup decode still authenticates the whole map.
        byte[] bytes = readSecureBytes(secureFile);
        long began=secureTrace.begin();SecretKey key;
        try{key=secureKey();}finally{secureTrace.end("secure.key",began,id);}
        SecureStoreCipher.Opened opened=SecureStoreCipher.decode(key,bytes);
        try{secureMemory.snapshot=new SecureStoreMemory.Snapshot(new JSONObject(new String(opened.plain,StandardCharsets.UTF_8)));secureMemory.cipherMaterial=opened.material;}
        finally{Arrays.fill(opened.plain,(byte)0);}
        secureMemory.readFailure.success();
        return secureMemory.snapshot.values;
    }
    /** Shared production decoder for private candidate verification; does not open/create aliases. */
    static JSONObject readSecureFile(File file, SecretKey key) throws Exception {
        return readSecureFile(file,key,new DeviceServiceTrace(false,line->{}),-1);
    }
    static JSONObject readSecureFile(File file,SecretKey key,DeviceServiceTrace trace,int id) throws Exception {
        long began=trace.begin();byte[] bytes;
        try{bytes=readSecureBytes(file);}finally{trace.end("secure.read",began,id);}
        return decodeSecureBytes(bytes,key,trace,id);
    }
    static JSONObject decodeSecureBytes(byte[] all, SecretKey key) throws Exception {
        return decodeSecureBytes(all,key,new DeviceServiceTrace(false,line->{}),-1);
    }
    static JSONObject decodeSecureBytes(byte[] all,SecretKey key,DeviceServiceTrace trace,int id) throws Exception {
        if (all.length > SecureStoreLimits.MAX_FILE_BYTES) throw SecureStoreLimits.tooLarge();
        if (all.length < 28) throw new IOException("secure_store_corrupt");
        long began=trace.begin();byte[] plain;
        try{plain=SecureStoreCipher.decode(key,all).plain;}finally{trace.end("secure.decrypt",began,id);}
        began=trace.begin();
        try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}
        finally{Arrays.fill(plain,(byte)0);trace.end("secure.json",began,id);}
    }
    private void writeSecure(JSONObject values) throws Exception {
        if(secureMemory.snapshot!=null&&SecureStoreMemory.same(secureMemory.snapshot.values,values))return;
        secureMemory.writeFailure.check();
        byte[] plain=SecureStoreLimits.encode(values);
        String json=new String(plain,StandardCharsets.UTF_8);
        if(secureMemory.snapshot!=null&&json.equals(secureMemory.snapshot.json)){Arrays.fill(plain,(byte)0);return;}
        SecureStoreMemory.Snapshot next=new SecureStoreMemory.Snapshot(values,json);
        try {
            secureMemory.writeFailure.check();
            try {
                SecureStoreCipher.Material material=secureMemory.cipherMaterial;
                if(material==null)material=SecureStoreCipher.create(secureKey());
                byte[] encoded=SecureStoreCipher.encode(material,plain);
                atomicWrite(secureFile,encoded);
                secureMemory.cipherMaterial=material;
                secureMemory.snapshot=next;
                secureMemory.writeFailure.success();
            } catch(Exception error){secureMemory.writeFailure.failed(error);throw error;}
        }
        finally { Arrays.fill(plain,(byte)0); }
    }
    private static byte[] readSecureBytes(File file) throws IOException {
        if (file.length() > SecureStoreLimits.MAX_FILE_BYTES) throw SecureStoreLimits.tooLarge();
        try { return readAll(file, SecureStoreLimits.MAX_FILE_BYTES); }
        catch (IOException failure) {
            if ("secure_store_too_large".equals(failure.getMessage())) throw SecureStoreLimits.tooLarge();
            throw SecureStoreLimits.readFailed();
        }
    }

    // Only the SPKI public key and authenticated plaintext cross the bridge. There is
    // deliberately no private-key export or raw ECDH-secret operation.
    private String ecdhAlias(JSONObject args) throws Exception {
        String id = required(args,"keyId");
        if(id.length()>1024) throw new IllegalArgumentException("key_id_too_long");
        return "podjs.ecdh.v1." + enc(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
    }
    private KeyStore ecdhStore() throws Exception {
        return ProcessKeyStore.get();
    }
    /** Caller holds both the process and OS secure-store locks, including generation. */
    private java.security.KeyPair ecdhPair(JSONObject args, boolean create) throws Exception {
        return ecdhPairAlias(ecdhAlias(args),create);
    }
    private java.security.KeyPair ecdhPairAlias(String alias,boolean create) throws Exception {
        java.security.KeyPair cached=secureMemory.identityKeys.get(alias);
        if(cached!=null)return cached;
        FailureBackoff failure=secureMemory.identityFailures.computeIfAbsent(alias+":"+create,k->new FailureBackoff());failure.check();
        try{java.security.KeyPair pair=loadEcdhPairAlias(alias,create);secureMemory.identityKeys.put(alias,pair);failure.success();return pair;}
        catch(Exception error){failure.failed(error);throw error;}
    }
    private java.security.KeyPair loadEcdhPairAlias(String alias,boolean create) throws Exception {
        String softwareKey=ECDH_PRIVATE_PREFIX+alias;
        JSONObject values=readSecure(-1);
        KeyStore store=ecdhStore();
        // Keep Android 11 keys after an OS upgrade; never rotate an established device identity.
        if(values.has(softwareKey)) {
            if(countedContains(store,alias)) throw new IllegalStateException("device_private_key_ambiguous");
            return PodSoftwareEcdh.decode(values.getString(softwareKey));
        }
        if(countedContains(store,alias)) {
            java.security.PrivateKey key=(java.security.PrivateKey)countedKey(store,alias);
            java.security.cert.Certificate certificate=countedCertificate(store,alias);
            if(key==null||certificate==null) throw new IllegalStateException("device_private_key_missing");
            return new java.security.KeyPair(certificate.getPublicKey(),key);
        }
        // A Keystore-only sentinel survives accidental deletion of the encrypted file.
        // Missing/corrupt identities must never be silently regenerated under an old keyId.
        if(countedContains(store,alias+".software")) throw new IllegalStateException("device_private_key_missing");
        if(!create) throw new IllegalStateException("device_private_key_missing");
        if(android.os.Build.VERSION.SDK_INT>=31) {
            java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC",KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build());
            SecureOperationStats.PROCESS.op("generate");return generator.generateKeyPair();
        }
        java.security.KeyPair pair=PodSoftwareEcdh.generate();
        values.put(softwareKey,PodSoftwareEcdh.encode(pair));
        // A quota rejection must not create a sentinel for an unpublished key.
        SecureStoreLimits.validate(values);
        getKey(alias+".software");
        // AES-GCM using the existing Android Keystore AES key; atomic encrypted publication only.
        writeSecure(values);
        return pair;
    }
    private java.security.KeyPair lockedEcdhPair(JSONObject args,boolean create) throws Exception {
        synchronized(SECURE_LOCK) {
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(new File(secureFile.getParentFile(),"secure.store.lock"),"rw");
                java.nio.channels.FileLock lock=file.getChannel().lock()) {
                return ecdhPair(args,create);
            }
        }
    }
    private JSONObject ecdhPublicKey(JSONObject args) throws Exception {
        return new JSONObject().put("publicKeySpki",enc(lockedEcdhPair(args,true).getPublic().getEncoded()));
    }
    private byte[] digestHex(JSONObject args,String field) throws Exception {
        String hex=required(args,field);if(!hex.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid_key_digest");
        byte[] bytes=new byte[32];for(int i=0;i<32;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return bytes;
    }
    private JSONObject ecdhPossessionProof(JSONObject args) throws Exception {
        String alias="podjs.ecdh.v1."+enc(digestHex(args,"keyIdSha256"));
        final java.security.KeyPair pair;
        synchronized(SECURE_LOCK){
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(new File(secureFile.getParentFile(),"secure.store.lock"),"rw");java.nio.channels.FileLock lock=file.getChannel().lock()){
                // Never create an identity to answer a possession challenge.
                pair=ecdhPairAlias(alias,false);
            }
        }
        byte[] proof=ProcessEcdh.proof(pair,digestHex(args,"recipientSpkiSha256"),decode(args,"publicKeySpki"),decode(args,"saltBase64"),decode(args,"infoBase64"),decode(args,"nonceBase64"),decode(args,"ciphertextBase64"),decode(args,"aadBase64"),decode(args,"dataBase64"));
        try{return new JSONObject().put("proofBase64",enc(proof));}finally{Arrays.fill(proof,(byte)0);}
    }
    private JSONObject ecdhUnwrap(JSONObject args) throws Exception {
        java.security.PrivateKey privateKey=lockedEcdhPair(args,false).getPrivate();
        byte[] value=ProcessEcdh.unwrap(privateKey,decode(args,"publicKeySpki"),decode(args,"saltBase64"),decode(args,"infoBase64"),decode(args,"nonceBase64"),decode(args,"ciphertextBase64"),decode(args,"aadBase64"));
        try { if(value.length!=32) throw new IllegalArgumentException("invalid_account_key"); return new JSONObject().put("dataBase64",enc(value)); }
        finally { Arrays.fill(value,(byte)0); }
    }
    // 4 MiB cloud chunks must use files: the guest event lane admits only 1 MiB
    // JSON events. Return a small owned file descriptor, never multi-MiB Base64.
    private byte[] cryptoInput(JSONObject args) throws Exception {
        File input=safeFile(required(args,"inputPath"));
        if(!input.isFile() || input.length()>PodCloudCrypto.MAX_BYTES) throw new IllegalArgumentException("invalid_crypto_file");
        return readAll(input,PodCloudCrypto.MAX_BYTES);
    }
    private JSONObject cryptoOutput(byte[] bytes) throws Exception {
        JSONObject temporary=fileCreateTemp(new JSONObject().put("directory","cloud-crypto"));
        try {
            File output=safeFile(temporary.getString("path")); atomicWrite(output,bytes);
            return hashFile(output).put("path",temporary.getString("path"));
        }catch(Exception error){discardTemporary(temporary);throw error;}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    private JSONObject largeAes(JSONObject args,boolean encrypt) throws Exception {
        byte[] nonce;
        if(encrypt) { nonce=new byte[12]; random.nextBytes(nonce); }
        else nonce=decode(args,"nonceBase64");
        byte[] output=PodCloudCrypto.aes(encrypt,decode(args,"keyBase64"),nonce,cryptoInput(args),decode(args,"aadBase64"));
        JSONObject result=cryptoOutput(output); if(encrypt) result.put("nonceBase64",enc(nonce)); return result;
    }
    /** A bounded native materialization of immutable AEAD parts. Plaintext is
     * temporary and never published until every part authenticates. No bridge
     * round trip, key lookup or plaintext input transfer per encrypted part. */
    private JSONObject openSealedParts(JSONObject args) throws Exception {
        org.json.JSONArray parts=args.getJSONArray("parts");int length=args.getInt("length");
        if(parts.length()<1||parts.length()>2048||length<0||length>64*1024*1024)throw new IllegalArgumentException("invalid_sealed_parts");
        byte[] key=decode(args,"keyBase64");if(key.length!=32)throw new IllegalArgumentException("invalid_aes_key");
        JSONObject temporary=fileCreateTemp(new JSONObject().put("directory","cloud-crypto"));
        try{
            File target=safeFile(temporary.getString("path"));int total=0;
            try(FileOutputStream output=new FileOutputStream(target)){
                for(int i=0;i<parts.length();i++){
                    if(Thread.currentThread().isInterrupted()||args.has("deadlineMs")&&System.currentTimeMillis()>=args.getLong("deadlineMs"))throw new IOException("cloud_sync_deadline_exceeded");
                    JSONObject part=parts.getJSONObject(i);int size=part.getInt("bytes");if(size<0||size>256*1024||total>length-size)throw new IllegalArgumentException("invalid_sealed_parts");
                    byte[] plain;
                    try{File source=safeFile(required(part,"path"));if(!source.isFile()||source.length()!=size+28)throw new IOException("invalid_part");
                        plain=LocalPlaintextPart.read(source,decode(part,"aadBase64"),size,key,args.optBoolean("plaintext",false));
                    }catch(Exception invalid){throw new IOException("sealed_part_invalid_"+i);}
                    try{output.write(plain);total+=plain.length;}finally{Arrays.fill(plain,(byte)0);}
                }
                if(total!=length)throw new IOException("invalid_sealed_parts");
            }
            return hashFile(target).put("path",temporary.getString("path"));
        }catch(Exception error){discardTemporary(temporary);throw error;}finally{Arrays.fill(key,(byte)0);}
    }
    private void closeSealedGzip(String id) throws IOException {
        SealedGzipSession stream=sealedGzip.remove(id);if(stream!=null)stream.close();
    }
    private JSONObject gunzipSealedParts(JSONObject args) throws Exception {
        String prefix=required(args,"outputPrefix"),aadPrefix=required(args,"aadPrefix");
        boolean allowShortOutput=args.optBoolean("allowShortOutput",false);
        int maximum=args.getInt("maxOutputBytes");long offset=exactFileSize(args,"offset"),length=exactFileSize(args,"length");
        if(maximum<0||maximum>256*1024*1024||offset>maximum||offset!=maximum&&offset%MAX_CHUNK_BYTES!=0||length>256L*1024*1024)throw new IllegalArgumentException("invalid_sealed_gzip");
        org.json.JSONArray parts=args.getJSONArray("parts");if(parts.length()<1||parts.length()>2048)throw new IllegalArgumentException("invalid_sealed_gzip");
        byte[] key=decode(args,"keyBase64");if(key.length!=32)throw new IllegalArgumentException("invalid_aes_key");
        long deadline=args.optLong("deadlineMs",System.currentTimeMillis()+120000);
        String binding=enc(MessageDigest.getInstance("SHA-256").digest((parts.toString()+":"+enc(key)+":"+aadPrefix+":"+maximum+":"+length+":"+allowShortOutput+":"+args.optBoolean("plaintext",false)).getBytes(StandardCharsets.UTF_8)));
        try{
            SealedGzipSession stream=sealedGzip.get(prefix);
            if(stream==null||!stream.binding.equals(binding)){
                closeSealedGzip(prefix);for(String id:new java.util.ArrayList<>(sealedGzip.keySet()))closeSealedGzip(id);
                java.util.List<SealedGzipSession.Part> inputs=new java.util.ArrayList<>();long total=0;
                File first=safeFile(prefix+"0.bin");
                for(int i=0;i<parts.length();i++){
                    JSONObject part=parts.getJSONObject(i);int n=part.getInt("bytes");if(n<=0||n>MAX_CHUNK_BYTES)throw new IllegalArgumentException("invalid_sealed_gzip");
                    File file=safeFile(required(part,"path"));
                    if(file.getParentFile().equals(first.getParentFile())&&file.getName().startsWith(first.getName().substring(0,first.getName().length()-5)))throw new IllegalArgumentException("overlapping_sealed_gzip");
                    inputs.add(new SealedGzipSession.Part(file,decode(part,"aadBase64"),n));total+=n;
                }
                if(total!=length)throw new IllegalArgumentException("invalid_sealed_gzip");
                stream=new SealedGzipSession(binding,inputs,key,aadPrefix,maximum,deadline,allowShortOutput,args.optBoolean("plaintext",false));sealedGzip.put(prefix,stream);
            }
            SealedGzipSession.Step step=stream.next(offset,deadline,(at,bytes)->{
                File target=safeFile(prefix+at+".bin");File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("parent_unavailable");
                // Every piece has its own fsynced temporary and atomic publish.
                JSONObject temp=fileCreateTemp(new JSONObject().put("directory",root.toPath().relativize(parent.toPath()).toString()));
                try{File staged=safeFile(temp.getString("path"));atomicWrite(staged,bytes);JSONObject hash=hashFile(staged);
                    fileCommit(new JSONObject().put("path",temp.getString("path")).put("target",prefix+at+".bin").put("expectedSize",bytes.length).put("expectedSha256",hash.getString("sha256")));
                }finally{discardTemporary(temp);}
            });
            JSONObject result=new JSONObject().put("offset",step.offset).put("bytes",step.bytes).put("done",step.done);
            if(step.done){result.put("sha256",step.sha256);closeSealedGzip(prefix);}return result;
        }catch(Exception error){try{closeSealedGzip(prefix);}catch(IOException ignored){}throw error;}finally{Arrays.fill(key,(byte)0);}
    }
    private JSONObject cryptoRandom(JSONObject a) throws Exception {
        int n = a.optInt("bytes", 32); if (n < 0 || n > MAX_CHUNK_BYTES) throw new IllegalArgumentException("invalid_bytes");
        byte[] out = new byte[n]; random.nextBytes(out); return new JSONObject().put("bytesBase64", enc(out));
    }
    private JSONObject cryptoHash(JSONObject a) throws Exception {
        byte[] data = decodeInput(a); String algorithm = a.optString("algorithm", "SHA-256");
        if (!"SHA-256".equalsIgnoreCase(algorithm)) throw new IllegalArgumentException("unsupported_hash");
        return new JSONObject().put("algorithm", "SHA-256").put("digestBase64", enc(MessageDigest.getInstance("SHA-256").digest(data)));
    }
    private static SecretKeySpec rawAesKey(JSONObject a) {
        byte[] key = decode(a, "keyBase64");
        if (key.length != 32) throw new IllegalArgumentException("invalid_aes_key");
        return new SecretKeySpec(key, "AES");
    }
    private JSONObject cryptoHmacSha256(JSONObject a) throws Exception {
        byte[] key = decode(a, "keyBase64");
        if (key.length != 32) throw new IllegalArgumentException("invalid_hmac_key");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return new JSONObject().put("digestBase64", enc(mac.doFinal(decode(a, "dataBase64"))));
    }
    private JSONObject cryptoAesGcmEncrypt(JSONObject a) throws Exception {
        SecretKeySpec key = rawAesKey(a);
        byte[] aad = decode(a, "aadBase64"), input = decode(a, "dataBase64");
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return new JSONObject().put("nonceBase64", enc(nonce)).put("ciphertextBase64", enc(cipher.doFinal(input)));
    }
    private JSONObject cryptoAesGcmDecrypt(JSONObject a) throws Exception {
        SecretKeySpec key = rawAesKey(a);
        byte[] nonce = decode(a, "nonceBase64");
        if (nonce.length != 12) throw new IllegalArgumentException("invalid_nonce");
        byte[] aad = decode(a, "aadBase64"), ciphertext = decode(a, "ciphertextBase64");
        if (ciphertext.length < 16) throw new IllegalArgumentException("invalid_ciphertext");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return new JSONObject().put("dataBase64", enc(cipher.doFinal(ciphertext)));
    }
    private JSONObject cryptoRsaOaepSha256(JSONObject args) throws Exception {
        String pem=args.optString("publicKeyPem", "");
        if(pem.length()>8192 || !pem.startsWith("-----BEGIN PUBLIC KEY-----"))throw new IllegalArgumentException("invalid_public_key");
        String body=pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        java.security.interfaces.RSAPublicKey key=(java.security.interfaces.RSAPublicKey)java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.decode(body,Base64.DEFAULT)));
        int bits=key.getModulus().bitLength();if(bits<1024||bits>4096)throw new IllegalArgumentException("invalid_rsa_key_size");
        byte[] input=decode(args,"inputBase64");if(input.length>(bits+7)/8-66)throw new IllegalArgumentException("rsa_message_too_long");
        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key,new javax.crypto.spec.OAEPParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA256,javax.crypto.spec.PSource.PSpecified.DEFAULT));
        return new JSONObject().put("ciphertextBase64",enc(cipher.doFinal(input)));
    }
    private JSONObject cryptoEncrypt(JSONObject a) throws Exception {
        byte[] encrypted = legacyCrypto(true,decodeInput(a));
        return new JSONObject().put("nonceBase64", enc(Arrays.copyOf(encrypted, 12))).put("ciphertextBase64", enc(Arrays.copyOfRange(encrypted, 12, encrypted.length)));
    }
    private JSONObject cryptoDecrypt(JSONObject a) throws Exception {
        byte[] nonce = decode(a, "nonceBase64"); if (nonce.length != 12) throw new IllegalArgumentException("invalid_nonce");
        byte[] ciphertext = decode(a, "ciphertextBase64"); byte[] all = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, all, 0, nonce.length); System.arraycopy(ciphertext, 0, all, nonce.length, ciphertext.length);
        return new JSONObject().put("dataBase64", enc(legacyCrypto(false,all)));
    }

    // Explicit legacy crypto calls retain their ciphertext contract, but a failing
    // hardware operation cannot spin while a caller retries the same alias.
    private byte[] legacyCrypto(boolean encrypt,byte[] input)throws Exception {
        synchronized(SECURE_LOCK){
            FailureBackoff failure=secureMemory.aliasFailures.computeIfAbsent("operation:"+encrypt+":"+CRYPTO_ALIAS,k->new FailureBackoff());failure.check();
            try{SecretKey key=getKey(CRYPTO_ALIAS);byte[] value=encrypt?encryptWithKey(key,input):decryptWithKey(key,input);failure.success();return value;}
            catch(Exception error){failure.failed(error);throw error;}
        }
    }

    private JSONObject fileRead(JSONObject a) throws Exception {
        File f = safeFile(required(a, "path")); if (!f.isFile()) throw new IOException("file_not_found");
        long offset = a.optLong("offset", 0); if (offset < 0 || offset > f.length()) throw new IllegalArgumentException("invalid_offset");
        int max = bounded(a.optInt("maxBytes", MAX_CHUNK_BYTES)); byte[] out = new byte[max]; int n;
        try (FileInputStream in = new FileInputStream(f)) { if (offset > 0 && in.skip(offset) != offset) throw new IOException("seek_failed"); n = in.read(out); }
        if (n < 0) n = 0;
        JSONObject result = new JSONObject().put("offset", offset).put("size", f.length()).put("bytesRead", n).put("eof", offset + n >= f.length());
        String encoding = a.optString("encoding", "base64");
        if ("utf8".equals(encoding)) return result.put("text", new String(out, 0, n, StandardCharsets.UTF_8));
        if (!"base64".equals(encoding)) throw new IllegalArgumentException("invalid_encoding");
        return result.put("dataBase64", enc(Arrays.copyOf(out, n)));
    }
    private JSONObject fileWrite(JSONObject a) throws Exception {
        File f = safeFile(required(a, "path")); byte[] data = decode(a, "dataBase64"); if (data.length > MAX_CHUNK_BYTES) throw new IllegalArgumentException("chunk_too_large");
        File parent = f.getParentFile(); if (!parent.exists() && !parent.mkdirs()) throw new IOException("parent_unavailable");
        if (a.optBoolean("append", false)) {
            if (a.has("atomicReplace") && a.getBoolean("atomicReplace")) throw new IllegalArgumentException("append_cannot_replace");
            long offset = exactFileSize(a, "expectedOffset");
            if (!f.isFile() || f.length() != offset) throw new IOException("append_offset_mismatch");
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(data); out.getFD().sync(); }
        } else if (a.optBoolean("atomicReplace", true)) atomicWrite(f, data);
        else try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); out.getFD().sync(); }
        return new JSONObject().put("size", f.length());
    }
    private JSONObject fileOpenText(JSONObject a) throws Exception {
        if (textReaders.size() >= 8) throw new IllegalStateException("too_many_text_readers");
        File file = safeFile(required(a,"path"));
        if (!file.isFile()) throw new IOException("file_not_found");
        String id = "text-" + (++nextTextReader);
        java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder();
        java.nio.charset.CodingErrorAction errors=a.optBoolean("strictUtf8",false)?java.nio.charset.CodingErrorAction.REPORT:java.nio.charset.CodingErrorAction.REPLACE;
        decoder.onMalformedInput(errors).onUnmappableCharacter(errors);
        textReaders.put(id,new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(file),decoder)));
        return new JSONObject().put("id",id);
    }
    private JSONObject fileReadTextChunk(JSONObject a) throws Exception {
        String id = required(a,"id");
        java.io.BufferedReader reader = textReaders.get(id);
        if (reader == null) throw new IllegalArgumentException("unknown_text_reader");
        long requested = a.has("maxChars") ? exactFileSize(a,"maxChars") : 16384;
        if (requested < 1 || requested > 32768) throw new IllegalArgumentException("invalid_max_chars");
        int max=(int)requested;
        char[] buffer = new char[max+1];int count = reader.read(buffer,0,max);
        if (count < 0) { reader.close();textReaders.remove(id);return new JSONObject().put("text","").put("eof",true); }
        // InputStreamReader produces valid pairs; keep a pair in one bridge reply.
        if (count > 0 && Character.isHighSurrogate(buffer[count-1])) {
            int next = reader.read();if (next >= 0) buffer[count++]=(char)next;
        }
        return new JSONObject().put("text",new String(buffer,0,count)).put("eof",false);
    }
    private JSONObject fileCloseText(JSONObject a) throws Exception {
        java.io.Reader reader = textReaders.remove(required(a,"id"));
        if (reader != null) reader.close();
        return new JSONObject().put("closed",reader != null);
    }
    private JSONObject uriParse(JSONObject a) throws Exception {
        String syntax = a.optString("syntax", "java");
        if ("android".equals(syntax)) {
            android.net.Uri uri = android.net.Uri.parse(a.getString("value"));
            return new JSONObject().put("valid",true)
                .put("scheme",uri.getScheme() == null ? JSONObject.NULL : uri.getScheme())
                .put("host",uri.getHost() == null ? JSONObject.NULL : uri.getHost())
                .put("rawPath",uri.getEncodedPath() == null ? JSONObject.NULL : uri.getEncodedPath())
                .put("rawQuery",uri.getEncodedQuery() == null ? JSONObject.NULL : uri.getEncodedQuery());
        }
        if (!"java".equals(syntax)) throw new IllegalArgumentException("Unknown URI syntax");
        java.net.URI uri;
        try {uri = new java.net.URI(a.getString("value"));}
        catch (java.net.URISyntaxException error) {return new JSONObject().put("valid",false).put("scheme",JSONObject.NULL).put("host",JSONObject.NULL).put("rawPath",JSONObject.NULL).put("rawQuery",JSONObject.NULL);}
        return new JSONObject().put("valid",true).put("scheme", uri.getScheme() == null ? JSONObject.NULL : uri.getScheme())
            .put("host", uri.getHost() == null ? JSONObject.NULL : uri.getHost())
            .put("rawPath", uri.getRawPath() == null ? JSONObject.NULL : uri.getRawPath())
            .put("rawQuery", uri.getRawQuery() == null ? JSONObject.NULL : uri.getRawQuery());
    }
    private JSONObject fileHtmlText(JSONObject a) throws Exception {
        File source=safeFile(required(a,"path"));
        if (!source.isFile()) throw new IOException("file_not_found");
        long maximum=exactFileSize(a,"maxOutputBytes");
        if (maximum > 32L*1024*1024 || source.length() > 32L*1024*1024) throw new IllegalArgumentException("invalid_content_size");
        String text=org.jsoup.Jsoup.parse(source,"UTF-8").text().trim();
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IOException("content_too_large");
        JSONObject temporary=fileCreateTemp(new JSONObject().put("directory","html_text"));
        try {
            try (FileOutputStream output=new FileOutputStream(safeFile(temporary.getString("path")))) {output.write(bytes);}
            return hashFile(safeFile(temporary.getString("path"))).put("path",temporary.getString("path"));
        } catch (Exception error) {discardTemporary(temporary);throw error;}
    }
    private JSONObject fileTranscode(JSONObject a,boolean decompress) throws Exception {
        File source=safeFile(required(a,"path"));
        if (!source.isFile()) throw new IOException("file_not_found");
        long maximum=exactFileSize(a,"maxOutputBytes");
        if (maximum > 64L*1024*1024) throw new IllegalArgumentException("invalid_max_output_bytes");
        String directory=root.toPath().relativize(source.getParentFile().toPath()).toString();
        if (directory.isEmpty()) directory="transcode";
        JSONObject temporary=fileCreateTemp(new JSONObject().put("directory",directory));
        File target=safeFile(temporary.getString("path"));
        try {
            java.io.InputStream input=new FileInputStream(source);
            if (decompress) {
                try { input=new java.util.zip.GZIPInputStream(input); }
                catch (IOException error) {
                    input.close();
                    if (!a.optBoolean("allowPlain",false)) throw error;
                    input=new FileInputStream(source);
                }
            }
            final java.io.InputStream selected=input;
            try (java.io.InputStream in=selected;FileOutputStream raw=new FileOutputStream(target)) {
                java.io.OutputStream bounded=new java.io.FilterOutputStream(raw) {
                    long count;
                    @Override public void write(int value) throws IOException { check(1);out.write(value); }
                    @Override public void write(byte[] bytes,int offset,int length) throws IOException { check(length);out.write(bytes,offset,length); }
                    private void check(int length) throws IOException { if (length > maximum-count) throw new IOException("decompressed_content_too_large");count+=length; }
                };
                java.io.OutputStream output=decompress?bounded:new java.util.zip.GZIPOutputStream(bounded);
                try (java.io.OutputStream out=output) {
                    byte[] bytes=new byte[65536];int count;
                    while ((count=in.read(bytes)) != -1) out.write(bytes,0,count);
                }
            }
            return hashFile(target).put("path",temporary.getString("path"));
        } catch (Exception error) {discardTemporary(temporary);throw error;}
    }
    /** Small sync bodies avoid two temporary files and several bridge round trips. */
    private JSONObject fileGzipBytes(JSONObject a) throws Exception {
        byte[] input=decode(a,"dataBase64");
        if(input.length>64*1024)throw new IllegalArgumentException("gzip_input_too_large");
        ByteArrayOutputStream sink=new ByteArrayOutputStream();
        try(java.util.zip.GZIPOutputStream output=new java.util.zip.GZIPOutputStream(sink)){output.write(input);}
        byte[] zipped=sink.toByteArray();
        if(zipped.length>MAX_CHUNK_BYTES)throw new IOException("gzip_output_too_large");
        return new JSONObject().put("dataBase64",Base64.encodeToString(zipped,Base64.NO_WRAP));
    }
    private JSONObject fileCreateTemp(JSONObject a) throws Exception {
        if (temporaryFiles.size() >= 8) throw new IllegalStateException("too_many_temporary_files");
        File directory = safeFile(required(a, "directory"));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("parent_unavailable");
        File file = Files.createTempFile(directory.toPath(), ".pod-write-", ".part").toFile();
        temporaryFiles.add(file);
        return new JSONObject().put("path", root.toPath().relativize(file.toPath()).toString());
    }
    /** Hash files on the host with bounded memory; file contents never cross the bridge. */
    private JSONObject fileHash(JSONObject a) throws Exception {
        File source = safeFile(required(a, "path"));
        return hashFile(source);
    }
    private static JSONObject hashFile(File source) throws Exception {
        if (!source.isFile()) throw new IOException("file_not_found");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0; byte[] chunk = new byte[65536];
        try (FileInputStream input = new FileInputStream(source)) {
            int count; while ((count = input.read(chunk)) != -1) { digest.update(chunk, 0, count); size += count; }
        }
        return new JSONObject().put("size", size).put("sha256", hexDigest(digest.digest()));
    }
    /** Assemble immutable pieces without guest plaintext transfer. Verify every
     * piece while copying, then use the normal fsync + atomic publication path.
     * No incomplete output or partial checksum is exposed to the guest. */
    private JSONObject fileCompose(JSONObject a) throws Exception {
        org.json.JSONArray parts=a.getJSONArray("parts");long expected=exactFileSize(a,"expectedSize");
        if(parts.length()>2048||expected>256L*1024*1024)throw new IllegalArgumentException("invalid_file_parts");
        File target=safeFile(required(a,"target"));String expectedHash=required(a,"expectedSha256");
        if(!expectedHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid_expected_hash");
        String directory=root.toPath().relativize(target.getParentFile().toPath()).toString();
        JSONObject temp=fileCreateTemp(new JSONObject().put("directory",directory.isEmpty()?"compose":directory));
        try{
            long total=0;byte[] buffer=new byte[65536];
            try(FileOutputStream out=new FileOutputStream(safeFile(temp.getString("path")))){
                for(int i=0;i<parts.length();i++){
                    if(Thread.currentThread().isInterrupted()||a.has("deadlineMs")&&System.currentTimeMillis()>=a.getLong("deadlineMs"))throw new IOException("cloud_sync_deadline_exceeded");
                    JSONObject part=parts.getJSONObject(i);File source=safeFile(required(part,"path"));long size=exactFileSize(part,"size");
                    if(source.equals(target)||size<1||size>MAX_CHUNK_BYTES||size>expected-total||!source.isFile()||source.length()!=size)throw new IOException("invalid_file_part");
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");long copied=0;
                    try(FileInputStream in=new FileInputStream(source)){int n;while((n=in.read(buffer))!=-1){if(n>size-copied)throw new IOException("invalid_file_part");out.write(buffer,0,n);digest.update(buffer,0,n);copied+=n;}}
                    String hex=hexDigest(digest.digest());
                    if(copied!=size||!hex.toString().equals(required(part,"sha256")))throw new IOException("file_part_checksum_mismatch");total+=copied;
                }
            }finally{Arrays.fill(buffer,(byte)0);}
            if(total!=expected)throw new IOException("file_compose_size_mismatch");
            return fileCommit(new JSONObject().put("path",temp.getString("path")).put("target",a.getString("target")).put("expectedSize",expected).put("expectedSha256",expectedHash));
        }finally{discardTemporary(temp);}
    }
    /** One bounded transfer, verified before atomic publication. Safe to retry
     * after a lost reply: an existing target must match the requested digest. */
    private JSONObject filePublishBytes(JSONObject a)throws Exception {
        if(Thread.currentThread().isInterrupted()||a.has("deadlineMs")&&System.currentTimeMillis()>=a.getLong("deadlineMs"))throw new IOException("cloud_sync_deadline_exceeded");
        File target=safeFile(required(a,"target"));long size=exactFileSize(a,"expectedSize");
        String digest=required(a,"expectedSha256");
        if(size>MAX_CHUNK_BYTES||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid_file_publication");
        byte[] bytes=decode(a,"dataBase64");
        if(bytes.length!=size||!hexDigest(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest))throw new IOException("commit_checksum_mismatch");
        boolean reused=target.isFile()&&target.length()==size&&hashFile(target).getString("sha256").equals(digest);
        if(reused)return new JSONObject().put("path",a.getString("target")).put("size",size).put("sha256",digest).put("reused",true);
        String directory=root.toPath().relativize(target.getParentFile().toPath()).toString();
        JSONObject temp=fileCreateTemp(new JSONObject().put("directory",directory.isEmpty()?"publish":directory));
        try{try(FileOutputStream out=new FileOutputStream(safeFile(temp.getString("path")))){out.write(bytes);}
            return fileCommit(new JSONObject().put("path",temp.getString("path")).put("target",a.getString("target")).put("expectedSize",size).put("expectedSha256",digest));
        }finally{discardTemporary(temp);}
    }
    private static String hexDigest(byte[] bytes){char[] out=new char[bytes.length*2];String alphabet="0123456789abcdef";for(int i=0;i<bytes.length;i++){out[i*2]=alphabet.charAt((bytes[i]&255)>>>4);out[i*2+1]=alphabet.charAt(bytes[i]&15);}return new String(out);}
    /** Verify the complete staged file before atomically publishing its final path. */
    private JSONObject fileCommit(JSONObject a) throws Exception {
        File source = safeFile(required(a, "path")), target = safeFile(required(a, "target"));
        if (source.equals(target)) throw new IllegalArgumentException("same_commit_path");
        long expectedSize = exactFileSize(a, "expectedSize");
        String expectedHash = required(a, "expectedSha256");
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("invalid_expected_hash");
        JSONObject actual = hashFile(source);
        if (actual.getLong("size") != expectedSize || !actual.getString("sha256").equalsIgnoreCase(expectedHash)) throw new IOException("commit_checksum_mismatch");
        // This also flushes a staged file supplied by a caller other than file.write.
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(source, "rw")) { file.getFD().sync(); }
        boolean reused = false;
        if (target.isFile() && target.length() == expectedSize) {
            reused = hashFile(target).getString("sha256").equalsIgnoreCase(expectedHash);
        }
        if (reused) {
            if (!source.delete()) throw new IOException("staged_file_cleanup_failed");
        } else {
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("parent_unavailable");
            // Do not replace a valid old file using a non-atomic fallback.
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        temporaryFiles.remove(source);
        return actual.put("path", required(a, "target")).put("reused", reused);
    }
    private static long exactFileSize(JSONObject a, String field) {
        Object value = a.opt(field);
        if (!(value instanceof Number)) throw new IllegalArgumentException("invalid_" + field);
        double size = ((Number)value).doubleValue();
        if (!Double.isFinite(size) || size < 0 || size > 9007199254740991d || size != Math.rint(size)) throw new IllegalArgumentException("invalid_" + field);
        return (long)size;
    }
    private JSONObject fileList(JSONObject a) throws Exception {
        File directory = safeFile(required(a, "path"));
        org.json.JSONArray entries = new org.json.JSONArray();
        if (!directory.exists()) return new JSONObject().put("entries", entries);
        if (!directory.isDirectory()) throw new IOException("not_directory");
        java.util.ArrayList<File> files = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = Files.newDirectoryStream(directory.toPath())) {
            for (java.nio.file.Path path : stream) {
                if (Files.isSymbolicLink(path)) throw new SecurityException("directory_contains_symlink");
                if (files.size() >= 10000) throw new IOException("directory_too_large");
                File file = path.toFile().getCanonicalFile();
                if (!file.getParentFile().equals(directory)) throw new SecurityException("path_outside_directory");
                files.add(file);
            }
        }
        files.sort(java.util.Comparator.comparing(File::getName));
        for (File file : files) entries.put(new JSONObject()
            .put("path", root.toPath().relativize(file.toPath()).toString())
            .put("file", file.isFile()).put("directory", file.isDirectory())
            .put("size", file.isFile() ? file.length() : 0).put("modified", file.lastModified()).put("changeToken", fileChangeToken(file)));
        return new JSONObject().put("entries", entries);
    }
    /** A metadata witness, not a content checksum. Nanosecond ctime/inode also
     * observe same-length replacements with an unchanged millisecond mtime. */
    private static String fileChangeToken(File file) throws android.system.ErrnoException {
        android.system.StructStat s=android.system.Os.lstat(file.getPath());
        return s.st_dev+":"+s.st_ino+":"+s.st_mode+":"+s.st_size+":"+s.st_mtim.tv_sec+":"+s.st_mtim.tv_nsec+":"+s.st_ctim.tv_sec+":"+s.st_ctim.tv_nsec;
    }
    private JSONObject fileTouch(JSONObject a) throws Exception {
        File file = safeFile(required(a, "path"));
        if (!file.isFile() || !file.setLastModified(System.currentTimeMillis())) throw new IOException("touch_failed");
        return new JSONObject().put("modified", file.lastModified());
    }
    private JSONObject fileStat(JSONObject a) throws Exception {
        File f = safeFile(required(a, "path")); return new JSONObject().put("exists", f.exists()).put("file", f.isFile()).put("directory", f.isDirectory()).put("size", f.isFile() ? f.length() : 0).put("modified", f.exists() ? f.lastModified() : 0).put("changeToken", f.exists()?fileChangeToken(f):"missing");
    }
    private JSONObject fileDelete(JSONObject a) throws Exception { File f = safeFile(required(a, "path")); boolean deleted = f.isFile() && f.delete(); if (deleted || !f.exists()) temporaryFiles.remove(f); return new JSONObject().put("deleted", deleted); }

    private File safeFile(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_path");
        File f = new File(root, path).getCanonicalFile(); String base = root.getPath() + File.separator;
        if (!f.getPath().startsWith(base)) throw new SecurityException("path_outside_root"); return f;
    }
    private static int bounded(int n) { if (n < 0 || n > MAX_CHUNK_BYTES) throw new IllegalArgumentException("invalid_max_bytes"); return n; }
    private static String required(JSONObject a, String k) { String v = a.optString(k, null); if (v == null) throw new IllegalArgumentException("missing_" + k); return v; }
    private static byte[] decodeInput(JSONObject a) { return a.has("dataBase64") ? decode(a, "dataBase64") : required(a, "data").getBytes(StandardCharsets.UTF_8); }
    private static byte[] decode(JSONObject a, String k) { try { byte[] b = Base64.decode(required(a, k), Base64.DEFAULT); if (b.length > MAX_CHUNK_BYTES) throw new IllegalArgumentException("chunk_too_large"); return b; } catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid_base64", e); } }
    private static String enc(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static boolean countedContains(KeyStore store,String alias)throws Exception {SecureOperationStats.PROCESS.op("containsAlias");return store.containsAlias(alias);}
    private static java.security.Key countedKey(KeyStore store,String alias)throws Exception {SecureOperationStats.PROCESS.op("getKey");return store.getKey(alias,null);}
    private static java.security.cert.Certificate countedCertificate(KeyStore store,String alias)throws Exception {SecureOperationStats.PROCESS.op("getCertificate");return store.getCertificate(alias);}
    private static KeyStore.Entry countedEntry(KeyStore store,String alias)throws Exception {SecureOperationStats.PROCESS.op("getEntry");return store.getEntry(alias,null);}
    private static void keyOp(java.security.Key key,String op){if(key.getClass().getName().startsWith("android.security.keystore"))SecureOperationStats.PROCESS.op(op);}
    private SecretKey getKey(String alias) throws Exception {
        synchronized(SECURE_LOCK){
            SecretKey cached=secureMemory.aliasKeys.get(alias);if(cached!=null)return cached;
            FailureBackoff failure=secureMemory.aliasFailures.computeIfAbsent(alias,k->new FailureBackoff());failure.check();
            try{KeyStore ks=ProcessKeyStore.get();
            if(!countedContains(ks,alias)){KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,KEYSTORE);kg.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());SecureOperationStats.PROCESS.op("generate");kg.generateKey();}
            SecretKey key=((KeyStore.SecretKeyEntry)countedEntry(ks,alias)).getSecretKey();secureMemory.aliasKeys.put(alias,key);failure.success();return key;}catch(Exception error){failure.failed(error);throw error;}
        }
    }
    private byte[] encryptWithKey(SecretKey key, byte[] input) throws Exception { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); keyOp(key,"cipherInit");c.init(Cipher.ENCRYPT_MODE, key); keyOp(key,"cipherAad");c.updateAAD(AAD);keyOp(key,"cipherFinal"); byte[] body = c.doFinal(input); byte[] nonce = c.getIV(); if (nonce == null || nonce.length != 12) throw new IOException("invalid_keystore_iv"); byte[] all = new byte[nonce.length + body.length]; System.arraycopy(nonce, 0, all, 0, nonce.length); System.arraycopy(body, 0, all, nonce.length, body.length); return all; }
    private static byte[] decryptWithKey(SecretKey key, byte[] all) throws Exception { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); keyOp(key,"cipherInit");c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(all, 12))); keyOp(key,"cipherAad");c.updateAAD(AAD);keyOp(key,"cipherFinal"); return c.doFinal(Arrays.copyOfRange(all, 12, all.length)); }
    private static byte[] readAll(File f, int limit) throws IOException { try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int n, total = 0; while ((n = in.read(b)) >= 0) { total += n; if (total > limit) throw new IOException("secure_store_too_large"); out.write(b, 0, n); } return out.toByteArray(); } }
    @FunctionalInterface interface AtomicWriteOutputFactory { FileOutputStream open(File temporary) throws IOException; }
    static void atomicWrite(File target, byte[] data) throws IOException { atomicWrite(target,data,FileOutputStream::new); }
    static void atomicWrite(File target,byte[] data,AtomicWriteOutputFactory outputFactory)throws IOException {
        java.nio.file.Path temporary=Files.createTempFile(target.getParentFile().toPath(),".tmp-",".pod-write");
        Throwable failure=null;
        try {
            try(FileOutputStream output=outputFactory.open(temporary.toFile())){
                output.write(data);output.flush();output.getFD().sync();
            }
            try { Files.move(temporary,target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(java.nio.file.AtomicMoveNotSupportedException unsupported){Files.move(temporary,target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException|RuntimeException|Error error){failure=error;throw error;}
        finally {
            // Only this call's atomically allocated path belongs to us. Cover
            // open/write/flush/sync/close failures as well as publication errors.
            try{Files.deleteIfExists(temporary);}
            catch(IOException|RuntimeException|Error cleanup){if(failure!=null){if(cleanup!=failure)failure.addSuppressed(cleanup);}else throw cleanup;}
        }
    }
}
