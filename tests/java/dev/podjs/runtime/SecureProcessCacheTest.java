package dev.podjs.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Production cache contract: process lifetime, shared writers, atomic persistence. */
public final class SecureProcessCacheTest {
    static final SecretKeySpec KEY=new SecretKeySpec(new byte[32],"AES");
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static String get(PodDeviceServices h)throws Exception{return h.execute("secure.get",new JSONObject().put("key","identity")).optString("value",null);}
    static void set(PodDeviceServices h,String value)throws Exception{h.execute("secure.set",new JSONObject().put("key","identity").put("value",value));}
    public static void main(String[] args)throws Exception {
        JSONObject nested=new JSONObject().put("value","old"),original=new JSONObject().put("nested",nested).put("flat","secret");
        SecureStoreMemory.Snapshot immutable=new SecureStoreMemory.Snapshot(original);
        JSONObject copy=SecureStoreMemory.copy(immutable.values);copy.getJSONObject("nested").put("value","edited");copy.put("flat","edited");nested.put("value","external");
        check(immutable.values.getJSONObject("nested").getString("value").equals("old"),"nested snapshot alias");
        check(immutable.values.getString("flat").equals("secret"),"flat snapshot alias");
        Path dir=Paths.get(args[0]);Files.createDirectories(dir);AtomicInteger keys=new AtomicInteger();
        PodDeviceServices.SecureKeySource key=()->{keys.incrementAndGet();return KEY;};
        try(PodDeviceServices first=new PodDeviceServices(dir.resolve("files").toFile(),key);
            PodDeviceServices second=new PodDeviceServices(dir.resolve("files").toFile(),key)){
            set(first,"old");byte[] before=Files.readAllBytes(dir.resolve("secure.store"));
            for(int i=0;i<1000;i++){check("old".equals(get(second)),"shared get differs");set(second,"old");}
            check(keys.get()==1,"no-op loop loaded keys again");check(Arrays.equals(before,Files.readAllBytes(dir.resolve("secure.store"))),"no-op rewrote ciphertext");
            // Simulate a long held native write lock. Warm foreground reads
            // must return the last committed snapshot without waiting for it.
            java.lang.reflect.Field f=PodDeviceServices.class.getDeclaredField("SECURE_LOCK");f.setAccessible(true);Object lock=f.get(null);
            CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> holding=executor.submit(()->{synchronized(lock){locked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
            check(locked.await(2,TimeUnit.SECONDS),"write lock not held");
            long started=System.nanoTime();try{check("old".equals(get(second)),"read during write differs");}finally{release.countDown();holding.get(2,TimeUnit.SECONDS);executor.shutdownNow();}
            long readNs=System.nanoTime()-started;check(readNs<50_000_000L,"warm read waited behind write");
            set(second,"new");check("new".equals(get(first)),"writer did not publish to foreground");
            check("new".equals(PodDeviceServices.readSecureFile(dir.resolve("secure.store").toFile(),KEY).getString("identity")),"persisted value differs");
            first.execute("secure.update",new JSONObject().put("values",new JSONObject().put("scope.cookie","cookie").put("scope.key","key")));
            second.execute("secure.update",new JSONObject().put("values",new JSONObject()).put("removePrefixes",new org.json.JSONArray().put("scope.")));
            check(!first.execute("secure.get",new JSONObject().put("key","scope.cookie")).getBoolean("exists"),"prefix clear left memory");
            first.execute("secure.delete",new JSONObject().put("key","identity"));check(get(second)==null,"logout left memory");
            check(keys.get()==1,"per-write key lookup returned");
            set(first,"committed");Files.delete(dir.resolve("secure.store"));Files.createDirectory(dir.resolve("secure.store"));Files.write(dir.resolve("secure.store/keep"),new byte[]{1});
            boolean failed=false;try{set(second,"uncommitted");}catch(Exception expected){failed=true;}check(failed,"failed publication accepted");check("committed".equals(get(first)),"failed write poisoned memory");
            System.out.println(new JSONObject().put("keyLoads",keys.get()).put("warmReads",1000).put("noopWrites",1000).put("foregroundReadNs",readNs).put("sharedClear",true).put("failedWritePreserved",true));
        }
        try(PodDeviceServices next=new PodDeviceServices(dir.resolve("files").toFile(),key)){check("committed".equals(get(next)),"view close cleared process memory");check(keys.get()==1,"new window loaded key again");}
        Path corrupt=dir.resolve("cold-corrupt");Files.createDirectories(corrupt);Files.write(corrupt.resolve("secure.store"),new byte[28]);
        int beforeFailure=keys.get();
        try(PodDeviceServices cold=new PodDeviceServices(corrupt.resolve("files").toFile(),key)){
            for(int i=0;i<100;i++){boolean failed=false;try{get(cold);}catch(Exception expected){failed=true;}check(failed,"cold tamper accepted");}
        }
        check(keys.get()==beforeFailure+1,"cold failure busy-retried KeyStore");
        System.out.println("SECURE_PROCESS_CACHE_PASS");
    }
}
