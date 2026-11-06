package dev.podjs.runtime;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;

public class PodGlyphDiskCacheTest {
    private static String key(int i){return String.format(Locale.ROOT,"%064x",i);}
    private static byte[] cell(int seed){byte[] bytes=new byte[512];new Random(seed).nextBytes(bytes);return bytes;}
    @Test public void boundedLruSurvivesRestartAndIncludesTemporaryWrites()throws Exception {
        File root=Files.createTempDirectory("glyph-cache").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200);
            cache.put(key(1),cell(1));cache.put(key(2),cell(2));assertArrayEquals(cell(1),cache.get(key(1),512));
            cache.put(key(3),cell(3));assertNull(cache.get(key(2),512));assertArrayEquals(cell(1),cache.get(key(1),512));
            assertTrue(cache.bytes()<=1200);assertTrue(Arrays.stream(root.listFiles()).mapToLong(File::length).sum()<=1200);
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,1200);assertArrayEquals(cell(1),reopened.get(key(1),512));
            assertEquals(2,root.listFiles().length);
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void corruptionWrongDimensionsAndTruncationAreCacheMisses()throws Exception {
        File root=Files.createTempDirectory("glyph-corrupt").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200);cache.put(key(1),cell(1));
            assertNull(cache.get(key(1),513));assertEquals(0,cache.bytes());cache.put(key(1),cell(1));
            try(RandomAccessFile f=new RandomAccessFile(new File(root,key(1)+".glyph"),"rw")){f.setLength(19);}
            assertNull(cache.get(key(1),512));assertEquals(0,cache.bytes());
            cache.put(key(1),cell(1));try(RandomAccessFile f=new RandomAccessFile(new File(root,key(1)+".glyph"),"rw")){f.seek(8);f.writeLong(123);}
            assertNull(cache.get(key(1),512));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void rejectsBudgetAndPathsAndDiscardsAbandonedWrites()throws Exception {
        File root=Files.createTempDirectory("glyph-invalid").toFile();
        try {
            assertThrows(IllegalArgumentException.class,()->new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES+1));
            Files.write(new File(root,"abandoned.tmp").toPath(),new byte[17]);
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,0);assertEquals(0,root.listFiles().length);
            cache.put(key(1),cell(1));assertEquals(0,cache.bytes());
            assertThrows(IllegalArgumentException.class,()->cache.get("../escape",512));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void fontIdentityReceiptIsBoundedAndInvalidatedByFileSignature()throws Exception {
        File root=Files.createTempDirectory("glyph-identity").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200);cache.identity(key(1),key(2));assertEquals(key(2),cache.identity(key(1)));assertNull(cache.identity(key(3)));
            cache.put(key(4),cell(4));cache.put(key(5),cell(5));assertTrue(cache.bytes()<=1200);
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,1200);assertEquals(key(2),reopened.identity(key(1)));
            assertEquals(Arrays.stream(root.listFiles()).mapToLong(File::length).sum(),reopened.bytes());
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
}
