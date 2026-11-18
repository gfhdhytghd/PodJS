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
    private final Object identity;private final java.util.function.LongSupplier generation;
    PodVideoCodecAdapters(MediaCodecAdapter.Factory delegate,String owner,IntSupplier priority){this(delegate,owner,owner,()->0,priority);}
    PodVideoCodecAdapters(MediaCodecAdapter.Factory delegate,String purpose,Object identity,java.util.function.LongSupplier generation,IntSupplier priority){this.delegate=delegate;this.owner=purpose;this.identity=identity;this.generation=generation;this.priority=priority;}
    public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration config)throws java.io.IOException {
        String mime=config.mediaFormat.getString(MediaFormat.KEY_MIME);
        if(mime==null||!mime.startsWith("video/")){
            AutoCloseable charge=PodMediaMemoryAccounting.reserveResource(identity,generation.getAsLong(),priority.getAsInt(),PodMediaMemoryBudget.Kind.AUDIO_CODEC,1_000_000L);
            if(charge==null)throw new PodVideoCodecGate.ByteAdmissionDenied(priority.getAsInt());
            try{MediaCodecAdapter codec=delegate.createAdapter(config);PodMediaMemoryAccounting.commitResource(charge);return new Counted(codec,null,charge);}
            catch(java.io.IOException|RuntimeException|Error error){PodMediaMemoryAccounting.close(charge);throw error;}
        }
        PodVideoCodecGate.Permit permit=PodVideoCodecGate.PROCESS.await(identity,generation.getAsLong(),owner,priority.getAsInt(),estimatedVideoBytes(config.mediaFormat));
        try{MediaCodecAdapter codec=delegate.createAdapter(config);permit.created();return new Counted(codec,permit,null);}
        catch(java.io.IOException|RuntimeException e){permit.close();e.addSuppressed(new PodVideoCodecGate.FactoryFailure());throw e;}
        catch(Error e){permit.close();throw e;}
    }
    static long estimatedVideoBytes(MediaFormat format){
        int width=format.containsKey(MediaFormat.KEY_WIDTH)?format.getInteger(MediaFormat.KEY_WIDTH):0;
        int height=format.containsKey(MediaFormat.KEY_HEIGHT)?format.getInteger(MediaFormat.KEY_HEIGHT):0;
        if(width<=0||height<=0)return 6_000_000L;
        // Eight aligned YUV420 output buffers plus fixed codec overhead. Surface
        // queues, compressed samples and audio retain separate reservations.
        long w=((long)width+63)&~63L,h=((long)height+63)&~63L;
        try{return Math.addExact(2_000_000L,Math.multiplyExact(Math.multiplyExact(w,h),12));}catch(ArithmeticException overflow){return Long.MAX_VALUE;}
    }
    private static final class Counted implements MediaCodecAdapter {
        final MediaCodecAdapter codec;final PodVideoCodecGate.Permit permit;final AutoCloseable charge;private boolean released;
        Counted(MediaCodecAdapter codec,PodVideoCodecGate.Permit permit,AutoCloseable charge){this.codec=codec;this.permit=permit;this.charge=charge;}
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
        public synchronized void release(){if(released)return;codec.release();released=true;if(permit!=null)permit.close();PodMediaMemoryAccounting.close(charge);}
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
