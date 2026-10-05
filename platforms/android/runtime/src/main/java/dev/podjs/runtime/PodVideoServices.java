package dev.podjs.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Build;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.AudioManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** One bounded Media3 video session. All player and view operations run on main. */
public final class PodVideoServices implements AutoCloseable {
    public interface Callback { void complete(JSONObject value); void fail(String code, String message); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File mediaRoot;
    private final PodVideoDecoderBudget decoderBudget;
    private boolean decoderLease;
    private ExoPlayer player;
    private TextureView videoView;
    private View videoBackground;
    private ViewGroup parent;
    private ImageView backdrop;
    private Bitmap backdropBitmap;
    private float volume = 1f;
    private float rotationDeg;
    private String scaleMode = "standard";
    private float panX, panY;
    private Callback prepareCallback;
    private String state = "idle";
    private boolean closed;
    private boolean autoplay;
    private boolean backgroundPlayback, lifecyclePaused;
    private ServiceConnection connection;
    private Player.Listener playerListener;
    private float density;

    public PodVideoServices(Context context) {
        this(context, new PodVideoDecoderBudget(PodVideoSessionServices.MAX_SESSIONS));
    }

    PodVideoServices(Context context, PodVideoDecoderBudget decoderBudget) {
        if (context == null) throw new IllegalArgumentException("context_required");
        this.context = context.getApplicationContext();
        this.decoderBudget = decoderBudget;
        try { mediaRoot = PodTakeover.filesRoot(this.context).getCanonicalFile(); }
        catch (IOException e) { throw new IllegalStateException("service_root_unavailable", e); }
        density = this.context.getResources().getDisplayMetrics().density;
    }

    /** Installs a non-interactive video texture below the transparent PodJS controls. */
    public void attachToParent(ViewGroup host) {
        main.post(() -> {
            if (closed || host == null) return;
            parent = host;
            if (videoView == null) {
                videoView = new TextureView(context) {
                    @Override public boolean onTouchEvent(android.view.MotionEvent event) { return false; }
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) { return false; }
                };
                videoView.setClickable(false); videoView.setFocusable(false); videoView.setVisibility(View.GONE);
                int podIndex=host.getChildCount(); for(int i=0;i<host.getChildCount();i++) if(host.getChildAt(i) instanceof PodRuntimeView){podIndex=i;break;}
                videoBackground = new View(context); videoBackground.setBackgroundColor(android.graphics.Color.BLACK); videoBackground.setVisibility(View.GONE);
                host.addView(videoBackground, podIndex, new FrameLayout.LayoutParams(1, 1));
                host.addView(videoView, podIndex + 1, new FrameLayout.LayoutParams(1, 1));
            } else if (videoView.getParent() != host) {
                if (videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
                if (videoBackground.getParent() instanceof ViewGroup) ((ViewGroup) videoBackground.getParent()).removeView(videoBackground);
                int podIndex=host.getChildCount(); for(int i=0;i<host.getChildCount();i++) if(host.getChildAt(i) instanceof PodRuntimeView){podIndex=i;break;}
                host.addView(videoBackground,podIndex); host.addView(videoView,podIndex+1);
            }
        });
    }

