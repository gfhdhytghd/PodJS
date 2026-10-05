package dev.podjs.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Debug-only, foreground PCM owner. Permission UI is coordinated on main by
 * PodServices; all blocking PCM calls run on its bounded acoustic executor. */
final class PodAcousticServices implements AutoCloseable {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final PodPcmSession pcm;
    private volatile boolean foreground=true,focused=true,closed;
    private Runnable watchdog;private long watchdogSession;
    PodAcousticServices(Context context){
        this.context=context;audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setOnAudioFocusChangeListener(change->{if(change<0)cancel();},main).build();
        pcm=new PodPcmSession(this::openDevice);
    }
    void requireDebug(){if((context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0)throw new SecurityException("Acoustic features require debug build");}
    void requireActive(){requireDebug();if(closed||!foreground||!focused)throw new SecurityException("Acoustic features require foreground focus");}
    void lifecycle(boolean active){foreground=active;if(!active)cancel();}
    void focus(boolean active){focused=active;if(!active)cancel();}
    private boolean microphone(){return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    Object execute(String method,JSONObject args) throws Exception {
        requireDebug();
        if("acoustic.pcm.stop".equals(method)){stop(args.getLong("session"));return JSONObject.NULL;}
        requireActive();
        if("acoustic.gzip".equals(method)||"acoustic.gunzip".equals(method)) {
            String text=args.getString("dataBase64");if(text.length()>87384)throw new IllegalArgumentException("Acoustic payload limit");
            byte[] bytes=Base64.decode(text,Base64.DEFAULT);if(bytes.length>65536)throw new IllegalArgumentException("Acoustic payload limit");
            byte[] result="acoustic.gzip".equals(method)?PodCloudCrypto.gzip(bytes):PodCloudCrypto.gunzip(bytes,Math.min(65536,args.optInt("maxBytes",65536)));
            if(result.length>65536)throw new IllegalArgumentException("Acoustic payload limit");
            return new JSONObject().put("dataBase64",Base64.encodeToString(result,Base64.NO_WRAP));
        }
        if("acoustic.pcm.open".equals(method)){
            String mode=args.getString("mode");if(!mode.equals("record")&&!mode.equals("play"))throw new IllegalArgumentException("PCM mode");
            boolean record=mode.equals("record");long id=pcm.open(record,true,true,microphone());
            // Watchdog is independent of guest pulls and guest lifecycle delivery.
            synchronized(this){if(watchdog!=null)main.removeCallbacks(watchdog);watchdogSession=id;watchdog=()->stop(id);main.postDelayed(watchdog,record?120000:600000);}
            return new JSONObject().put("session",id).put("sampleRate",PodPcmSession.SAMPLE_RATE).put("chunkSamples",PodPcmSession.CHUNK_SAMPLES);
        }
        long id=args.getLong("session");
        if("acoustic.pcm.read".equals(method)){
            if(!microphone()){pcm.stop(id);throw new SecurityException("Microphone permission revoked");}
            short[] samples=pcm.read(id);ByteBuffer bytes=ByteBuffer.allocate(samples.length*2).order(ByteOrder.LITTLE_ENDIAN);for(short s:samples)bytes.putShort(s);
            return new JSONObject().put("pcm16Base64",Base64.encodeToString(bytes.array(),Base64.NO_WRAP));
        }
        if("acoustic.pcm.write".equals(method)){
            String encoded=args.getString("pcm16Base64");if(encoded.length()>10924)throw new IllegalArgumentException("PCM chunk limit");
            byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);if(bytes.length%2!=0||bytes.length>8192)throw new IllegalArgumentException("PCM16 chunk required");
            short[] samples=new short[bytes.length/2];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            return new JSONObject().put("samples",pcm.write(id,samples));
        }
        if("acoustic.pcm.drain".equals(method)){
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(!pcm.drained(id)){requireActive();if(SystemClock.elapsedRealtime()>=deadline){pcm.stop(id);throw new IllegalStateException("PCM drain timeout");}Thread.sleep(20);}
            pcm.stop(id);return JSONObject.NULL;
        }
        throw new IllegalArgumentException("Unknown PCM operation");
    }
    private PodPcmSession.Device openDevice(boolean record) throws Exception {
        requireActive();
        if(record){
            if(!microphone())throw new SecurityException("Microphone permission required");
            int min=AudioRecord.getMinBufferSize(22050,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("Microphone format unsupported");
            AudioRecord value=new AudioRecord(MediaRecorder.AudioSource.MIC,22050,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192));
            try{if(value.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");value.startRecording();}
            catch(Exception e){value.release();throw e;}
            return new PodPcmSession.Device(){public int read(short[] b){return value.read(b,0,b.length);}public int write(short[] b){throw new IllegalStateException();}public long played(){return 0;}public void close(){try{value.stop();}catch(Exception ignored){}finally{value.release();}}};
        }
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("Audio focus denied");
        AudioTrack value=null;
        try{
            int min=AudioTrack.getMinBufferSize(22050,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("PCM playback unsupported");
            value=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),new AudioFormat.Builder().setSampleRate(22050).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),Math.max(min,8192),AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            if(value.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("PCM player initialization failed");value.play();
            final AudioTrack track=value;
            return new PodPcmSession.Device(){public int read(short[] b){throw new IllegalStateException();}public int write(short[] b){return track.write(b,0,b.length,AudioTrack.WRITE_BLOCKING);}public long played(){return Integer.toUnsignedLong(track.getPlaybackHeadPosition());}public void close(){try{track.stop();}catch(Exception ignored){}finally{track.release();audio.abandonAudioFocusRequest(focus);}}};
        }catch(Exception e){if(value!=null)value.release();audio.abandonAudioFocusRequest(focus);throw e;}
    }
    private void stop(long id){pcm.stop(id);synchronized(this){if(watchdogSession==id&&watchdog!=null){main.removeCallbacks(watchdog);watchdog=null;}}}
    void cancel(){pcm.cancel();synchronized(this){if(watchdog!=null){main.removeCallbacks(watchdog);watchdog=null;}}}
    public void close(){closed=true;pcm.close();main.removeCallbacksAndMessages(null);}
}
