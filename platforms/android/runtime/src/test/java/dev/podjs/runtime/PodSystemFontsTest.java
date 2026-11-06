package dev.podjs.runtime;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodSystemFontsTest {
    @Test public void cachedMetricsRoundTripWithoutSharingMutableWarmupArraysAndInvalidateWithFontFiles()throws Exception {
        File root=java.nio.file.Files.createTempDirectory("font-metrics").toFile(),font=new File(root,"Font.ttf");
        try {
            java.nio.file.Files.write(font.toPath(),new byte[]{1,2,3});
            List<PodSystemFonts.Family> families=parse("<familyset><family name='sans-serif'><font>Font.ttf</font></family></familyset>");
            int[] values={14,17,14,21};PodSystemFontIndex.Metric m=new PodSystemFontIndex.Metric(14,1,2,values);values[0]=99;
            byte[] raw=PodSystemFontIndex.encode("xml","build","zh",families,root,"a".repeat(64),java.util.List.of(m));
            PodSystemFontIndex.Value decoded=PodSystemFontIndex.decode(raw,"xml","build","zh",root);assertNotNull(decoded);assertEquals(1,decoded.metrics.size());
            PodSystemFontIndex.Metric actual=decoded.metrics.get(0);assertEquals(m.key(),actual.key());assertArrayEquals(new int[]{14,17,14,21},actual.copy());
            int[] warm=actual.copy();warm[0]=40;warm[1]=40;assertArrayEquals(new int[]{14,17,14,21},actual.copy());
            font.setLastModified(font.lastModified()+2000);assertNull(PodSystemFontIndex.decode(raw,"xml","build","zh",root));
        }finally{font.delete();root.delete();}
    }
    @Test public void fastFileValidationMatchesThePreviousRegexForAllAsciiCharacterPairs() {
        java.util.regex.Pattern reference=java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
        for(int a=0;a<128;a++)for(int b=0;b<128;b++){
            String value=""+(char)a+(char)b;assertEquals(reference.matcher(value).matches(),PodSystemFontIndex.validFile(value));
        }
        assertFalse(PodSystemFontIndex.validFile(null));assertFalse(PodSystemFontIndex.validFile(""));assertFalse(PodSystemFontIndex.validFile("中文.ttf"));
    }
    @Test public void immutableFontFingerprintChangesWithBuildXmlAndFileIdentity()throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("font-identity");File font=root.resolve("Font.ttf").toFile();
        try {
            java.nio.file.Files.write(font.toPath(),new byte[]{1,2,3});byte[] xml="<familyset/>".getBytes(StandardCharsets.UTF_8);java.util.List<File> files=java.util.List.of(font);
            String first=PodSystemFonts.fingerprint(xml,"build-a",files);assertEquals(first,PodSystemFonts.fingerprint(xml,"build-a",files));
            assertNotEquals(first,PodSystemFonts.fingerprint(xml,"build-b",files));assertNotEquals(first,PodSystemFonts.fingerprint(new byte[]{4},"build-a",files));
            java.nio.file.Files.write(font.toPath(),new byte[]{1,2,3,4});assertNotEquals(first,PodSystemFonts.fingerprint(xml,"build-a",files));
            String second=PodSystemFonts.fingerprint(xml,"build-a",files);font.setLastModified(font.lastModified()+2000);assertNotEquals(second,PodSystemFonts.fingerprint(xml,"build-a",files));
        }finally{font.delete();root.toFile().delete();}
    }
    private List<PodSystemFonts.Family> parse(String xml)throws Exception{return PodSystemFonts.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));}
    @Test public void preservesCollectionIndexLanguageWeightAndVariationAxes()throws Exception {
        List<PodSystemFonts.Family> families=parse("<familyset><family name='sans-serif'><font weight='700' style='italic' index='2'> Font.ttc <axis tag='wght' stylevalue='750'/></font></family><family lang='zh-Hans'><font weight='400'>Chinese.ttf</font></family></familyset>");
        assertEquals(2,families.size());assertEquals("sans-serif",families.get(0).name);assertEquals("zh-Hans",families.get(1).lang);
        PodSystemFonts.Face face=families.get(0).faces.get(0);assertEquals("Font.ttc",face.file);assertEquals(2,face.index);assertEquals(700,face.weight);assertEquals("italic",face.style);assertEquals("'wght' 750.0",face.axes.get(0));
    }
    @Test public void rejectsInvalidAxisBeforeTypefaceConstruction() {
        assertThrows(Exception.class,()->parse("<familyset><family><font>F.ttf<axis tag='wght' stylevalue='NaN'/></font></family></familyset>"));
        assertThrows(Exception.class,()->parse("<familyset><family><font>F.ttf<axis tag='bad' stylevalue='3'/></font></family></familyset>"));
    }
}