    public void dispatch(String method, JSONObject args, Callback callback) {
        main.post(() -> {
            if (closed) { callback.fail("service_closed", "Video service is closed"); return; }
            try {
                switch (method) {
                    case "video.prepare": prepare(args, callback); break;
                    case "video.play": play(callback); break;
                    case "video.pause": pause(callback); break;
                    case "video.seek": seek(args, callback); break;
                    case "video.stop": stop(callback); break;
                    case "video.setBounds": bounds(args, callback); break;
                    case "video.captureBackdrop": captureBackdrop(callback); break;
                    case "video.setTransform": transform(args, callback); break;
                    case "video.setVolume": setVolume(args, callback); break;
                    case "video.state": callback.complete(state()); break;
                    default: callback.fail("unsupported_method", "Unsupported video method: " + method);
                }
            } catch (Exception e) { if("video.prepare".equals(method)){prepareCallback=null;releasePlayer();state="error";} callback.fail(errorCode(e), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
        });
    }

    public void cancel(String method) {
        if (!"video.prepare".equals(method)) return;
        main.post(() -> { if (prepareCallback != null) { Callback c=prepareCallback; prepareCallback=null; c.fail("cancelled", "Video preparation cancelled"); releasePlayer(); state="stopped"; } });
    }
    public void pauseForLifecycle() { main.post(() -> { if (!backgroundPlayback) { lifecyclePaused=true; if(player!=null)player.pause(); state="paused"; } }); }

    private void prepare(JSONObject a, Callback cb) throws Exception {
        if(videoView==null || parent==null) throw new IllegalStateException("video_surface_unavailable");
        String videoUrl = a.optString("videoUrl", ""); String audioUrl = a.optString("audioUrl", "");
        String url = videoUrl.isEmpty() || audioUrl.isEmpty() ? source(a, "url") : sourceValue(videoUrl);
        if (!audioUrl.isEmpty()) audioUrl = sourceValue(audioUrl);
        if (!videoUrl.isEmpty()) videoUrl = sourceValue(videoUrl);
        if (prepareCallback != null) { prepareCallback.fail("cancelled", "Video preparation replaced"); prepareCallback=null; }
        releasePlayer();
        if(!decoderBudget.acquire())throw new IllegalStateException("video_decoder_limit");
        decoderLease=true;
        applyBounds(a.optJSONObject("bounds")); autoplay=a.optBoolean("autoplay", true); backgroundPlayback=a.optBoolean("backgroundPlayback",false); lifecyclePaused=false; density=context.getResources().getDisplayMetrics().density;
        Map<String,String> headers = headers(a.optJSONObject("headers"));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory(); if (!headers.isEmpty()) http.setDefaultRequestProperties(headers);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(context, http);
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(data);
        MediaSource source;
        if (!videoUrl.isEmpty() && !audioUrl.isEmpty()) source = new MergingMediaSource(factory.createMediaSource(item(videoUrl, a.optString("type", ""))), factory.createMediaSource(item(audioUrl, "")));
        else source = factory.createMediaSource(item(url, a.optString("type", "")));
        prepareCallback=cb; state="preparing";
        final MediaSource preparedSource=source;
        if(backgroundPlayback && Build.VERSION.SDK_INT>=33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED) throw new SecurityException("notification_permission_denied");
        final Intent serviceIntent=new Intent(context,PodVideoPlaybackService.class);
        final ServiceConnection pendingConnection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){
                if(connection!=this||closed)return;
                try {
                    player=((PodVideoPlaybackService.LocalBinder)binder).service().player();
                    configurePlayer(a,preparedSource);
                } catch(Exception error){Callback pending=prepareCallback;prepareCallback=null;releasePlayer();state="error";if(pending!=null)pending.fail("prepare_failed",error.getMessage());}
            }
            public void onServiceDisconnected(ComponentName name){if(connection!=this)return;Callback pending=prepareCallback;prepareCallback=null;player=null;releasePlayer();state="error";if(pending!=null)pending.fail("service_disconnected","Video service disconnected");}
            public void onNullBinding(ComponentName name){onServiceDisconnected(name);}
        };
        connection=pendingConnection;
        if(backgroundPlayback) context.startForegroundService(serviceIntent);
        if(!context.bindService(new Intent(serviceIntent).setAction("dev.podjs.runtime.BIND_VIDEO"),pendingConnection,Context.BIND_AUTO_CREATE)) throw new IllegalStateException("video_service_unavailable");
    }
    private void configurePlayer(JSONObject a,MediaSource source) throws Exception {
        if(player==null)throw new IllegalStateException("video_service_unavailable");
        final ExoPlayer session = player;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setRepeatMode(a.optBoolean("loop", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setVolume(a.optBoolean("muted", false) ? 0f : 1f);
        playerListener=new Player.Listener() {
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size){if(player==session)fitVideo();}
            @Override public void onPlaybackStateChanged(int s) { if(player!=session)return; if (s==Player.STATE_BUFFERING) { if (player.getPlayWhenReady()) state="buffering"; } else if(s==Player.STATE_READY && player!=null && player.isPlaying()) state="playing"; else if(s==Player.STATE_ENDED) { state="ended"; if(videoView!=null)videoView.setKeepScreenOn(false); } else if(s==Player.STATE_READY && !"paused".equals(state)) state="ready"; if (s==Player.STATE_READY && prepareCallback != null) { Callback c=prepareCallback; prepareCallback=null; try { c.complete(state()); } catch (Exception e) { c.fail("video_error", e.getMessage()); } } }
            @Override public void onIsPlayingChanged(boolean playing) { if(player!=session)return; if (playing) state="playing"; else if (state.equals("playing")) state="paused"; }
            @Override public void onPlayerError(PlaybackException error) { if(player!=session)return; state="error"; Callback c=prepareCallback; prepareCallback=null; releasePlayer(); if(c!=null)c.fail("prepare_failed",error.getMessage()==null?"Video playback failed":error.getMessage()); if(videoView!=null)videoView.setVisibility(View.GONE); }
        };
        player.addListener(playerListener);
        state=lifecyclePaused?"paused":"preparing"; if(videoView!=null)videoView.setKeepScreenOn(true); player.setMediaSource(source); player.setVideoTextureView(videoView); player.prepare();
        if (autoplay && !lifecyclePaused) player.play(); else player.pause();
    }
    private MediaItem item(String url, String type) { MediaItem.Builder b=new MediaItem.Builder().setUri(Uri.parse(url)); if(!type.isEmpty()) b.setMimeType(type); return b.build(); }
    private void play(Callback c) throws Exception { require(); lifecyclePaused=false;if(videoView!=null)videoView.setKeepScreenOn(true);player.play(); c.complete(state()); }
    private void pause(Callback c) throws Exception { require(); player.pause(); if(videoView!=null)videoView.setKeepScreenOn(false); state="paused"; c.complete(state()); }
    private void seek(JSONObject a, Callback c) throws Exception { require(); long p=a.optLong("positionMs", -1); if(p<0) throw new IllegalArgumentException("invalid_position"); player.seekTo(p); c.complete(state()); }
    private void stop(Callback c) throws Exception { releasePlayer(); clearBackdrop(); state="stopped"; if(videoView!=null){videoView.setVisibility(View.GONE);videoView.setKeepScreenOn(false);} c.complete(state()); }
    private void bounds(JSONObject b, Callback c) throws Exception { applyBounds(b); c.complete(state()); }
    private void captureBackdrop(Callback c) throws Exception { if(parent==null)throw new IllegalStateException("video_surface_unavailable"); PodRuntimeView pod=null; for(int i=0;i<parent.getChildCount();i++){View v=parent.getChildAt(i);if(v instanceof PodRuntimeView){pod=(PodRuntimeView)v;break;}} if(pod==null)throw new IllegalStateException("pod_surface_unavailable"); Bitmap shot=pod.getBitmap(); if(shot==null)throw new IllegalStateException("backdrop_unavailable"); clearBackdrop(); backdropBitmap=shot; if(backdrop==null){backdrop=new ImageView(context);backdrop.setScaleType(ImageView.ScaleType.FIT_XY);parent.addView(backdrop,0,new FrameLayout.LayoutParams(parent.getWidth(),parent.getHeight()));} backdrop.setImageBitmap(shot);backdrop.setVisibility(View.VISIBLE);c.complete(new JSONObject().put("ok",true)); }
    private void clearBackdrop(){if(backdrop!=null){backdrop.setImageDrawable(null);backdrop.setVisibility(View.GONE);}if(backdropBitmap!=null&&!backdropBitmap.isRecycled())backdropBitmap.recycle();backdropBitmap=null;}
    private void transform(JSONObject a, Callback c)throws Exception { String mode=a.optString("scaleMode","standard");if(!mode.equals("standard")&&!mode.equals("expanded")&&!mode.equals("shrunk"))throw new IllegalArgumentException("invalid_scale_mode");float rot=(float)a.optDouble("rotationDeg",0);if(!Float.isFinite(rot)||Math.abs(rot)>100000)throw new IllegalArgumentException("invalid_rotation");rotationDeg=Math.round(rot*100f)/100f;scaleMode=mode;panX=(float)a.optDouble("panX",0);panY=(float)a.optDouble("panY",0);if(!Float.isFinite(panX)||!Float.isFinite(panY))throw new IllegalArgumentException("invalid_pan");fitVideo();c.complete(state()); }
    private void setVolume(JSONObject a, Callback c)throws Exception {double v=a.optDouble("volume",-1);if(!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("invalid_volume");volume=(float)v;AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);am.setStreamVolume(AudioManager.STREAM_MUSIC,Math.round(max*volume),0);c.complete(state());}
    private void applyBounds(JSONObject b) { if(videoView==null||b==null)return; if(backdrop!=null)backdrop.setVisibility(b.optDouble("translateX",0)==0?View.GONE:View.VISIBLE); int x=dp(b.optDouble("x",0)+b.optDouble("translateX",0)),y=dp(b.optDouble("y",0)),w=dp(b.optDouble("width",0)),h=dp(b.optDouble("height",0)); FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.max(0,w),Math.max(0,h));p.leftMargin=x;p.topMargin=y;videoView.setLayoutParams(p); boolean visible=b.optBoolean("visible",true);videoView.setVisibility(visible&&w>0&&h>0?View.VISIBLE:View.GONE); JSONObject clip=b.optJSONObject("roundClip"); if(clip!=null){final float r=(float)clip.optDouble("radius",0)*density; final float cx=(float)clip.optDouble("cx",w/(2*density))*density,cy=(float)clip.optDouble("cy",h/(2*density))*density; videoView.setClipToOutline(r>0); videoView.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline o){o.setRoundRect((int)(cx-r),(int)(cy-r),(int)(cx+r),(int)(cy+r),r);}}); } else videoView.setClipToOutline(false); if(videoBackground!=null){videoBackground.setLayoutParams(new FrameLayout.LayoutParams(p));videoBackground.setVisibility(videoView.getVisibility());videoBackground.setOutlineProvider(videoView.getOutlineProvider());videoBackground.setClipToOutline(videoView.getClipToOutline());} fitVideo(); }
    private void fitVideo(){
        if(player==null||videoView==null||videoView.getLayoutParams()==null)return;
        androidx.media3.common.VideoSize size=player.getVideoSize();int w=videoView.getLayoutParams().width,h=videoView.getLayoutParams().height;
        if(size.width<=0||size.height<=0||w<=0||h<=0)return;
        float vw=size.width*size.pixelWidthHeightRatio, vh=size.height;
        Matrix transform=videoTransform(w,h,vw,vh,rotationDeg,scaleMode,dp(panX),dp(panY));
        videoView.setTransform(transform);
    }
    static Matrix videoTransform(float w,float h,float vw,float vh,float rotation,String mode,float panX,float panY) {
        double angle=Math.toRadians(rotation),ca=Math.abs(Math.cos(angle)),sa=Math.abs(Math.sin(angle));
        float rotatedW=(float)(vw*ca+vh*sa),rotatedH=(float)(vw*sa+vh*ca);
        float factor="expanded".equals(mode)?Math.max(w/rotatedW,h/rotatedH):Math.min(w/rotatedW,h/rotatedH);
        if("shrunk".equals(mode))factor=Math.min(factor,(float)(Math.min(w,h)/Math.hypot(vw,vh)));
        Matrix matrix=new Matrix(); matrix.setScale(vw*factor/w,vh*factor/h,w/2,h/2);
        matrix.postRotate(rotation,w/2,h/2); matrix.postTranslate(panX,panY); return matrix;
    }
    private int dp(double v) { if(!Double.isFinite(v)) throw new IllegalArgumentException("invalid_bounds"); return Math.max(-100000,Math.min(100000,(int)Math.round(v*density))); }
    private void require(){if(player==null)throw new IllegalStateException("video_not_ready");}
    private JSONObject state() throws Exception { AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);return new JSONObject().put("ok",true).put("state",state).put("positionMs",player==null?0:player.getCurrentPosition()).put("durationMs",player==null?0:Math.max(0,player.getDuration())).put("width",player==null?0:player.getVideoSize().width).put("height",player==null?0:player.getVideoSize().height).put("volume",am.getStreamVolume(AudioManager.STREAM_MUSIC)/(double)Math.max(1,am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))).put("volumeMax",am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); }
    private String source(JSONObject a,String key)throws Exception{String s=a.optString(key,"");if(s.isEmpty())throw new IllegalArgumentException("missing_url");return sourceValue(s);}
    private String sourceValue(String s)throws Exception{if(s.startsWith("http://")||s.startsWith("https://")){Uri u=Uri.parse(s);if(u.getHost()==null)throw new IllegalArgumentException("invalid_url");return s;}File f=new File(mediaRoot,s).getCanonicalFile();if(s.startsWith("/")||!f.getPath().startsWith(mediaRoot.getPath()+File.separator)||!f.isFile())throw new SecurityException("invalid_media_path");return f.toURI().toString();}
    private Map<String,String> headers(JSONObject o)throws Exception{Map<String,String> m=new HashMap<>();if(o==null)return m; if(o.length()>32)throw new IllegalArgumentException("too_many_headers");int total=0;java.util.Iterator<String> i=o.keys();while(i.hasNext()){String k=i.next();String v=o.optString(k,null);if(v==null||k.length()>128||v.length()>65536||k.indexOf('\r')>=0||k.indexOf('\n')>=0||v.indexOf('\r')>=0||v.indexOf('\n')>=0)throw new IllegalArgumentException("invalid_headers");total+=k.length()+v.length();if(total>65536)throw new IllegalArgumentException("headers_too_large");m.put(k,v);}return m;}
    private void detachPlayer(boolean keepPlaying){
        ExoPlayer previous=player;player=null;
        if(previous!=null){if(playerListener!=null)previous.removeListener(playerListener);previous.clearVideoSurface();if(!keepPlaying){previous.stop();previous.clearMediaItems();}}
        playerListener=null;
        ServiceConnection previousConnection=connection;connection=null;
        if(previousConnection!=null){try{context.unbindService(previousConnection);}catch(IllegalArgumentException ignored){}}
        if(!keepPlaying){context.stopService(new Intent(context,PodVideoPlaybackService.class));if(decoderLease){decoderLease=false;decoderBudget.release();}}
        if(videoView!=null)videoView.setVisibility(View.GONE);
        if(videoBackground!=null)videoBackground.setVisibility(View.GONE);
        if(prepareCallback!=null){Callback pending=prepareCallback;prepareCallback=null;pending.fail("cancelled","Video preparation cancelled");}
    }
    private void releasePlayer(){detachPlayer(false);if(videoView!=null)videoView.setKeepScreenOn(false);}
    @Override public void close(){closed=true;main.post(()->{detachPlayer(backgroundPlayback&&player!=null&&prepareCallback==null&&player.getPlayWhenReady());if(videoView!=null&&videoView.getParent() instanceof ViewGroup)((ViewGroup)videoView.getParent()).removeView(videoView);if(videoView!=null)videoView.setKeepScreenOn(false);videoView=null;if(videoBackground!=null&&videoBackground.getParent() instanceof ViewGroup)((ViewGroup)videoBackground.getParent()).removeView(videoBackground);videoBackground=null;clearBackdrop();if(backdrop!=null&&backdrop.getParent() instanceof ViewGroup)((ViewGroup)backdrop.getParent()).removeView(backdrop);backdrop=null;parent=null;state="idle";});}
    private static String errorCode(Exception e){return e instanceof SecurityException?"permission_denied":e instanceof IllegalArgumentException?"invalid_argument":"video_decoder_limit".equals(e.getMessage())?"resource_exhausted":"video_error";}
}
