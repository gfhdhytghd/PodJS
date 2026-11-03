package dev.podjs.runtime;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Copies encoded DASH tracks without transcoding or retaining whole media in RAM. */
final class PodMediaLocalMuxer {
    private static final int SAMPLE_LIMIT=2*1024*1024;
    private static final class Input implements AutoCloseable {
        final MediaExtractor extractor=new MediaExtractor();final MediaFormat format;int track;
        Input(File file,String type)throws Exception {
            try{extractor.setDataSource(file.getAbsolutePath());int found=-1;
                for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith(type)){found=i;break;}}
                if(found<0)throw new IOException(type.equals("audio/")?"complete_audio_track_missing":"complete_video_track_missing");
                format=extractor.getTrackFormat(found);extractor.selectTrack(found);
            }catch(Exception failure){extractor.release();throw failure;}
        }
        long time(){return extractor.getSampleTime();}
        boolean hasSample(){return extractor.getSampleTrackIndex()>=0;}
        public void close(){extractor.release();}
    }
    static void combine(PodMediaPrefixCache.Job job,File video,File audio,File output)throws Exception {
        output.delete();boolean complete=false;
        try(Input v=new Input(video,"video/");Input a=new Input(audio,"audio/")){
            MediaMuxer muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);boolean started=false;
            try{
                v.track=muxer.addTrack(v.format);a.track=muxer.addTrack(a.format);muxer.start();started=true;
                ByteBuffer bytes=ByteBuffer.allocateDirect(SAMPLE_LIMIT);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                // AAC priming samples can have negative timestamps. Only the track index
                // identifies EOS; shift both tracks by the same origin to preserve sync.
                long origin=Math.min(0,Math.min(v.hasSample()?v.time():0,a.hasSample()?a.time():0));
                while(v.hasSample()||a.hasSample()){
                    PodMediaPrefixCache.backgroundTurn(job);
                    Input in=!a.hasSample()||v.hasSample()&&v.time()<=a.time()?v:a;
                    long size=in.extractor.getSampleSize();if(size<0||size>SAMPLE_LIMIT)throw new IOException("complete_sample_over_budget");
                    bytes.clear();int n=in.extractor.readSampleData(bytes,0);if(n<0||n!=size)throw new IOException("complete_mux_failed");
                    long timestamp=Math.subtractExact(in.time(),origin);if(timestamp<0)throw new IOException("complete_mux_failed");
                    info.set(0,n,timestamp,(in.extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                    muxer.writeSampleData(in.track,bytes,info);in.extractor.advance();
                }
                muxer.stop();started=false;complete=true;
            }finally{try{if(started)muxer.stop();}finally{muxer.release();}}
        }finally{if(!complete)output.delete();}
    }
}
