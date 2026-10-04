package dev.podjs.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.media.ExifInterface;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

public final class PodImageServicesTest {
    @Test public void decodesSmallFixtureToRgbaAndCleansOnClose() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File source = new File(root, "image-test.png");
        Bitmap fixture = Bitmap.createBitmap(new int[] { 0xffff0000, 0x8000ff00 }, 2, 1, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { fixture.recycle(); }

        PodImageServices images = new PodImageServices(context);
        JSONObject result = images.execute("image.decode", new JSONObject().put("path", "image-test.png").put("maxDimension", 512));
        assertEquals(2, result.getInt("width"));
        assertEquals(1, result.getInt("height"));
        File rgba = new File(root, result.getString("path"));
        byte[] bytes = Files.readAllBytes(rgba.toPath());
        assertEquals(8, bytes.length);
        assertEquals(255, bytes[0] & 0xff); assertEquals(0, bytes[1] & 0xff); assertEquals(0, bytes[2] & 0xff); assertEquals(255, bytes[3] & 0xff);
        assertEquals(0, bytes[4] & 0xff); assertEquals(255, bytes[5] & 0xff); assertEquals(0, bytes[6] & 0xff); assertEquals(128, bytes[7] & 0xff);
        images.close();
        assertFalse(rgba.exists());
        source.delete();
    }

