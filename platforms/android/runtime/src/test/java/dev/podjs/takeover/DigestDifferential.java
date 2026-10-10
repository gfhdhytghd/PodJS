package dev.podjs.takeover;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared JVM/Robolectric differential oracle; legacy is the unedited baseline with class renamed. */
public final class DigestDifferential {
    private static final String GENERATION="g-differential-31";
    private static final String[] NAMES={"ascii", "中文/é", "😀", "\ue000", "e\u0301", " spaces #:=!", "\u0000", "\ud800", "\udfff", "?", "\n\r\t"};
    interface Call { Object run() throws Exception; }
    static int cases;
    private static Object outcome(Call call)throws Exception {
        try{return call.run();}
        catch(IOException e){return e.getClass().getName()+":"+e.getMessage();}
        catch(IllegalArgumentException e){return e.getClass().getName()+":"+e.getMessage();}
    }
    private static void equal(Call oldCall,Call newCall)throws Exception {
        Object old=outcome(oldCall),now=outcome(newCall);
        if(!Objects.equals(old,now))throw new AssertionError("differential case "+cases+": "+old+" != "+now);
        cases++;
    }
    private static void compare(Properties p,String phase,String generation)throws Exception {
        equal(()->LegacyTakeoverDigest.digestManifest(p,phase,generation),()->TakeoverDigest.digestManifest(p,phase,generation));
    }
    public static Properties fixture(int count) {
        Random random=new Random(31);Properties p=new Properties();p.setProperty("entries",Integer.toString(count));
        for(int i=0;i<count;i++) {
            String key="entry."+i+".";
            p.setProperty(key+"type",i%17==0?"directory":"file");
            p.setProperty(key+"path",TakeoverDigest.ROOTS[i%4]+"/"+NAMES[i%NAMES.length]+"/item-"+i);
            p.setProperty(key+"size",Long.toString(i%29==0?0:random.nextInt(100000)));
            char[] hash=new char[64];for(int h=0;h<64;h++)hash[h]="0123456789abcdef".charAt(random.nextInt(16));
            p.setProperty(key+"sha256",new String(hash));
        }
        return p;
    }
    private static Properties copy(Properties p){Properties out=new Properties();out.putAll(p);return out;}
    private static void bad(Properties p,String key,String value)throws Exception {
        Properties q=copy(p);if(value==null)q.remove(key);else q.setProperty(key,value);
        compare(q,"ACTIVE",GENERATION);
        Object result=outcome(()->TakeoverDigest.digestManifest(q,"ACTIVE",GENERATION));
        if(!"java.io.IOException:digest_invalid".equals(result))throw new AssertionError("expected digest_invalid for "+key);
    }
    public static void verify()throws Exception {
        cases=0;
        for(int i=0;i<120;i++){Properties p=fixture(i);compare(p,"SNAPSHOTTED",GENERATION);compare(p,"ACTIVE",GENERATION);}
        Random fuzz=new Random(0x31d1ff);
        for(int trial=0;trial<100;trial++) {
            Properties random=fixture(1+fuzz.nextInt(200));int count=Integer.parseInt(random.getProperty("entries"));
            for(int i=0;i<count;i++) {
                StringBuilder name=new StringBuilder();
                for(int j=0,length=1+fuzz.nextInt(40);j<length;j++)name.append(NAMES[fuzz.nextInt(NAMES.length)]);
                random.setProperty("entry."+i+".path",TakeoverDigest.ROOTS[fuzz.nextInt(4)]+"/"+name+"/unique-"+i);
                random.setProperty("entry."+i+".type",fuzz.nextInt(4)==0?"directory":"file");
            }
            compare(random,"ACTIVE",GENERATION);
        }
        Properties empty=fixture(0);compare(empty,"ACTIVE",GENERATION);
        // Defaults, supplementary/BMP order and malformed UTF-16 replacement bytes must retain seal semantics.
        Properties defaults=fixture(12),inherited=new Properties(defaults);inherited.setProperty("extra.😀","é");compare(inherited,"ACTIVE",GENERATION);
        Properties p=fixture(3);p.setProperty("entry.0.type","file");
        for(String value:new String[]{"", "../file", "files/", "files//name", "files/./x", "files/../x", "files/a\\b", "/files/x", "unknown/x", "files/podjs/x", "files/package-takeover/x", "files/.podjs-hidden", "databases/podjs.sqlite-wal", "no_backup/podjs-secret"})bad(p,"entry.0.path",value);
        for(String key:new String[]{"path","type","size","sha256"})bad(p,"entry.0."+key,null);
        for(String value:new String[]{"", "other", "FILE"})bad(p,"entry.0.type",value);
        for(String value:new String[]{"", "-1", "1000001", "2147483648", "1.0", " 1"})bad(p,"entries",value);
        for(String value:new String[]{"", "-1", "9223372036854775808", "1.2", " 1", "NaN"})bad(p,"entry.0.size",value);
        for(String value:new String[]{"", "a", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65), "a".repeat(63)+"\n"})bad(p,"entry.0.sha256",value);
        for(String value:new String[]{null,"", "g-", "g-中文", "g-a\n", "g-a_", "x-a"})compare(p,"ACTIVE",value);
        compare(p,"OTHER",GENERATION);compare(p,null,GENERATION);
        Properties overflow=fixture(2);overflow.setProperty("entry.0.type","file");
        overflow.setProperty("entry.0.size",Long.toString(Long.MAX_VALUE));overflow.setProperty("entry.1.size","1");compare(overflow,"ACTIVE",GENERATION);
        Properties duplicate=copy(p);duplicate.setProperty("entry.1.path",p.getProperty("entry.0.path"));compare(duplicate,"ACTIVE",GENERATION);
        duplicate.setProperty("entry.0.path","files/\ud800");duplicate.setProperty("entry.1.path","files/?");compare(duplicate,"ACTIVE",GENERATION);
        Properties numeric=fixture(1);numeric.setProperty("entry.0.type","file");
        for(String value:new String[]{"0","+0","-0","+42","00042","٤٢",Long.toString(Long.MAX_VALUE)}) {
            numeric.setProperty("entry.0.size",value);compare(numeric,"ACTIVE",GENERATION);
        }
        numeric.setProperty("entry.0.type","directory");numeric.remove("entry.0.size");numeric.remove("entry.0.sha256");compare(numeric,"ACTIVE",GENERATION);
        Properties boundaries=fixture(9);
        String[] paths={"files/profileInstalled","files/profileInstalled.user","files/profileinstaller_profileWrittenFor_lastUpdateTime.dat","files/profileinstaller_profileWrittenFor_lastUpdateTime.dat.extra","files/"+"长".repeat(20000),"files/\ud800","files/\udfff/x","files/podjs2","no_backup/podjs"};
        for(int i=0;i<paths.length;i++){boundaries.setProperty("entry."+i+".path",paths[i]);boundaries.setProperty("entry."+i+".type","file");}
        compare(boundaries,"ACTIVE",GENERATION);
        Properties large=fixture(10000);compare(large,"ACTIVE",GENERATION);
        Properties reversed=new Properties();reversed.setProperty("entries","10000");
        for(int i=0;i<10000;i++)for(String key:new String[]{"type","path","size","sha256"})reversed.setProperty("entry."+(9999-i)+"."+key,large.getProperty("entry."+i+"."+key));
        compare(reversed,"ACTIVE",GENERATION);
        if(!TakeoverDigest.digestManifest(large,"ACTIVE",GENERATION).equals(TakeoverDigest.digestManifest(reversed,"ACTIVE",GENERATION)))throw new AssertionError("order unstable");
        String[] savedRoots=TakeoverDigest.ROOTS.clone();
        try {TakeoverDigest.ROOTS[0]="mutated-public-array";compare(p,"ACTIVE",GENERATION);}
        finally{System.arraycopy(savedRoots,0,TakeoverDigest.ROOTS,0,savedRoots.length);}
        Path sandbox=Files.createTempDirectory("digest-differential-");
        try {
            activeFixture(sandbox,large);
            equal(()->LegacyTakeoverDigest.activeLines(sandbox.toFile(),GENERATION),()->TakeoverDigest.activeLines(sandbox.toFile(),GENERATION));
            equal(()->LegacyTakeoverDigest.captureActive(sandbox.toFile(),GENERATION).lines(),()->TakeoverDigest.captureActive(sandbox.toFile(),GENERATION).lines());
            Path base=sandbox.resolve(NativeBackup.BACKUP+"/"+GENERATION);
            for(String file:new String[]{"manifest","seal","ready"}) {
                Path target=base.resolve(file);byte[] good=Files.readAllBytes(target);
                for(String invalid:new String[]{"sha256=bad\n","bad=\\uZZZZ\n"}) {
                    Files.write(target,invalid.getBytes(StandardCharsets.ISO_8859_1));
                    equal(()->LegacyTakeoverDigest.activeLines(sandbox.toFile(),GENERATION),()->TakeoverDigest.activeLines(sandbox.toFile(),GENERATION));
                    equal(()->LegacyTakeoverDigest.captureActive(sandbox.toFile(),GENERATION).lines(),()->TakeoverDigest.captureActive(sandbox.toFile(),GENERATION).lines());
                }
                Files.write(target,good);
            }
            Path ready=base.resolve("ready"),moved=base.resolve("original-ready");
            Files.move(ready,moved);Files.createSymbolicLink(ready,moved);
            equal(()->LegacyTakeoverDigest.activeLines(sandbox.toFile(),GENERATION),()->TakeoverDigest.activeLines(sandbox.toFile(),GENERATION));
            equal(()->LegacyTakeoverDigest.captureActive(sandbox.toFile(),GENERATION),()->TakeoverDigest.captureActive(sandbox.toFile(),GENERATION));
            Files.delete(ready);
            equal(()->LegacyTakeoverDigest.activeLines(sandbox.toFile(),GENERATION),()->TakeoverDigest.activeLines(sandbox.toFile(),GENERATION));
            Files.move(moved,ready);
            for(String g:new String[]{null,"g-","g-中文","g-../invalid"}) {
                equal(()->LegacyTakeoverDigest.activeLines(sandbox.toFile(),g),()->TakeoverDigest.activeLines(sandbox.toFile(),g));
                equal(()->LegacyTakeoverDigest.captureActive(sandbox.toFile(),g),()->TakeoverDigest.captureActive(sandbox.toFile(),g));
            }
            // Concurrent calls cannot share a digest or staging buffer.
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try {
                List<String> expected=TakeoverDigest.digestManifest(boundaries,"ACTIVE",GENERATION);
                List<Future<List<String>>> futures=new ArrayList<>();
                for(int i=0;i<12;i++)futures.add(pool.submit(()->TakeoverDigest.digestManifest(boundaries,"ACTIVE",GENERATION)));
                for(Future<List<String>> f:futures)if(!expected.equals(f.get()))throw new AssertionError("shared state");
            }finally{pool.shutdownNow();}
        }finally{remove(sandbox);}
        System.out.println("Digest differential PASS cases="+cases+" entries=10000 concurrent=12");
    }
    static void activeFixture(Path sandbox,Properties manifest)throws Exception {
        manifest=copy(manifest);manifest.setProperty("protocol","1");manifest.setProperty("generation",GENERATION);
        manifest.setProperty("extra.😀","\ud800");manifest.setProperty("extra.\ue000","😀");
        Method seal=LegacyTakeoverDigest.class.getDeclaredMethod("seal",Properties.class);seal.setAccessible(true);
        String sha=(String)seal.invoke(null,manifest);
        Path base=sandbox.resolve(NativeBackup.BACKUP+"/"+GENERATION);Files.createDirectories(base);
        try(OutputStream out=Files.newOutputStream(base.resolve("manifest"))){manifest.store(out,"synthetic differential metadata");}
        for(String name:new String[]{"seal","ready"})Files.write(base.resolve(name),("sha256="+sha+"\n").getBytes(StandardCharsets.ISO_8859_1));
    }
    static void remove(Path path)throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(path)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
    }
    private static volatile Object sink;
    static double median(Call call,int warmup,int rounds)throws Exception {
        for(int i=0;i<warmup;i++)sink=call.run();double[] samples=new double[rounds];
        for(int i=0;i<rounds;i++){long start=System.nanoTime();sink=call.run();samples[i]=(System.nanoTime()-start)/1e6;}
        System.out.println("samples_ms="+Arrays.toString(samples));Arrays.sort(samples);return samples[rounds/2];
    }
    public static void benchmark(String runtime)throws Exception {
        Properties p=fixture(10000);Path sandbox=Files.createTempDirectory("digest-benchmark-");
        try {
            activeFixture(sandbox,p);
            for(String operation:new String[]{"projection","activeLines","capturedLines"}) {
                LegacyTakeoverDigest.ActiveMetadata oldCapture=LegacyTakeoverDigest.captureActive(sandbox.toFile(),GENERATION);
                TakeoverDigest.ActiveMetadata newCapture=TakeoverDigest.captureActive(sandbox.toFile(),GENERATION);
                Call old=operation.equals("projection")?()->LegacyTakeoverDigest.digestManifest(p,"ACTIVE",GENERATION):operation.equals("activeLines")?()->LegacyTakeoverDigest.activeLines(sandbox.toFile(),GENERATION):oldCapture::lines;
                Call now=operation.equals("projection")?()->TakeoverDigest.digestManifest(p,"ACTIVE",GENERATION):operation.equals("activeLines")?()->TakeoverDigest.activeLines(sandbox.toFile(),GENERATION):newCapture::lines;
                equal(old,now);
                double before=median(old,3,7),after=median(now,3,7);
                System.out.printf(Locale.ROOT,"BENCH runtime=%s operation=%s entries=10000 warmup=3 rounds=7 before_ms=%.3f after_ms=%.3f speedup=%.2f%n",runtime,operation,before,after,before/after);
            }
        }finally{remove(sandbox);}
    }
    public static void main(String[] args)throws Exception {verify();if(args.length>0)benchmark("JVM-"+System.getProperty("java.version"));}
}
