package dev.podjs.runtime;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;

public class PodGlyphDiskCacheTest {
    @Test public void deferredInventoryReadsOldPixelsAndPreservesEarlyLruBeforeAnyDiskWrite()throws Exception {
        File root=Files.createTempDirectory("glyph-deferred").toFile();
        try {
            PodGlyphDiskCache old=new PodGlyphDiskCache(root,1200,0);old.put(key(1),cell(1));old.put(key(2),cell(2));
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200,0,true);assertFalse(cache.inventoryReady());
            assertArrayEquals(cell(1),cache.get(key(1),512));cache.putAsync(key(3),cell(3));
            assertEquals(2,root.listFiles().length);assertFalse(cache.awaitWrites(1));
            cache.enableWrites();assertTrue(cache.awaitWrites(10000));assertTrue(cache.inventoryReady());
            assertArrayEquals(cell(1),cache.get(key(1),512));assertNull(cache.get(key(2),512));assertArrayEquals(cell(3),cache.get(key(3),512));
            assertTrue(cache.bytes()<=1200);assertTrue(Arrays.stream(root.listFiles()).mapToLong(File::length).sum()<=1200);
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void deferredMetadataAndGlyphsRemainInRamUntilInventoryAndShareTheBudgetAfterDrain()throws Exception {
        File root=Files.createTempDirectory("glyph-deferred-index").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,32768,4096,true);byte[] index=cell(17);
            cache.fontIndex(index);cache.identity(key(8),key(9));cache.putAsync(key(1),cell(1));
            assertEquals(0,root.listFiles().length);assertArrayEquals(index,cache.fontIndex());assertEquals(key(9),cache.identity(key(8)));
            cache.enableWrites();assertTrue(cache.awaitWrites(10000));assertTrue(cache.inventoryReady());
            assertTrue(cache.bytes()<=32768);PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,32768,4096);
            assertArrayEquals(index,reopened.fontIndex());assertEquals(key(9),reopened.identity(key(8)));assertArrayEquals(cell(1),reopened.get(key(1),512));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void nativeMetadataTransferKeepsNamesLengthsAndMillisecondTimesAndRejectsPartialTables()throws Exception {
        File root=new File("/private/glyph-cache");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);out.writeInt(0x50474432);out.writeInt(1);
        byte[] name=(key(7)+".glyph").getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeShort(name.length);out.write(name);out.writeLong(123);out.writeLong(1234567890);out.writeByte(1);
        byte[] raw=bytes.toByteArray();java.util.ArrayList<PodGlyphDiskCache.Existing> rows=PodGlyphDiskCache.decodeScan(root,raw);
        assertEquals(1,rows.size());assertEquals(new File(root,key(7)+".glyph"),rows.get(0).file(root));assertEquals(123,rows.get(0).length);assertEquals(1234567890,rows.get(0).last);
        for(int length=0;length<raw.length;length++){
            try{PodGlyphDiskCache.decodeScan(root,java.util.Arrays.copyOf(raw,length));fail("partial directory table");}catch(java.io.IOException expected){}
        }
        raw[10]='/';try{PodGlyphDiskCache.decodeScan(root,raw);fail("path separator");}catch(java.io.IOException expected){}
    }
    @Test public void immutableArgbQueuePacksOnlyOnWriterAndSurvivesRestartWithExactFloatAndColorBits()throws Exception {
        File root=Files.createTempDirectory("glyph-argb").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES,4096);
            int[] argb={Float.floatToRawIntBits(12.5f),Float.floatToRawIntBits(1f),Float.floatToRawIntBits(2f),0x7f15879f,0xff00aa55,0};
            cache.putArgbAsync(key(1),argb);assertEquals(0,root.listFiles().length);
            assertSame(argb,cache.getArgb(key(1),argb.length*4));assertFalse(cache.awaitWrites(1));
            cache.enableWrites();assertTrue(cache.awaitWrites(10000));
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES,4096);
            assertArrayEquals(argb,reopened.getArgb(key(1),argb.length*4));
            byte[] raw=reopened.get(key(1),argb.length*4);
            assertEquals(0,raw[0]);assertEquals(0,raw[1]);assertEquals(0x48,raw[2]&255);assertEquals(0x41,raw[3]&255);
            assertEquals(0x9f,raw[12]&255);assertEquals(0x87,raw[13]&255);assertEquals(0x15,raw[14]&255);assertEquals(0x7f,raw[15]&255);
            assertTrue(reopened.bytes()<=PodGlyphDiskCache.MAX_BYTES);
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void pendingPixelsStayBoundedUntilFirstPresentationAndPersistWithoutChangingBytes()throws Exception {
        File root=Files.createTempDirectory("glyph-pending").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES,4096);
            byte[] raw=new byte[256*1024];new Random(41).nextBytes(raw);
            for(int i=1;i<=5;i++)cache.putAsync(key(i),raw);
            assertEquals(0,root.listFiles().length);assertFalse(cache.awaitWrites(1));
            for(int i=1;i<=4;i++)assertArrayEquals(raw,cache.get(key(i),raw.length));
            assertNull(cache.get(key(5),raw.length));cache.enableWrites();assertTrue(cache.awaitWrites(10000));
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES,4096);
            for(int i=1;i<=4;i++)assertArrayEquals(raw,reopened.get(key(i),raw.length));
            assertTrue(reopened.bytes()<=PodGlyphDiskCache.MAX_BYTES);
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void parsedFontIndexSharesTheSamePhysicalBudgetWithPixelsAndTemporaryWrites()throws Exception {
        File root=Files.createTempDirectory("glyph-index-budget").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,32768,4096);byte[] index=new byte[512];new Random(7).nextBytes(index);
            cache.fontIndex(index);for(int i=1;i<=8;i++)cache.put(key(i),cell(i));
            assertArrayEquals(index,cache.fontIndex());assertTrue(cache.bytes()<=32768);
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,32768,4096);assertArrayEquals(index,reopened.fontIndex());assertTrue(reopened.bytes()<=32768);
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void fullBudgetReservesDirectoryGrowthBeforeEvictingLongNames()throws Exception {
        File root=Files.createTempDirectory("glyph-directory").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES,4096);
            assertTrue(cache.bytes()>=512*1024);
            for(int i=1;i<=2000;i++)cache.put(key(i),cell(i));
            assertTrue(cache.bytes()<=PodGlyphDiskCache.MAX_BYTES);
            assertTrue(root.listFiles().length<=1984);
            assertNull(cache.get(key(1),512));assertArrayEquals(cell(2000),cache.get(key(2000),512));
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,PodGlyphDiskCache.MAX_BYTES,4096);
            assertTrue(reopened.bytes()<=PodGlyphDiskCache.MAX_BYTES);
            assertArrayEquals(cell(2000),reopened.get(key(2000),512));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void allocationBlocksAndDirectoryOverheadShareTheBudget()throws Exception {
        File root=Files.createTempDirectory("glyph-blocks").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,32768,4096);
            for(int i=1;i<=8;i++)cache.put(key(i),cell(i));
            assertTrue(cache.bytes()<=32768);assertEquals(3,root.listFiles().length);
            assertNull(cache.get(key(1),512));assertArrayEquals(cell(8),cache.get(key(8),512));
            assertTrue(cache.contains(key(8)));assertFalse(cache.contains(key(1)));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    private static String key(int i){return String.format(Locale.ROOT,"%064x",i);}
    private static byte[] cell(int seed){byte[] bytes=new byte[512];new Random(seed).nextBytes(bytes);return bytes;}
    @Test public void boundedLruSurvivesRestartAndIncludesTemporaryWrites()throws Exception {
        File root=Files.createTempDirectory("glyph-cache").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200,0);
            cache.put(key(1),cell(1));cache.put(key(2),cell(2));assertArrayEquals(cell(1),cache.get(key(1),512));
            cache.put(key(3),cell(3));assertNull(cache.get(key(2),512));assertArrayEquals(cell(1),cache.get(key(1),512));
            assertTrue(cache.bytes()<=1200);assertTrue(Arrays.stream(root.listFiles()).mapToLong(File::length).sum()<=1200);
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,1200,0);assertArrayEquals(cell(1),reopened.get(key(1),512));
            assertEquals(2,root.listFiles().length);
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void corruptionWrongDimensionsAndTruncationAreCacheMisses()throws Exception {
        File root=Files.createTempDirectory("glyph-corrupt").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200,0);cache.put(key(1),cell(1));
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
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,0,0);assertEquals(0,root.listFiles().length);
            cache.put(key(1),cell(1));assertEquals(0,cache.bytes());
            assertThrows(IllegalArgumentException.class,()->cache.get("../escape",512));
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
    @Test public void fontIdentityReceiptIsBoundedAndInvalidatedByFileSignature()throws Exception {
        File root=Files.createTempDirectory("glyph-identity").toFile();
        try {
            PodGlyphDiskCache cache=new PodGlyphDiskCache(root,1200,0);cache.identity(key(1),key(2));assertEquals(key(2),cache.identity(key(1)));assertNull(cache.identity(key(3)));
            cache.put(key(4),cell(4));cache.put(key(5),cell(5));assertTrue(cache.bytes()<=1200);
            PodGlyphDiskCache reopened=new PodGlyphDiskCache(root,1200,0);assertEquals(key(2),reopened.identity(key(1)));
            assertEquals(Arrays.stream(root.listFiles()).mapToLong(File::length).sum(),reopened.bytes());
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
}
