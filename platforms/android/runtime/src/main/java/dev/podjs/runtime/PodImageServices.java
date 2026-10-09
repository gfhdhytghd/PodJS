package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.system.Os;
import android.system.StructStat;
import android.system.StructTimespec;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;

/** Bounded system image decoding into tightly packed RGBA8888 files. */
public final class PodImageServices implements Closeable {
    public static final int MAX_DIMENSION = 512;
    private static final int MAX_SOURCE_DIMENSION = 65_535;
    private static final long MAX_SOURCE_PIXELS = 400_000_000L;
    private final File root;
    private final Object decoderLock = new Object();
    private final ArrayDeque<DecoderEntry> idleDecoders = new ArrayDeque<>();
    private long decoderEpoch;
    private volatile boolean closed;

    private static final class DecoderEntry {
        final String path;
        final long device, inode, size;
        final StructTimespec modified, changed;
        final BitmapRegionDecoder decoder;
        final long epoch;
        DecoderEntry(File source, StructStat stat, BitmapRegionDecoder decoder,long epoch) {
            this.path=source.getPath();this.device=stat.st_dev;this.inode=stat.st_ino;this.size=stat.st_size;
            this.modified=stat.st_mtim;this.changed=stat.st_ctim;this.decoder=decoder;this.epoch=epoch;
        }
        boolean matches(File source,StructStat stat) {
            return path.equals(source.getPath())&&device==stat.st_dev&&inode==stat.st_ino&&size==stat.st_size
                    &&modified.equals(stat.st_mtim)&&changed.equals(stat.st_ctim);
        }
    }

    private DecoderEntry borrowDecoder(File source) throws Exception {
        StructStat stat=Os.stat(source.getPath());
        long epoch;
        synchronized(decoderLock) {
            epoch=decoderEpoch;
            for(Iterator<DecoderEntry> it=idleDecoders.iterator();it.hasNext();) {
                DecoderEntry entry=it.next();it.remove();
                if(entry.matches(source,stat))return entry;
                entry.decoder.recycle();
            }
        }
        BitmapRegionDecoder decoder=BitmapRegionDecoder.newInstance(source.getPath(),false);
        if(decoder==null)throw new IOException("unsupported_image");
        return new DecoderEntry(source,stat,decoder,epoch);
    }

    private void returnDecoder(DecoderEntry entry) {
        synchronized(decoderLock) {
            if(closed||entry.epoch!=decoderEpoch||idleDecoders.size()>=4)entry.decoder.recycle();
            else idleDecoders.addLast(entry);
        }
    }

    int idleDecoderCount() {synchronized(decoderLock){return idleDecoders.size();}}

    private void releaseDecoders() {
        synchronized(decoderLock) {
            decoderEpoch++;
            while(!idleDecoders.isEmpty())idleDecoders.removeFirst().decoder.recycle();
        }
    }

