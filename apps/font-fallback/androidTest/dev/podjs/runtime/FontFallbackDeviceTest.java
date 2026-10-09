package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs only inside the isolated font fixture; never opens another app's data. */
@RunWith(AndroidJUnit4.class)
public class FontFallbackDeviceTest {
    private static final int[] CPS={0x6c17,0x2460,0x1f600,0x3400,0x20bb7};
    private static final String[] SEQUENCES={"👨‍👩‍👧‍👦","👍🏽","❤️"};
    private static Paint paint(float px,boolean bold,int density) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
        p.setTypeface(Typeface.create(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL));p.setTextSize(px*density);p.setColor(Color.WHITE);return p;
    }
    private static void png(Bitmap bitmap,File file)throws Exception {
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
    }
    private static void compareRegion(Bitmap actual,Bitmap expected,int x,int y,int w,int h,JSONArray regions)throws Exception {
        long error=0;int changed=0;
        for(int dy=0;dy<h;dy++)for(int dx=0;dx<w;dx++) {
            int a=actual.getPixel(x+dx,y+dy),b=expected.getPixel(x+dx,y+dy);
            for(int shift:new int[]{0,8,16}) {int delta=Math.abs(((a>>>shift)&255)-((b>>>shift)&255));error+=delta;if(delta!=0)changed++;}
        }
        regions.put(new JSONObject().put("x",x).put("y",y).put("w",w).put("h",h).put("mae",error/(w*h*3.0)).put("changedChannels",changed));
        assertEquals("Actual renderer differs from native Canvas at "+x+","+y,0,error);
    }
    @Test public void missingGlyphPixelsAndMetricsMatchNativeAndCaptureRealUi()throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("dev.podjs.fontfallback.rehearsal",target.getPackageName());
        String metadata;
        try(java.io.InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("font-metrics.json")) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);metadata=bytes.toString("UTF-8");
        }
        JSONObject slots=new JSONObject(metadata).getJSONObject("slots"),report=new JSONObject();JSONArray glyphs=new JSONArray();
        File directory=new File(target.getExternalFilesDir(null),"t19-font-fallback");assertTrue(directory.mkdirs()||directory.isDirectory());
        Bitmap nativeGrid=Bitmap.createBitmap(466,466,Bitmap.Config.ARGB_8888);nativeGrid.eraseColor(Color.BLACK);Canvas grid=new Canvas(nativeGrid);
        for(int slot:new int[]{2,9}) {
            JSONObject m=slots.getJSONObject(Integer.toString(slot));int d=m.getInt("density"),base=m.getInt("baseline");
            float px=(float)m.getDouble("px");int extent=(int)Math.ceil(px*2+4),w=Math.max(m.getInt("w"),extent),h=Math.max(m.getInt("h"),extent);
            Paint p=paint(px,slot==9,d);
            String[] samples=new String[CPS.length+SEQUENCES.length];
            for(int i=0;i<CPS.length;i++)samples[i]=new String(Character.toChars(CPS[i]));
            System.arraycopy(SEQUENCES,0,samples,CPS.length,SEQUENCES.length);
            for(int i=0;i<samples.length;i++) {
                String text=samples[i];assertTrue("System font has no glyph for "+text,p.hasGlyph(text));
                byte[] result=PodSystemFontFallback.raster(text,px,slot==9?1:0,w,h,base,d);assertNotNull(result);
                ByteBuffer b=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);float advance=b.getFloat(),xoff=b.getFloat(),yoff=b.getFloat();
                assertEquals(p.measureText(text)/d,advance,0);
                int margin=32,bw=w*d+margin*2,bh=h*d+margin*2;Bitmap nativeCell=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);
                new Canvas(nativeCell).drawText(text,margin,margin+base*d,p);
                int[] expected=new int[bw*bh];nativeCell.getPixels(expected,0,bw,0,0,bw,bh);
                int[] actual=new int[bw*bh];int colored=0,ink=0;
                for(int y=0;y<h*d;y++)for(int x=0;x<w*d;x++) {
                    int r=b.get()&255,g=b.get()&255,blue=b.get()&255,a=b.get()&255;
                    int dx=margin-(int)(xoff*d)+x,dy=margin-(int)(yoff*d)+y;
                    if(dx>=0&&dy>=0&&dx<bw&&dy<bh)actual[dy*bw+dx]=a==0?0:(a<<24)|(r<<16)|(g<<8)|blue;
                    if(a!=0){ink++;if(r!=g||g!=blue)colored++;}
                }
                assertTrue("Empty raster for "+text,ink>0);assertArrayEquals("Uncropped native pixels for "+text,expected,actual);
                int nativeColored=0;
                for(int argb:expected)if((argb>>>24)!=0&&(((argb>>>16)&255)!=((argb>>>8)&255)||((argb>>>8)&255)!=(argb&255)))nativeColored++;
                assertEquals("Retain native color for "+text,nativeColored,colored);
                android.util.Log.i("T19Font","slot="+slot+" text="+text+" ink="+ink+" colored="+colored+" nativePixelEqual=true");
                glyphs.put(new JSONObject().put("slot",slot).put("text",text).put("advance",advance).put("ink",ink).put("colored",colored).put("pixelEqual",true));
                if(i<CPS.length) {
                    float top=slot==2?104:156;
                    float baseline=(top+(24-m.getInt("h"))/2f+base)*d;
                    grid.drawText(text,(40+i*32)*d,baseline,p);
                } else if(slot==2) {
                    float baseline=(184+(24-m.getInt("h"))/2f+base)*d;
                    grid.drawText(text,(52+(i-CPS.length)*56)*d,baseline,p);
                }
                nativeCell.recycle();
            }
        }
        png(nativeGrid,new File(directory,"native-grid.png"));
        Intent launch=target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());assertNotNull(launch);
        target.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(2500);
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
        png(screenshot,new File(directory,"podjs.png"));
        // Compare the actual Vulkan output, independently of the host raster test.
        // These rectangles avoid title/labels and remain inside the round screen.
        JSONArray regions=new JSONArray();
        for(int row:new int[]{208,312})for(int i=0;i<5;i++)compareRegion(screenshot,nativeGrid,80+i*64,row,48,50,regions);
        for(int i=0;i<3;i++)compareRegion(screenshot,nativeGrid,104+i*112,366,48,54,regions);
        report.put("renderRegions",regions);screenshot.recycle();nativeGrid.recycle();
        report.put("glyphs",glyphs).put("sdk",android.os.Build.VERSION.SDK_INT).put("device",android.os.Build.MODEL);
        try(FileOutputStream output=new FileOutputStream(new File(directory,"native-proof.json"))) {output.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
