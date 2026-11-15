package dev.podjs.runtime;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.function.IntSupplier;
import androidx.media3.decoder.CryptoInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;

/** Counts each actual Media3 video codec, including replacement codecs. */
final class PodVideoCodecAdapters implements MediaCodecAdapter.Factory {
    private final MediaCodecAdapter.Factory delegate;
    private final String owner;
    private final IntSupplier priority;
    PodVideoCodecAdapters(MediaCodecAdapter.Factory delegate,String owner,IntSupplier priority){this.delegate=delegate;this.owner=owner;this.priority=priority;}
    public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration config)throws java.io.IOException {
        String mime=config.mediaFormat.getString(MediaFormat.KEY_MIME);
        if(mime==null||!mime.startsWith("video/"))return delegate.createAdapter(config);
        PodVideoCodecGate.Permit permit=PodVideoCodecGate.PROCESS.await(owner,priority.getAsInt());
        try{return new Counted(delegate.createAdapter(config),permit);}
        catch(java.io.IOException|RuntimeException|Error e){permit.close();throw e;}
    }
    private static final class Counted implements MediaCodecAdapter {
        final MediaCodecAdapter codec;final PodVideoCodecGate.Permit permit;
        Counted(MediaCodecAdapter codec,PodVideoCodecGate.Permit permit){this.codec=codec;this.permit=permit;}
        public int dequeueInputBufferIndex(){return codec.dequeueInputBufferIndex();}
        public int dequeueOutputBufferIndex(MediaCodec.BufferInfo info){return codec.dequeueOutputBufferIndex(info);}
        public MediaFormat getOutputFormat(){return codec.getOutputFormat();}
        public ByteBuffer getInputBuffer(int i){return codec.getInputBuffer(i);}
        public ByteBuffer getOutputBuffer(int i){return codec.getOutputBuffer(i);}
        public void queueInputBuffer(int i,int offset,int size,long time,int flags){codec.queueInputBuffer(i,offset,size,time,flags);}
        public void queueSecureInputBuffer(int i,int offset,CryptoInfo crypto,long time,int flags){codec.queueSecureInputBuffer(i,offset,crypto,time,flags);}
        public void releaseOutputBuffer(int i,boolean render){codec.releaseOutputBuffer(i,render);}
        public void releaseOutputBuffer(int i,long time){codec.releaseOutputBuffer(i,time);}
        public void flush(){codec.flush();}
        public void release(){codec.release();permit.close();}
        public void setOnFrameRenderedListener(OnFrameRenderedListener listener,Handler handler){codec.setOnFrameRenderedListener((adapter,time,nano)->listener.onFrameRendered(this,time,nano),handler);}
        public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener){return codec.registerOnBufferAvailableListener(listener);}
        public void setOutputSurface(Surface surface){codec.setOutputSurface(surface);}
        public void detachOutputSurface(){codec.detachOutputSurface();}
        public void setParameters(Bundle parameters){codec.setParameters(parameters);}
        public void setVideoScalingMode(int mode){codec.setVideoScalingMode(mode);}
        public boolean needsReconfiguration(){return codec.needsReconfiguration();}
        public PersistableBundle getMetrics(){return codec.getMetrics();}
    }
}
