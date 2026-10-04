package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PodFontServicesTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getContext();}
    private static float width(Typeface face){Paint paint=new Paint();paint.setTypeface(face);paint.setTextSize(28);return paint.measureText("Watch font 中文");}
    @Test public void packagedTypefaceMatchesDirectBuilderAndRetainsStyleParameters()throws Exception{
        Context context=context();String name="fonts/noto-test.ttf";
        JSONObject options=new JSONObject().put("fontAsset",name).put("fontFaceIndex",0).put("variationSettings","'wght' 650");
        Typeface expected=new Typeface.Builder(context.getAssets(),"pod-assets/"+name).setTtcIndex(0).setWeight(650).setItalic(true).setFontVariationSettings("'wght' 650").build();
        assertNotNull("Test font did not load",expected);
        Typeface actual=PodFontServices.resolve(context,options,650,true);
        assertNotEquals("Packaged font unexpectedly fell back",Typeface.create(Typeface.DEFAULT,650,true),actual);
        assertEquals(expected.getWeight(),actual.getWeight());assertEquals(expected.isItalic(),actual.isItalic());assertEquals(width(expected),width(actual),0.001f);
        assertSame(actual,PodFontServices.resolve(context,options,650,true));
    }
    @Test public void missingBadAssetsAndInvalidVariationUseTheExistingSystemFallback()throws Exception{
        Context context=context();Typeface fallback=Typeface.create(Typeface.DEFAULT,500,true);
        for(JSONObject options:new JSONObject[]{new JSONObject().put("fontAsset","fonts/missing.ttf"),new JSONObject().put("fontAsset","fonts/not-a-font.ttf"),new JSONObject().put("fontAsset","fonts/noto-test.ttf").put("variationSettings","invalid axes")})
            assertEquals(fallback,PodFontServices.resolve(context,options,500,true));
    }
    @Test public void fileFontsRemainCompatibleAndCannotBeCombinedWithAssets()throws Exception{
        // Instrumentation executes under the target UID. Its APK assets are
        // readable, but its separate application files directory is not.
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(context.getFilesDir(),"podjs/files/font-tests");assertTrue(directory.isDirectory()||directory.mkdirs());
        File file=Files.createTempFile(directory.toPath(),"font-",".ttf").toFile();
        try{
            try(InputStream source=context().getAssets().open("pod-assets/fonts/noto-test.ttf")){Files.copy(source,file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            String relative="font-tests/"+file.getName();JSONObject options=new JSONObject().put("fontPath",relative).put("fontFaceIndex",0).put("variationSettings","'wght' 450");
            Typeface expected=new Typeface.Builder(file).setTtcIndex(0).setWeight(450).setItalic(false).setFontVariationSettings("'wght' 450").build();
            assertNotNull(expected);assertEquals(width(expected),width(PodFontServices.resolve(context,options,450,false)),0.001f);
            for(JSONObject bad:new JSONObject[]{new JSONObject().put("fontPath",relative).put("fontAsset","fonts/noto-test.ttf"),new JSONObject().put("fontAsset","../noto-test.ttf"),new JSONObject().put("fontAsset",2),new JSONObject().put("fontAsset","fonts/noto-test.ttf").put("fontFaceIndex",0.5)}){
                try{PodFontServices.resolve(context,bad,400,false);fail("Invalid font request accepted");}catch(IllegalArgumentException expectedFailure){}
            }
        }finally{Files.deleteIfExists(file.toPath());}
    }
}