    public PodImageServices(Context context) {
        if (context == null) throw new IllegalArgumentException("context_required");
        File files = PodTakeover.filesRoot(context);
        if (!files.exists() && !files.mkdirs()) throw new IllegalStateException("service_root_unavailable");
        try { root = files.getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
    }

    public JSONObject execute(String method, JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException("service_closed");
        if ("image.release".equals(method)) { releaseDecoders(); return new JSONObject(); }
        if ("image.decodeRegion".equals(method) && args != null) return decodeRegion(args);
        if (!"image.decode".equals(method) || args == null) throw new IllegalArgumentException("unsupported_method");
        return decode(args);
    }

    private JSONObject decode(JSONObject args) throws Exception {
        File source = safeFile(required(args, "path"));
        if (!source.isFile()) throw new IOException("file_not_found");
        int maximum = args.optInt("maxDimension", MAX_DIMENSION);
        if (maximum < 1 || maximum > MAX_DIMENSION) throw new IllegalArgumentException("invalid_max_dimension");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        checkInterrupted();
        int sourceWidth = bounds.outWidth, sourceHeight = bounds.outHeight;
        if (sourceWidth < 1 || sourceHeight < 1) throw new IOException("unsupported_image");
        checkSourceBounds(sourceWidth,sourceHeight,true);
        int orientation = args.optBoolean("applyExif", true) ? orientation(source) : ExifInterface.ORIENTATION_NORMAL;
        int displayedWidth = swapsAxes(orientation) ? sourceHeight : sourceWidth;
        int displayedHeight = swapsAxes(orientation) ? sourceWidth : sourceHeight;

        int sample = 1;
        // Keep the sampled bitmap below roughly 2x the requested edge, then do
        // one high-quality exact resize. The decoder only receives powers of two.
        while (sample < Math.max(sourceWidth, sourceHeight)
                && ceilDiv(Math.max(sourceWidth, sourceHeight), sample << 1) >= maximum) sample <<= 1;
        checkSourceBounds(sourceWidth,sourceHeight,sample>1);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = args.optBoolean("rgb565", false) ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap decoded = BitmapFactory.decodeFile(source.getPath(), options);
        if (decoded == null) throw new IOException("unsupported_image");

        Bitmap bitmap = orient(decoded, orientation);
        File output = null;
        try {
            checkInterrupted();
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            double scale = Math.min(1.0, Math.min((double) maximum / width, (double) maximum / height));
            int scaledWidth = Math.max(1, (int) Math.floor(width * scale));
            int scaledHeight = Math.max(1, (int) Math.floor(height * scale));
            int textureWidth = nearestPowerOfTwo(scaledWidth, maximum);
            int textureHeight = nearestPowerOfTwo(scaledHeight, maximum);
            if (width != textureWidth || height != textureHeight) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, textureWidth, textureHeight, true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            checkInterrupted();
            int finalWidth = bitmap.getWidth(), finalHeight = bitmap.getHeight();
            int[] pixels = new int[finalWidth * finalHeight];
            bitmap.getPixels(pixels, 0, finalWidth, 0, 0, finalWidth, finalHeight);
            File directory = new File(root, "images");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("parent_unavailable");
            output = new File(directory, "decoded-" + Long.toUnsignedString(System.nanoTime()) + ".rgba").getCanonicalFile();
            byte[] row = new byte[finalWidth * 4];
            try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
                for (int y = 0; y < finalHeight; y++) {
                    checkInterrupted();
                    for (int x = 0; x < finalWidth; x++) {
                        int pixel = pixels[y * finalWidth + x], offset = x * 4;
                        row[offset] = (byte) ((pixel >>> 16) & 0xff);
                        row[offset + 1] = (byte) ((pixel >>> 8) & 0xff);
                        row[offset + 2] = (byte) (pixel & 0xff);
                        row[offset + 3] = (byte) ((pixel >>> 24) & 0xff);
                    }
                    stream.write(row);
                }
            }
            checkInterrupted();
            if (closed) throw new IOException("service_closed");
            return new JSONObject().put("path", relative(output)).put("width", finalWidth).put("height", finalHeight).put("sourceWidth", displayedWidth).put("sourceHeight", displayedHeight);
        } catch (Exception error) {
            if (output != null && output.exists()) output.delete();
            throw error;
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private JSONObject decodeRegion(JSONObject args) throws Exception {
        final long traceStarted=System.nanoTime();
        File source = safeFile(required(args, "path"));
        if (!source.isFile()) throw new IOException("file_not_found");
        int x=args.optInt("x",-1), y=args.optInt("y",-1), width=args.optInt("width",0), height=args.optInt("height",0);
        int sample=args.optInt("sampleSize",1);
        if (x<0 || y<0 || width<1 || height<1 || sample<1 || sample>MAX_SOURCE_DIMENSION || (sample & (sample-1))!=0
                || (long)width> (long)MAX_DIMENSION*sample || (long)height> (long)MAX_DIMENSION*sample) {
            throw new IllegalArgumentException("invalid_region");
        }
        checkInterrupted();
        DecoderEntry decoderEntry=borrowDecoder(source);
        BitmapRegionDecoder decoder=decoderEntry.decoder;
        final long decoderReady=System.nanoTime();
        Bitmap decoded=null, oriented=null, padded=null; File output=null;
        try {
            int rawWidth=decoder.getWidth(),rawHeight=decoder.getHeight();
            checkSourceBounds(rawWidth,rawHeight,true);
            int orientation=args.optBoolean("applyExif",true)?orientation(source):ExifInterface.ORIENTATION_NORMAL;
            int sourceWidth=swapsAxes(orientation)?rawHeight:rawWidth,sourceHeight=swapsAxes(orientation)?rawWidth:rawHeight;
            if((long)x+width>sourceWidth || (long)y+height>sourceHeight) throw new IllegalArgumentException("region_outside_image");
            // JPEG region decoding can alter the outer pixel columns because chroma
            // interpolation sees the crop as an image edge. Decode a one-pixel halo
            // and crop it away so neighboring tiles agree with a full decode.
            int halo=sample==1?1:0;
            int decodeX=Math.max(0,x-halo),decodeY=Math.max(0,y-halo);
            int decodeRight=Math.min(sourceWidth,x+width+halo),decodeBottom=Math.min(sourceHeight,y+height+halo);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=args.optBoolean("rgb565",false)?Bitmap.Config.RGB_565:Bitmap.Config.ARGB_8888;options.inScaled=false;
            decoded=decoder.decodeRegion(rawRegion(decodeX,decodeY,decodeRight-decodeX,decodeBottom-decodeY,rawWidth,rawHeight,orientation),options);
            checkInterrupted();
            if(decoded==null) throw new IOException("unsupported_image");
            oriented=orient(decoded,orientation);
            if(oriented!=decoded)decoded=null;
            if(halo!=0){
                Bitmap cropped=Bitmap.createBitmap(oriented,x-decodeX,y-decodeY,width,height);
                if(cropped!=oriented)oriented.recycle();
                oriented=cropped;
            }
            final long bitmapReady=System.nanoTime();
            int contentWidth=oriented.getWidth(),contentHeight=oriented.getHeight();
            if(contentWidth<1 || contentHeight<1 || contentWidth>MAX_DIMENSION || contentHeight>MAX_DIMENSION) throw new IOException("invalid_decoded_region");
            int textureWidth=1,textureHeight=1;while(textureWidth<contentWidth)textureWidth<<=1;while(textureHeight<contentHeight)textureHeight<<=1;
            padded=Bitmap.createBitmap(textureWidth,textureHeight,Bitmap.Config.ARGB_8888);
            new Canvas(padded).drawBitmap(oriented,0,0,null);
            final long paddedReady=System.nanoTime();
            File directory=new File(root,"images");if(!directory.exists()&&!directory.mkdirs())throw new IOException("parent_unavailable");
            output=new File(directory,"decoded-"+Long.toUnsignedString(System.nanoTime())+".rgba").getCanonicalFile();
            final int rowsPerWrite=16;
            int[] pixels=new int[textureWidth*rowsPerWrite];byte[] rgba=new byte[pixels.length*4];
            try(BufferedOutputStream stream=new BufferedOutputStream(new FileOutputStream(output))){
                for(int rowIndex=0;rowIndex<textureHeight;rowIndex+=rowsPerWrite){
                    checkInterrupted();int rows=Math.min(rowsPerWrite,textureHeight-rowIndex);
                    padded.getPixels(pixels,0,textureWidth,0,rowIndex,textureWidth,rows);
                    int count=textureWidth*rows;
                    for(int column=0;column<count;column++){int pixel=pixels[column],offset=column*4;rgba[offset]=(byte)(pixel>>>16);rgba[offset+1]=(byte)(pixel>>>8);rgba[offset+2]=(byte)pixel;rgba[offset+3]=(byte)(pixel>>>24);}
                    stream.write(rgba,0,count*4);
                }
            }
            final long outputReady=System.nanoTime();
            checkInterrupted();if(closed)throw new IOException("service_closed");
            JSONObject result=new JSONObject().put("path",relative(output)).put("width",textureWidth).put("height",textureHeight)
                    .put("contentWidth",contentWidth).put("contentHeight",contentHeight).put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight)
                    .put("x",x).put("y",y).put("regionWidth",width).put("regionHeight",height).put("sampleSize",sample);
            if(args.optBoolean("trace",false))result.put("trace",new JSONObject()
                    .put("decoderOpenMs",(decoderReady-traceStarted)/1_000_000.0)
                    .put("decodeAndCropMs",(bitmapReady-decoderReady)/1_000_000.0)
                    .put("padMs",(paddedReady-bitmapReady)/1_000_000.0)
                    .put("writeRgbaMs",(outputReady-paddedReady)/1_000_000.0));
            return result;
        }catch(Exception error){if(output!=null)output.delete();throw error;}
        finally {if(decoded!=null&&!decoded.isRecycled())decoded.recycle();if(oriented!=null&&!oriented.isRecycled())oriented.recycle();if(padded!=null&&!padded.isRecycled())padded.recycle();returnDecoder(decoderEntry);}
    }

    /** Sampled overviews and bounded regions may use large sources; full decodes retain the original guard. */
    static void checkSourceBounds(int width,int height,boolean boundedOutput) throws IOException {
        int edge=boundedOutput?MAX_SOURCE_DIMENSION:16_384;
        long pixels=boundedOutput?MAX_SOURCE_PIXELS:64L*1024*1024;
        if(width<1||height<1||width>edge||height>edge||(long)width*height>pixels)throw new IOException("image_too_large");
    }

    private static int orientation(File source) {
        try { return new ExifInterface(source.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL); }
        catch (IOException ignored) { return ExifInterface.ORIENTATION_NORMAL; }
    }
    private static boolean swapsAxes(int orientation) {
        return orientation>=ExifInterface.ORIENTATION_TRANSPOSE && orientation<=ExifInterface.ORIENTATION_ROTATE_270;
    }
    private static Bitmap orient(Bitmap raw,int orientation) {
        if(orientation<=ExifInterface.ORIENTATION_NORMAL || orientation>ExifInterface.ORIENTATION_ROTATE_270)return raw;
        Matrix matrix=new Matrix();
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setValues(new float[]{0,1,0,1,0,0,0,0,1});break;
            case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setValues(new float[]{0,-1,0,-1,0,0,0,0,1});break;
            case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;
            default:return raw;
        }
        try {
            Bitmap rotated=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),matrix,true);
            if(rotated!=raw)raw.recycle();
            return rotated;
        } catch(RuntimeException error){raw.recycle();throw error;}
    }
    private static Rect rawRegion(int x,int y,int width,int height,int rawWidth,int rawHeight,int orientation){
        int right=x+width,bottom=y+height;
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:return new Rect(rawWidth-right,y,rawWidth-x,bottom);
            case ExifInterface.ORIENTATION_ROTATE_180:return new Rect(rawWidth-right,rawHeight-bottom,rawWidth-x,rawHeight-y);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:return new Rect(x,rawHeight-bottom,right,rawHeight-y);
            case ExifInterface.ORIENTATION_TRANSPOSE:return new Rect(y,x,bottom,right);
            case ExifInterface.ORIENTATION_ROTATE_90:return new Rect(y,rawHeight-right,bottom,rawHeight-x);
            case ExifInterface.ORIENTATION_TRANSVERSE:return new Rect(rawWidth-bottom,rawHeight-right,rawWidth-y,rawHeight-x);
            case ExifInterface.ORIENTATION_ROTATE_270:return new Rect(rawWidth-bottom,x,rawWidth-y,right);
            default:return new Rect(x,y,right,bottom);
        }
    }

    private File safeFile(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid_path");
        File file = new File(root, path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator)) throw new SecurityException("path_outside_root");
        return file;
    }

    private String relative(File file) throws IOException { return root.toPath().relativize(file.getCanonicalFile().toPath()).toString(); }
    private static String required(JSONObject args, String key) { String value = args.optString(key, null); if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing_" + key); return value; }
    private static int ceilDiv(int value, int divisor) { return (value + divisor - 1) / divisor; }
    private static int nearestPowerOfTwo(int value, int maximum) {
        int lower = 1;
        while (lower <= maximum / 2 && lower << 1 <= value) lower <<= 1;
        int upper = lower <= maximum / 2 ? lower << 1 : lower;
        return value - lower < upper - value ? lower : upper;
    }
    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("decode_cancelled");
    }

    /** Release an output when cancellation won the race with reply delivery. */
    void discard(JSONObject result) {
        try {
            File output = safeFile(result.getString("path"));
            if (output.getParentFile().equals(new File(root, "images")) && output.getName().startsWith("decoded-") && output.getName().endsWith(".rgba")) output.delete();
        } catch (Exception ignored) { }
    }

    @Override public void close() {
        closed = true;
        releaseDecoders();
        File directory = new File(root, "images");
        File[] temporary = directory.listFiles((parent, name) -> name.startsWith("decoded-") && name.endsWith(".rgba"));
        if (temporary != null) {
            for (File output : temporary) if (output.isFile()) output.delete();
        }
    }
}