    @Test public void rejectsInvalidFilesAndBoundsWithoutLeavingOutput() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File invalid = new File(root, "image-invalid.bin");
        Files.write(invalid.toPath(), new byte[] { 1, 2, 3, 4 });
        PodImageServices images = new PodImageServices(context);
        try {
            expectFailure(images, new JSONObject().put("path", "image-invalid.bin"), "unsupported_image");
            expectFailure(images, new JSONObject().put("path", "image-invalid.bin").put("maxDimension", 513), "invalid_max_dimension");
            expectFailure(images, new JSONObject().put("path", "../outside.png"), "path_outside_root");
        } finally {
            images.close(); invalid.delete();
        }
    }

    @Test public void nonPowerOfTwoFixtureProducesUploadableDimensions() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File source = new File(root, "image-npot-test.png");
        Bitmap fixture = Bitmap.createBitmap(3, 5, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { fixture.recycle(); }
        PodImageServices images = new PodImageServices(context);
        try {
            JSONObject result = images.execute("image.decode", new JSONObject().put("path", source.getName()).put("maxDimension", 5));
            assertEquals(4, result.getInt("width"));
            assertEquals(4, result.getInt("height"));
            assertEquals(3, result.getInt("sourceWidth"));
            assertEquals(5, result.getInt("sourceHeight"));
            assertEquals(4L * 4 * 4, new File(root, result.getString("path")).length());
        } finally { images.close(); source.delete(); }
    }

    @Test public void interruptedDecodeLeavesNoOutputBehind() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "podjs/files");
        assertTrue(root.exists() || root.mkdirs());
        File source = new File(root, "image-cancel-test.png");
        Bitmap fixture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { fixture.recycle(); }
        PodImageServices images = new PodImageServices(context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { images.execute("image.decode", new JSONObject().put("path", source.getName())); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start(); worker.join();
        assertTrue(String.valueOf(failure.get()).contains("decode_cancelled"));
        File directory = new File(root, "images");
        File[] outputs = directory.listFiles((parent, name) -> name.startsWith("decoded-") && name.endsWith(".rgba"));
        assertTrue(outputs == null || outputs.length == 0);
        images.close(); source.delete();
    }

    @Test public void regionDecodePreservesOriginalPixelsAndPadsWithoutResizing() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File root=new File(context.getFilesDir(),"podjs/files");assertTrue(root.exists()||root.mkdirs());File source=new File(root,"image-region-test.png");
        Bitmap fixture=Bitmap.createBitmap(1031,513,Bitmap.Config.ARGB_8888);for(int x=0;x<1031;x++)fixture.setPixel(x,512,0xff000000|((x&255)<<16)|0x00ab);
        try(FileOutputStream output=new FileOutputStream(source)){assertTrue(fixture.compress(Bitmap.CompressFormat.PNG,100,output));}finally{fixture.recycle();}
        PodImageServices images=new PodImageServices(context);
        try {
            JSONObject result=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",1024).put("y",512).put("width",7).put("height",1));
            assertEquals(8,result.getInt("width"));assertEquals(1,result.getInt("height"));assertEquals(7,result.getInt("contentWidth"));assertEquals(1,result.getInt("contentHeight"));assertEquals(1031,result.getInt("sourceWidth"));
            byte[] rgba=Files.readAllBytes(new File(root,result.getString("path")).toPath());assertEquals(32,rgba.length);
            for(int x=0;x<7;x++){assertEquals(x,rgba[x*4]&255);assertEquals(0,rgba[x*4+1]&255);assertEquals(171,rgba[x*4+2]&255);assertEquals(255,rgba[x*4+3]&255);}assertEquals(0,rgba[31]&255);
            JSONObject sampled=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",0).put("y",0).put("width",1024).put("height",512).put("sampleSize",2));
            assertEquals(512,sampled.getInt("contentWidth"));assertEquals(256,sampled.getInt("contentHeight"));
            images.discard(sampled);assertFalse(new File(root,sampled.getString("path")).exists());assertTrue(source.exists());
            images.discard(new JSONObject().put("path",source.getName()));assertTrue(source.exists());
            try{images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",1030).put("y",0).put("width",3).put("height",1));fail("region bounds accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("region_outside_image"));}
            try{images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",0).put("y",0).put("width",513).put("height",1));fail("unbounded region accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("invalid_region"));}
        }finally{images.close();source.delete();}
    }

    @Test public void bothImageHalvesPreserveAlternatingColumns() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File root=new File(context.getFilesDir(),"podjs/files");assertTrue(root.exists()||root.mkdirs());File source=new File(root,"image-stripes-test.png");
        Bitmap fixture=Bitmap.createBitmap(1024,512,Bitmap.Config.ARGB_8888);for(int y=0;y<512;y++)for(int x=0;x<1024;x++)fixture.setPixel(x,y,(x&1)==0?0xff000000:0xffffffff);
        try(FileOutputStream output=new FileOutputStream(source)){assertTrue(fixture.compress(Bitmap.CompressFormat.PNG,100,output));}finally{fixture.recycle();}
        PodImageServices images=new PodImageServices(context);
        try{for(int start:new int[]{0,512}){JSONObject result=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",start).put("y",0).put("width",512).put("height",512));byte[] bytes=Files.readAllBytes(new File(root,result.getString("path")).toPath());for(int x=0;x<512;x++)assertEquals("region "+start+", x="+x,(x&1)*255,bytes[x*4]&255);}}finally{images.close();source.delete();}
    }

    @Test public void regionDecoderRefreshesWhenSourceFileChanges() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(context.getFilesDir(),"podjs/files");assertTrue(root.exists()||root.mkdirs());
        File source=new File(root,"image-region-replace-test.png");
        PodImageServices images=new PodImageServices(context);
        try {
            int[] colors={0xffff0000,0xff00ff00};
            for(int index=0;index<2;index++){
                Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(colors[index]);
                try(FileOutputStream output=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
                bitmap.recycle();
                assertTrue(source.setLastModified(System.currentTimeMillis()+index*2000L));
                JSONObject region=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",0).put("y",0).put("width",8).put("height",8));
                byte[] rgba=Files.readAllBytes(new File(root,region.getString("path")).toPath());
                assertEquals((colors[index]>>>16)&255,rgba[0]&255);
                assertEquals((colors[index]>>>8)&255,rgba[1]&255);
                assertEquals(colors[index]&255,rgba[2]&255);
                images.discard(region);
            }
            java.lang.reflect.Field idleField=PodImageServices.class.getDeclaredField("idleDecoders");idleField.setAccessible(true);
            java.util.ArrayDeque<?> idle=(java.util.ArrayDeque<?>)idleField.get(images);
            assertEquals(1,idle.size());
            Object entry=idle.peekFirst();java.lang.reflect.Field decoderField=entry.getClass().getDeclaredField("decoder");decoderField.setAccessible(true);
            BitmapRegionDecoder decoder=(BitmapRegionDecoder)decoderField.get(entry);assertFalse(decoder.isRecycled());
            images.execute("image.release",new JSONObject());
            assertTrue(idle.isEmpty());assertTrue(decoder.isRecycled());
        }finally{images.close();source.delete();}
    }

    @Test public void exifOrientationAppliesToOverviewAndRegionTiles() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(context.getFilesDir(),"podjs/files");assertTrue(root.exists()||root.mkdirs());
        File source=new File(root,"image-exif-orientation-test.jpg");
        Bitmap fixture=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);
        int[] colors={0xffff0000,0xff00ff00,0xff0000ff,0xffffff00};
        for(int y=0;y<32;y++)for(int x=0;x<64;x++)fixture.setPixel(x,y,colors[(y>=16?2:0)+(x>=32?1:0)]);
        try(FileOutputStream output=new FileOutputStream(source)){assertTrue(fixture.compress(Bitmap.CompressFormat.JPEG,100,output));}finally{fixture.recycle();}
        // Expected displayed corners TL, TR, BL, BR for every EXIF transform.
        int[][] corners={{0,1,2,3},{1,0,3,2},{3,2,1,0},{2,3,0,1},{0,2,1,3},{2,0,3,1},{3,1,2,0},{1,3,0,2}};
        int[] orientations={1,2,3,4,5,6,7,8};
        org.json.JSONArray hashes=new org.json.JSONArray();
        PodImageServices images=new PodImageServices(context);
        try{
            for(int index=0;index<orientations.length;index++){
                ExifInterface exif=new ExifInterface(source.getPath());
                exif.setAttribute(ExifInterface.TAG_ORIENTATION,String.valueOf(orientations[index]));exif.saveAttributes();
                boolean rotated=orientations[index]>=5;
                int width=rotated?32:64,height=rotated?64:32;
                JSONObject full=images.execute("image.decode",new JSONObject().put("path",source.getName()).put("maxDimension",64));
                assertEquals(width,full.getInt("sourceWidth"));assertEquals(height,full.getInt("sourceHeight"));
                assertEquals(width,full.getInt("width"));assertEquals(height,full.getInt("height"));
                byte[] all=Files.readAllBytes(new File(root,full.getString("path")).toPath());
                JSONObject hashRow=new JSONObject().put("orientation",orientations[index]).put("inputSha256",sha256(Files.readAllBytes(source.toPath()))).put("rgbaSha256",sha256(all));
                hashes.put(hashRow);
                JSONObject raw=images.execute("image.decode",new JSONObject().put("path",source.getName()).put("maxDimension",64).put("applyExif",false));
                assertEquals(64,raw.getInt("sourceWidth"));assertEquals(32,raw.getInt("sourceHeight"));
                byte[] rawBytes=Files.readAllBytes(new File(root,raw.getString("path")).toPath());
                hashRow.put("rawRgbaSha256",sha256(rawBytes));
                JSONObject nativeRaw=images.execute("image.decode",new JSONObject().put("path",source.getName()).put("maxDimension",64).put("applyExif",false).put("rgb565",true));
                assertEquals(64,nativeRaw.getInt("sourceWidth"));assertEquals(32,nativeRaw.getInt("sourceHeight"));
                byte[] nativeRawBytes=Files.readAllBytes(new File(root,nativeRaw.getString("path")).toPath());
                hashRow.put("rawRgb565RgbaSha256",sha256(nativeRawBytes));
                images.discard(nativeRaw);
                for(int rawCorner=0;rawCorner<4;rawCorner++){
                    int rawX=rawCorner%2==0?16:48,rawY=rawCorner<2?8:24;
                    assertNearColor("raw orientation="+orientations[index]+" corner="+rawCorner,rawBytes,(rawY*64+rawX)*4,colors[rawCorner]);
                }
                JSONObject rawTile=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",12).put("y",4).put("width",8).put("height",8).put("applyExif",false));
                assertEquals(64,rawTile.getInt("sourceWidth"));assertEquals(32,rawTile.getInt("sourceHeight"));
                byte[] rawTileBytes=Files.readAllBytes(new File(root,rawTile.getString("path")).toPath());
                assertNearColor("raw tile orientation="+orientations[index],rawTileBytes,(4*rawTile.getInt("width")+4)*4,colors[0]);
                images.discard(rawTile);images.discard(raw);
                JSONObject rawRgbTile=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",12).put("y",4).put("width",8).put("height",8).put("applyExif",false).put("rgb565",true));
                byte[] rawRgbTileBytes=Files.readAllBytes(new File(root,rawRgbTile.getString("path")).toPath());
                assertNearColor("raw RGB565 tile orientation="+orientations[index],rawRgbTileBytes,(4*rawRgbTile.getInt("width")+4)*4,colors[0]);
                images.discard(rawRgbTile);
                for(int corner=0;corner<4;corner++){
                    int centerX=(corner%2==0?width/4:3*width/4),centerY=(corner<2?height/4:3*height/4);
                    assertNearColor("full orientation="+orientations[index]+" corner="+corner,all,(centerY*width+centerX)*4,colors[corners[index][corner]]);
                    JSONObject tile=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",centerX-4).put("y",centerY-4).put("width",8).put("height",8));
                    assertEquals(width,tile.getInt("sourceWidth"));assertEquals(height,tile.getInt("sourceHeight"));
                    assertEquals(8,tile.getInt("contentWidth"));assertEquals(8,tile.getInt("contentHeight"));
                    byte[] tileBytes=Files.readAllBytes(new File(root,tile.getString("path")).toPath());
                    assertNearColor("tile orientation="+orientations[index]+" corner="+corner,tileBytes,(4*tile.getInt("width")+4)*4,colors[corners[index][corner]]);
                    images.discard(tile);
                    JSONObject sampled=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName()).put("x",centerX-4).put("y",centerY-4).put("width",8).put("height",8).put("sampleSize",2));
                    assertEquals(4,sampled.getInt("contentWidth"));assertEquals(4,sampled.getInt("contentHeight"));
                    byte[] sampledBytes=Files.readAllBytes(new File(root,sampled.getString("path")).toPath());
                    assertNearColor("sampled orientation="+orientations[index]+" corner="+corner,sampledBytes,(2*sampled.getInt("width")+2)*4,colors[corners[index][corner]]);
                    images.discard(sampled);
                }
                images.discard(full);
            }
            Files.write(new File(context.getExternalFilesDir(null),"podjs-exif-hashes.json").toPath(),hashes.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }finally{images.close();source.delete();}
    }

    @Test public void largeImageTilesMatchNativeSourceCoordinates() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(context.getFilesDir(),"podjs/files");assertTrue(root.exists()||root.mkdirs());
        File source=new File(root,"image-large-tile-test.jpg");int width=1031,height=513;
        int[] colors=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)colors[y*width+x]=0xff000000|(((x*17+y*3)&255)<<16)|(((x*5+y*11)&255)<<8)|((x*7+y*13)&255);
        Bitmap fixture=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        fixture.setPixels(colors,0,width,0,0,width,height);
        try(FileOutputStream output=new FileOutputStream(source)){assertTrue(fixture.compress(Bitmap.CompressFormat.JPEG,95,output));}finally{fixture.recycle();}
        PodImageServices images=new PodImageServices(context);
        try {
            org.json.JSONArray tiles=new org.json.JSONArray();
            byte[] assembled=new byte[width*height*4];
            for(int y:new int[]{0,512})for(int x:new int[]{0,512,1024}){
                int tileWidth=Math.min(512,width-x),tileHeight=Math.min(512,height-y);
                JSONObject decoded=images.execute("image.decodeRegion",new JSONObject().put("path",source.getName())
                    .put("x",x).put("y",y).put("width",tileWidth).put("height",tileHeight)
                    .put("applyExif",false).put("rgb565",true));
                assertEquals(width,decoded.getInt("sourceWidth"));assertEquals(height,decoded.getInt("sourceHeight"));
                assertEquals(tileWidth,decoded.getInt("contentWidth"));assertEquals(tileHeight,decoded.getInt("contentHeight"));
                byte[] padded=Files.readAllBytes(new File(root,decoded.getString("path")).toPath());
                int stride=decoded.getInt("width")*4;byte[] content=new byte[tileWidth*tileHeight*4];
                for(int row=0;row<tileHeight;row++){
                    System.arraycopy(padded,row*stride,content,row*tileWidth*4,tileWidth*4);
                    System.arraycopy(padded,row*stride,assembled,((y+row)*width+x)*4,tileWidth*4);
                }
                tiles.put(new JSONObject().put("x",x).put("y",y).put("width",tileWidth).put("height",tileHeight).put("rgbaSha256",sha256(content)));
                images.discard(decoded);
            }
            JSONObject result=new JSONObject().put("inputSha256",sha256(Files.readAllBytes(source.toPath())))
                .put("sourceWidth",width).put("sourceHeight",height).put("tiles",tiles);
            Files.write(new File(context.getExternalFilesDir(null),"podjs-large-image-tiles.json").toPath(),result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(new File(context.getExternalFilesDir(null),"podjs-large-image.rgba").toPath(),assembled);
            BitmapFactory.Options fullOptions=new BitmapFactory.Options();fullOptions.inPreferredConfig=Bitmap.Config.RGB_565;fullOptions.inScaled=false;
            Bitmap full=BitmapFactory.decodeFile(source.getPath(),fullOptions);
            assertTrue(full!=null);
            try {
                int[] row=new int[width];byte[] expected=new byte[assembled.length];
                for(int y=0;y<height;y++){
                    full.getPixels(row,0,width,0,y,width,1);
                    for(int x=0;x<width;x++){int color=row[x],offset=(y*width+x)*4;expected[offset]=(byte)(color>>>16);expected[offset+1]=(byte)(color>>>8);expected[offset+2]=(byte)color;expected[offset+3]=(byte)(color>>>24);}
                }
                assertArrayEquals("tiled RGB565 JPEG differs from full decode",expected,assembled);
            } finally { full.recycle(); }
        }finally{images.close();source.delete();}
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();
    }

    private static void assertNearColor(String label,byte[] rgba,int offset,int expected){
        for(int channel=0;channel<3;channel++){
            int actual=rgba[offset+channel]&255;
            int wanted=(expected>>>(16-8*channel))&255;
            assertTrue(label+" channel="+channel+" expected="+wanted+" actual="+actual,Math.abs(actual-wanted)<=35);
        }
        assertEquals(label+" alpha",255,rgba[offset+3]&255);
    }

    private static void expectFailure(PodImageServices images, JSONObject args, String message) throws Exception {
        try { images.execute("image.decode", args); fail("expected " + message); }
        catch (Exception error) { assertTrue(String.valueOf(error.getMessage()).contains(message)); }
    }
}
