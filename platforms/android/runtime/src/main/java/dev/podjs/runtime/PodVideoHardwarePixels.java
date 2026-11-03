package dev.podjs.runtime;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.*;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Worker-only ImageReader / HardwareBuffer conversion. No PixelCopy or player. */
final class PodVideoHardwarePixels {
    static final class Unavailable extends Exception { Unavailable(Exception cause){super(cause);} }
    private static final Map<String,String> decoders=new HashMap<>();
    static synchronized String decoder(String mime,int width,int height){
        String key=mime+":"+width+"x"+height;
        if(decoders.containsKey(key))return decoders.get(key);
        String name=null;
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder()||!info.isHardwareAccelerated())continue;
            try{if(info.getCapabilitiesForType(mime).getVideoCapabilities().isSizeSupported(width,height)){name=info.getName();break;}}
            catch(IllegalArgumentException unsupported){}
        }
        if(decoders.size()>=32)decoders.clear();decoders.put(key,name);return name;
    }
    static Bitmap bitmap(Image image)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("keyframe_hardware_on_main");
        try(HardwareBuffer buffer=image.getHardwareBuffer()){
            Bitmap wrapped=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
            if(wrapped==null)throw new IOException("keyframe_hardware_bitmap");
            try{return render(wrapped);}finally{wrapped.recycle();}
        }
    }
    private static Bitmap render(Bitmap source)throws Exception {
        double scale=Math.min(1,PodVideoKeyframes.MAX_EDGE/(double)Math.max(source.getWidth(),source.getHeight()));
        int w=Math.max(1,(int)Math.round(source.getWidth()*scale)),h=Math.max(1,(int)Math.round(source.getHeight()*scale));
        ImageReader target=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2,HardwareBuffer.USAGE_CPU_READ_OFTEN|HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareRenderer renderer=new HardwareRenderer();RenderNode node=new RenderNode("PodKeyframe");
        try{
            node.setPosition(0,0,w,h);RecordingCanvas canvas=node.beginRecording();
            try{canvas.drawBitmap(source,null,new Rect(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG));}finally{node.endRecording();}
            renderer.setSurface(target.getSurface());renderer.setContentRoot(node);
            if(renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()!=HardwareRenderer.SYNC_OK)throw new IOException("keyframe_render_failed");
            Image image=null;long end=SystemClock.uptimeMillis()+500;
            while(SystemClock.uptimeMillis()<end&&(image=target.acquireNextImage())==null)Thread.sleep(1);
            if(image==null)throw new IOException("keyframe_render_deadline");
            try{
                Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
                if(plane.getPixelStride()!=4)throw new IOException("keyframe_rgba_stride");
                byte[] rgba=new byte[w*h*4];int start=buffer.position();
                for(int y=0;y<h;y++){buffer.position(start+y*plane.getRowStride());buffer.get(rgba,y*w*4,w*4);}
                Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                result.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));return result;
            }finally{image.close();}
        }finally{renderer.destroy();node.discardDisplayList();target.close();}
    }
}
