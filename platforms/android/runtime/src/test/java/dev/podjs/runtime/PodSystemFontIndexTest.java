package dev.podjs.runtime;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;

public class PodSystemFontIndexTest {
    @Test public void roundTripPreservesAxesAndIndexAndInvalidatesOnEveryIdentityInput()throws Exception {
        File root=Files.createTempDirectory("font-index").toFile(),font=new File(root,"Family.ttc");
        try {
            Files.write(font.toPath(),new byte[]{1,2,3});PodSystemFonts.Family family=new PodSystemFonts.Family();family.name="sans-serif";family.lang="zh-Hans";
            PodSystemFonts.Face face=new PodSystemFonts.Face();face.file=font.getName();face.index=2;face.weight=700;face.axes.add("'wght' 750.0");family.faces.add(face);
            byte[] raw=PodSystemFontIndex.encode("xml","build","zh",List.of(family),root,"a".repeat(64));
            PodSystemFontIndex.Value value=PodSystemFontIndex.decode(raw,"xml","build","zh",root);assertNotNull(value);
            assertEquals("a".repeat(64),value.identity);assertEquals("zh-Hans",value.families.get(0).lang);assertEquals(2,value.families.get(0).faces.get(0).index);assertEquals(face.axes,value.families.get(0).faces.get(0).axes);
            assertNull(PodSystemFontIndex.decode(raw,"xml2","build","zh",root));assertNull(PodSystemFontIndex.decode(raw,"xml","build2","zh",root));assertNull(PodSystemFontIndex.decode(raw,"xml","build","en",root));
            long mtime=font.lastModified();assertTrue(font.setLastModified(mtime+2000));assertNull(PodSystemFontIndex.decode(raw,"xml","build","zh",root));font.setLastModified(mtime);
            Files.write(font.toPath(),new byte[]{1,2,3,4});assertNull(PodSystemFontIndex.decode(raw,"xml","build","zh",root));
            for(int n=0;n<raw.length;n++)assertNull(PodSystemFontIndex.decode(Arrays.copyOf(raw,n),"xml","build","zh",root));
        }finally{font.delete();root.delete();}
    }
    @Test public void checksumAndCountCorruptionAreMissesBeforeUsingCachedMetadata()throws Exception {
        File root=Files.createTempDirectory("font-index-crc").toFile(),font=new File(root,"F.ttf");
        try {
            Files.write(font.toPath(),new byte[]{1});PodSystemFonts.Family family=new PodSystemFonts.Family();family.name="sans-serif";PodSystemFonts.Face face=new PodSystemFonts.Face();face.file=font.getName();family.faces.add(face);
            byte[] raw=PodSystemFontIndex.encode("x","b","en",List.of(family),root,"b".repeat(64));
            raw[raw.length-1]^=1;assertNull(PodSystemFontIndex.decode(raw,"x","b","en",root));assertNull(PodSystemFontIndex.decode(new byte[128*1024+1],"x","b","en",root));
        }finally{font.delete();root.delete();}
    }
    @Test public void commonStyleSelectionRetainsXmlAxisAndCollectionRatherThanOpeningEveryWeight() {
        ArrayList<PodSystemFonts.Face> faces=new ArrayList<>();
        for(int weight:new int[]{100,300,400,500,700,900})for(String style:new String[]{"normal","italic"}){PodSystemFonts.Face f=new PodSystemFonts.Face();f.file="F.ttf";f.weight=weight;f.style=style;faces.add(f);}
        assertEquals(400,PodSystemFonts.closest(faces,400,false).weight);assertEquals(700,PodSystemFonts.closest(faces,700,false).weight);
        assertEquals("italic",PodSystemFonts.closest(faces,400,true).style);
        PodSystemFonts.Face axis=new PodSystemFonts.Face();axis.file="OPlusSans3.ttf";axis.weight=700;axis.index=2;axis.axes.add("'wght' 750.0");
        PodSystemFonts.Face selected=PodSystemFonts.closest(List.of(axis),700,false);assertSame(axis,selected);assertEquals(2,selected.index);assertEquals("'wght' 750.0",selected.axes.get(0));
    }
}
