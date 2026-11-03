package dev.podjs.runtime;

import java.nio.file.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Synthetic stores only. The same compiled host runs on JVM and Android ART. */
public final class CloudPerfSecureProbe {
    static final SecretKeySpec KEY=new SecretKeySpec(new byte[32],"AES");
    static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    static String padding(int n){char[] c=new char[n];Arrays.fill(c,'x');return new String(c);}
    static JSONObject get(PodDeviceServices h)throws Exception{return h.execute("secure.get",new JSONObject().put("key","identity"));}
    static long cpu(){try{return ((Number)Class.forName("android.os.Debug").getMethod("threadCpuTimeNanos").invoke(null)).longValue();}catch(Exception unavailable){return -1;}}
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        boolean androidKeyStore=args.length>1&&"keystore".equals(args[1]);
        // app_process does not run ActivityThread's provider initialization.
        if(androidKeyStore)Class.forName(android.os.Build.VERSION.SDK_INT>=31?"android.security.keystore2.AndroidKeyStoreProvider":"android.security.keystore.AndroidKeyStoreProvider").getMethod("install").invoke(null);
        String alias="podjs.cloudperf.synthetic."+System.nanoTime();
        PodDeviceServices keyOwner=new PodDeviceServices(root.resolve("key-owner/files").toFile(),null);
        java.lang.reflect.Method getter=PodDeviceServices.class.getDeclaredMethod("getKey",String.class);getter.setAccessible(true);
        PodDeviceServices.SecureKeySource source=()->androidKeyStore?(javax.crypto.SecretKey)getter.invoke(keyOwner,alias):KEY;
        try {
        for(int size:new int[]{1024,65536,1048576}){
            Path dir=root.resolve("s"+size);Files.createDirectories(dir);
            int[] keys={0};
            try(PodDeviceServices host=new PodDeviceServices(dir.resolve("files").toFile(),()->{keys[0]++;return source.get();})){
                JSONObject values=new JSONObject().put("identity","synthetic");
                for(int i=0;i<size;i+=65536)values.put("padding"+i,padding(Math.min(65536,size-i)));
                host.execute("secure.update",new JSONObject().put("values",values));
                get(host);byte[] original=Files.readAllBytes(dir.resolve("secure.store"));
                long[] times=new long[100];long started=System.nanoTime(),cpu=cpu();int initialKeys=keys[0];
                for(int i=0;i<times.length;i++){long t=System.nanoTime();require("synthetic".equals(get(host).getString("value")),"read differs");times[i]=System.nanoTime()-t;}
                long elapsed=System.nanoTime()-started,used=cpu();Arrays.sort(times);
                System.out.println(new JSONObject().put("stage","secure-read").put("bytes",original.length).put("operations",times.length).put("elapsedNs",elapsed).put("cpuNs",cpu<0?-1:used-cpu).put("p50Ns",times[49]).put("p95Ns",times[94]).put("maxNs",times[99]).put("keyLoads",keys[0]-initialKeys));
                started=System.nanoTime();cpu=cpu();initialKeys=keys[0];
                for(int i=0;i<20;i++)host.execute("secure.set",new JSONObject().put("key","identity").put("value","synthetic"));
                System.out.println(new JSONObject().put("stage","secure-noop-write").put("bytes",original.length).put("operations",20).put("elapsedNs",System.nanoTime()-started).put("cpuNs",cpu<0?-1:cpu()-cpu).put("keyLoads",keys[0]-initialKeys).put("ciphertextUnchanged",Arrays.equals(original,Files.readAllBytes(dir.resolve("secure.store")))));
                try(PodDeviceServices writer=new PodDeviceServices(dir.resolve("files").toFile(),source)){
                    writer.execute("secure.set",new JSONObject().put("key","identity").put("value","replacement"));
                    require("replacement".equals(get(host).getString("value")),"stale cross-instance credentials");
                }
            }
            try(PodDeviceServices reopened=new PodDeviceServices(dir.resolve("files").toFile(),source)){require("replacement".equals(get(reopened).getString("value")),"reopen differs");}
        }
        System.out.println("SECURE_PROBE_PASS");
        } finally {
            keyOwner.close();
            if(androidKeyStore){java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);}
        }
    }
}
