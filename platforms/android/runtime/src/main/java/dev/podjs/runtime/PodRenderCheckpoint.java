package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.TextureView;

/** Explicit, bounded diagnostic capture. Texture updates are latch observations,
 * not panel scan-out fences. No bitmap or text content is exported. */
final class PodRenderCheckpoint {
    private long sequence, deadline;
    private int updates;
    private boolean sampleAlpha;
    void reset(){sequence=0;deadline=0;updates=0;sampleAlpha=false;}
    boolean begin(long id, long now, boolean alpha) {
        if(id<=sequence||id<1||id>Integer.MAX_VALUE)return false;
        sequence=id;deadline=now+2_000_000_000L;updates=0;sampleAlpha=alpha;return true;
    }
    boolean update(long now) {return sequence>0&&now<=deadline&&updates++<8;}
    long sequence(){return sequence;}
    static int[] alphaCounts(Bitmap bitmap) {
        int[] counts=new int[3];
        for(int y=3;y<13;y++)for(int x=3;x<13;x++){
            int alpha=bitmap.getPixel(x,y)>>>24;
            counts[alpha==0?0:alpha==255?2:1]++;
        }
        return counts;
    }
    void textureUpdated(TextureView view, SurfaceTexture texture) {
        long now=System.nanoTime();if(!update(now))return;
        String alpha="";
        if(sampleAlpha&&updates<=2)try{
            Bitmap bitmap=view.getBitmap(16,16);
            if(bitmap!=null){int[] counts=alphaCounts(bitmap);bitmap.recycle();alpha=" transparent="+counts[0]+" partial="+counts[1]+" opaque="+counts[2];}
        }catch(RuntimeException ignored){}
        android.util.Log.i("PodRenderTiming","stage=uiTextureUpdated checkpoint="+sequence+" monoNs="+now
            +" producerNs="+texture.getTimestamp()+" update="+updates+alpha);
    }
}
