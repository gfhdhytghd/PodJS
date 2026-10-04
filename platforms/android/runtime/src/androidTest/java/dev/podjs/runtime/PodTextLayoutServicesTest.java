package dev.podjs.runtime;
import android.os.CancellationSignal;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodTextLayoutServicesTest {
    @Test public void lineEndsMatchProductionComposeWatchOracle() throws Exception {
        String json;
        try (java.io.InputStream stream = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("reader-line-breaks-native.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer,0,count);
            json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        JSONArray cases = new JSONObject(json).getJSONArray("cases"); assertEquals(288, cases.length());
        for (int i=0;i<cases.length();i++) {
            JSONObject item=cases.getJSONObject(i);
            JSONObject args=new JSONObject().put("text",item.getString("text")).put("widthPx",item.getInt("widthPx"))
                .put("fontSizePx",item.getDouble("textSizePx")).put("fontWeight",item.getInt("typefaceWeight")).put("lineHeightPx",item.getDouble("textSizePx")*item.getDouble("lineHeightEm")).put("lineBreak",item.getString("mode").equals("PARAGRAPH")?"paragraph":"simple");
            JSONArray actual=PodTextLayoutServices.layout(args,new CancellationSignal()).getJSONArray("lines");
            JSONArray expected=item.getJSONArray("composeEnds");
            assertEquals("case "+i,expected.length(),actual.length());
            int previous=0;
            for(int line=0;line<actual.length();line++) {
                JSONObject value=actual.getJSONObject(line);
                assertEquals("case "+i+" line "+line,expected.getInt(line),value.getInt("end"));
                assertEquals(previous,value.getInt("start"));
                JSONObject expectedLine=item.getJSONArray("lines").getJSONObject(line);
                for(String field:new String[]{"top","bottom","baseline"})assertEquals("case "+i+" line "+line+" "+field,expectedLine.getDouble(field),value.getDouble(field+"Px"),0.001);
                assertEquals("case "+i+" line "+line+" visible end",expectedLine.getInt("visibleEnd"),value.getInt("visibleEnd"));
                assertTrue(value.getInt("visibleEnd")>=previous && value.getInt("visibleEnd")<=value.getInt("end"));
                previous=value.getInt("end");
            }
        }
    }
    @Test public void rejectsInvalidDimensionsAndHonorsCancellation() throws Exception {
        JSONObject args=new JSONObject().put("text","中文").put("widthPx",0).put("fontSizePx",28);
        try { PodTextLayoutServices.layout(args,new CancellationSignal()); fail("zero width accepted"); } catch(IllegalArgumentException expected) {}
        args.put("widthPx",200.5);
        try { PodTextLayoutServices.layout(args,new CancellationSignal()); fail("fractional width accepted"); } catch(IllegalArgumentException expected) {}
        args.put("widthPx",200);
        CancellationSignal cancel=new CancellationSignal();cancel.cancel();
        try { PodTextLayoutServices.layout(args,cancel); fail("cancel ignored"); } catch(android.os.OperationCanceledException expected) {}
    }
    @Test public void batchPreservesOrderIsolatesInvalidParagraphsAndBoundsInput() throws Exception {
        JSONObject first=new JSONObject().put("text","甲乙").put("widthPx",200).put("fontSizePx",28);
        JSONObject invalid=new JSONObject().put("text","错误").put("widthPx",0).put("fontSizePx",28);
        JSONObject last=new JSONObject().put("text","丙丁").put("widthPx",200).put("fontSizePx",28);
        JSONObject args=new JSONObject().put("paragraphs",new JSONArray().put(first).put(invalid).put(last));
        JSONArray results=PodTextLayoutServices.layoutBatch(null,args,new CancellationSignal(),null).getJSONArray("results");
        assertEquals(3,results.length());
        assertEquals(2,results.getJSONObject(0).getJSONArray("lines").getJSONObject(0).getInt("end"));
        assertTrue(results.getJSONObject(1).has("error"));
        assertEquals(2,results.getJSONObject(2).getJSONArray("lines").getJSONObject(0).getInt("end"));
        try{PodTextLayoutServices.layoutBatch(null,new JSONObject().put("paragraphs",new JSONArray()),new CancellationSignal(),null);fail("empty batch accepted");}catch(IllegalArgumentException expected){}
        JSONArray many=new JSONArray();for(int i=0;i<33;i++)many.put(first);
        try{PodTextLayoutServices.layoutBatch(null,new JSONObject().put("paragraphs",many),new CancellationSignal(),null);fail("oversized batch accepted");}catch(IllegalArgumentException expected){}
        JSONObject tooLong=new JSONObject().put("text",new String(new char[262145]).replace('\0','甲')).put("widthPx",200).put("fontSizePx",28);
        try{PodTextLayoutServices.layoutBatch(null,new JSONObject().put("paragraphs",new JSONArray().put(tooLong)),new CancellationSignal(),null);fail("oversized text accepted");}catch(IllegalArgumentException expected){}
        CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();
        try{PodTextLayoutServices.layoutBatch(null,args,cancelled,null);fail("cancelled batch accepted");}catch(android.os.OperationCanceledException expected){}
    }
}
