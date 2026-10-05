package dev.podjs.runtime;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.Presentation;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local image/video surfaces copied into owned scene textures; never an interactive overlay.
 * Player/view/texture operations run on main. Decoding and intrinsic image blur run on workers.
 */
final class PodMediaSurfaceServices implements AutoCloseable {
    interface TextureSink { int write(int handle,int[] argb,int width,int height,boolean linear); void free(int handle); }
    interface Callback { void complete(JSONObject value); void fail(String code,String message); }
    /** Diagnostic CPU wall-clock samples. The borrowed array is valid only during record(). */
    interface TimingObserver { void record(String sessionId,int kind,long[] values); }
    static final class Timing {
        static final int DISPATCH=1,RENDER=2;
        static final int INITIAL=1,DECODE=2,STYLE=3,VIDEO_FRAME=4,VIDEO_STATE=5;
        static final int OPEN=1,UPDATE=2,STATE=3,CLOSE=4,OTHER=0;
        static final int OK=1,EXCEPTION=2,SINK_REJECTED=3;
        static final int RECORD_STAGE=1,DRAW_STAGE=2,ACQUIRE_STAGE=3,COPY_STAGE=4,PIXELS_STAGE=5,CLEANUP_STAGE=6,SINK_STAGE=7;
        static final int SEQUENCE=0,DISPATCH_SEQUENCE=1,CAUSE=2,HANDLE_BEFORE=3,HANDLE_AFTER=4,DECODE_REVISION=5,
            WIDTH=6,HEIGHT=7,TEXTURE_WIDTH=8,TEXTURE_HEIGHT=9,ENQUEUED_NS=10,BEGIN_NS=11,END_NS=12,
            RECORD_BEGIN_NS=13,RECORD_END_NS=14,DRAW_BEGIN_NS=15,DRAW_END_NS=16,
            ACQUIRE_BEGIN_NS=17,ACQUIRE_END_NS=18,COPY_BEGIN_NS=19,COPY_END_NS=20,
            PIXELS_BEGIN_NS=21,PIXELS_END_NS=22,CLEANUP_BEGIN_NS=23,CLEANUP_END_NS=24,
            SINK_BEGIN_NS=25,SINK_END_NS=26,WORK_END_NS=27,COMPLETE_BEGIN_NS=28,COMPLETE_END_NS=29,
            FAIL_BEGIN_NS=30,FAIL_END_NS=31,OUTCOME=32,FAILED_STAGE=33,FIELD_COUNT=34;
        static void stamp(long[] values,int field){if(values!=null)values[field]=System.nanoTime();}
        static void stage(long[] values,int stage,int field){if(values!=null){values[FAILED_STAGE]=stage;stamp(values,field);}}
    }
    private final Context context;
    private final File root;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final HashMap<String,Session> sessions=new HashMap<>();
    private TextureSink sink;
    private ViewGroup parent;
    private boolean closed,foreground=true;
    private volatile TimingObserver timingObserver;
    private long[] dispatchTiming,renderTiming;
    private long dispatchSequence,renderSequence,activeDispatchSequence;

    // Installed only by host diagnostics; normal services allocate no timing arrays.
    TimingObserver setTimingObserver(TimingObserver observer){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("timing_observer_requires_main");
        TimingObserver previous=timingObserver;
        if(observer!=null&&dispatchTiming==null){dispatchTiming=new long[Timing.FIELD_COUNT];renderTiming=new long[Timing.FIELD_COUNT];}
        timingObserver=observer;return previous;
    }
    private void reportTiming(TimingObserver observer,String id,int kind,long[] values){
        if(observer==null||observer!=timingObserver)return;
        try{observer.record(id,kind,values);}catch(Throwable ignored){/* Diagnostics cannot change service outcomes. */}
    }
    private static void complete(Callback callback,JSONObject value,long[] timing){
        Timing.stamp(timing,Timing.COMPLETE_BEGIN_NS);
        try{callback.complete(value);}finally{Timing.stamp(timing,Timing.COMPLETE_END_NS);}
    }
    private static void fail(Callback callback,String code,String message,long[] timing){
        Timing.stamp(timing,Timing.FAIL_BEGIN_NS);
        try{callback.fail(code,message);}finally{Timing.stamp(timing,Timing.FAIL_END_NS);}
    }

    PodMediaSurfaceServices(Context context) {
        this.context=context.getApplicationContext();
        try {root=PodTakeover.filesRoot(this.context).getCanonicalFile();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    void attach(ViewGroup parent,TextureSink sink){this.parent=parent;this.sink=sink;}
    void lifecycle(boolean resumed){main.post(()->{foreground=resumed;for(Session s:sessions.values())s.playback();});}
    void dispatch(String method,JSONObject args,Callback callback){
        final TimingObserver queuedObserver=timingObserver;
        final long enqueuedNs=queuedObserver==null?0:System.nanoTime();
        main.post(()->{
        TimingObserver observer=queuedObserver==timingObserver?queuedObserver:null;
        long[] timing=observer==null?null:dispatchTiming;
        long previousDispatch=activeDispatchSequence;
        if(timing!=null){java.util.Arrays.fill(timing,0);timing[Timing.SEQUENCE]=++dispatchSequence;timing[Timing.DISPATCH_SEQUENCE]=dispatchSequence;timing[Timing.ENQUEUED_NS]=enqueuedNs;timing[Timing.OUTCOME]=Timing.EXCEPTION;Timing.stamp(timing,Timing.BEGIN_NS);}
        activeDispatchSequence=timing==null?0:timing[Timing.SEQUENCE];
        String timingId="";
        try {
            if(closed)throw new IllegalStateException("service_closed");
            String id=args.optString("id","");if(!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("invalid_surface_id");
            timingId=id;
            boolean video=method.startsWith("video.");String action=method.substring(method.lastIndexOf('.')+1);
            if(timing!=null)timing[Timing.CAUSE]=action.equals("open")?Timing.OPEN:action.equals("update")?Timing.UPDATE:action.equals("state")?Timing.STATE:action.equals("close")?Timing.CLOSE:Timing.OTHER;
            Session s=sessions.get(id);
            if(s!=null&&s.video!=video)throw new IllegalArgumentException("surface_kind_mismatch");
            if(action.equals("close")){if(s!=null){sessions.remove(id);s.close();}JSONObject value=new JSONObject().put("closed",true);Timing.stamp(timing,Timing.WORK_END_NS);complete(callback,value,timing);if(timing!=null)timing[Timing.OUTCOME]=Timing.OK;return;}
            if(action.equals("open")){
                if(s!=null)throw new IllegalArgumentException("surface_id_in_use");
                if(sessions.size()>=8)throw new IllegalStateException("surface_limit");
                if(sink==null||parent==null)throw new IllegalStateException("surface_host_unavailable");
                s=new Session(id,video,args);sessions.put(id,s);
                try{s.start();}catch(Exception failure){sessions.remove(id);s.close();throw failure;}
            }else if(s==null)throw new IllegalArgumentException("unknown_surface");
            else if(action.equals("update"))s.update(args);
            else if(!action.equals("state"))throw new IllegalArgumentException("unsupported_method");
            JSONObject value=s.state();Timing.stamp(timing,Timing.WORK_END_NS);complete(callback,value,timing);if(timing!=null)timing[Timing.OUTCOME]=Timing.OK;
        }catch(Exception error){if(timing!=null&&timing[Timing.WORK_END_NS]==0)Timing.stamp(timing,Timing.WORK_END_NS);fail(callback,error instanceof IllegalArgumentException?"invalid_argument":"media_surface_error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(),timing);}
        finally{activeDispatchSequence=previousDispatch;Timing.stamp(timing,Timing.END_NS);reportTiming(observer,timingId,Timing.DISPATCH,timing);}
    });}
    private File file(JSONObject args,String key,boolean required)throws Exception {
        Object raw=args.opt(key);if(raw==null||raw==JSONObject.NULL){if(required)throw new IllegalArgumentException("missing_"+key);return null;}
        if(!(raw instanceof String))throw new IllegalArgumentException("invalid_"+key);
        String path=(String)raw;if(path.isEmpty()){if(required)throw new IllegalArgumentException("missing_"+key);return null;}
        if(path.startsWith("/")||path.indexOf('\\')>=0||path.indexOf('\0')>=0)throw new IllegalArgumentException("invalid_media_path");
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("invalid_media_path");
        File f=new File(root,path).getCanonicalFile();if(!f.getPath().startsWith(root.getPath()+File.separator))throw new IllegalArgumentException("invalid_media_path");
        return f;
    }
    static float number(JSONObject args,String key,float fallback,float low,float high)throws Exception {
        if(!args.has(key))return fallback;Object raw=args.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("invalid_"+key);
        double n=((Number)raw).doubleValue();if(!Double.isFinite(n)||n<low||n>high)throw new IllegalArgumentException("invalid_"+key);return (float)n;
    }
    static boolean bool(JSONObject args,String key,boolean fallback){if(!args.has(key))return fallback;Object raw=args.opt(key);if(!(raw instanceof Boolean))throw new IllegalArgumentException("invalid_"+key);return (Boolean)raw;}
    static boolean linearSampling(JSONObject args){
        Object value=args.has("textureSampling")?args.opt("textureSampling"):"nearest";
        if("nearest".equals(value))return false;
        if("linear".equals(value))return true;
        throw new IllegalArgumentException("invalid_textureSampling");
    }
    static int color(JSONObject args,String key,int fallback)throws Exception {if(!args.has(key))return fallback;Object raw=args.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("invalid_"+key);double n=((Number)raw).doubleValue();if(!Double.isFinite(n)||n<Integer.MIN_VALUE||n>0xffffffffL||n!=Math.rint(n))throw new IllegalArgumentException("invalid_"+key);return (int)(long)n;}
    /** Presentation in physical pixels relative to the fixed output viewport. */
    static final class PresentationFrame {
        final float left,top,scale,opacity;final int width,height;
        PresentationFrame(JSONObject a){
            left=(float)value(a,"leftPx",-32768,32768);top=(float)value(a,"topPx",-32768,32768);
            double w=value(a,"widthPx",1,1024),h=value(a,"heightPx",1,1024);
            if(w!=Math.rint(w)||h!=Math.rint(h))throw new IllegalArgumentException("invalid_presentationFrame");
            width=(int)w;height=(int)h;scale=(float)value(a,"scale",.25,8);opacity=(float)value(a,"opacity",0,1);
        }
        private static double value(JSONObject a,String key,double low,double high){
            Object raw=a.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("invalid_presentationFrame");
            double value=((Number)raw).doubleValue();
            if(!Double.isFinite(value)||value<low||value>high)throw new IllegalArgumentException("invalid_presentationFrame");
            return value;
        }
        JSONObject toJson()throws Exception{return new JSONObject().put("leftPx",left).put("topPx",top).put("widthPx",width).put("heightPx",height).put("scale",scale).put("opacity",opacity);}
    }
    static final class Style {
        final String fit,blurEdgeMode;final float zoom,focusX,focusY,rotation,blur,brightness,brightnessBias,saturation,overlayOpacity,speed;final int base,overlay;final boolean loop,active;
        final PresentationFrame presentationFrame;
        Style(JSONObject a)throws Exception {
            Object presentation=a.opt("presentationFrame");
            if(a.has("presentationFrame")&&!(presentation instanceof JSONObject))throw new IllegalArgumentException("invalid_presentationFrame");
            presentationFrame=a.has("presentationFrame")?new PresentationFrame((JSONObject)presentation):null;
            fit=a.optString("fit","crop");if(!fit.equals("crop")&&!fit.equals("fit")&&!fit.equals("fill"))throw new IllegalArgumentException("invalid_fit");
            zoom=number(a,"zoom",1,.25f,8);focusX=number(a,"focusX",.5f,0,1);focusY=number(a,"focusY",.5f,0,1);
            rotation=number(a,"rotationDegrees",0,-180,180);blur=number(a,"blurPx",0,0,256);brightness=number(a,"brightness",1,0,2);brightnessBias=number(a,"brightnessBias",0,-1,1);saturation=number(a,"saturation",1,0,2);
            Object edgeMode=a.has("blurEdgeMode")?a.opt("blurEdgeMode"):"decal";
            if(!(edgeMode instanceof String)||(!edgeMode.equals("decal")&&!edgeMode.equals("clamp")))throw new IllegalArgumentException("invalid_blurEdgeMode");
            blurEdgeMode=(String)edgeMode;
            overlayOpacity=number(a,"overlayOpacity",0,0,1);base=color(a,"colorArgb",Color.BLACK);overlay=color(a,"overlayColorArgb",Color.BLACK);speed=number(a,"speed",1,.25f,4);loop=bool(a,"loop",true);active=bool(a,"active",true);
        }
    }
    final class CaptureView extends TextureView {
        private SurfaceTextureListener playerListener;
        CaptureView(Session owner){super(context);
            super.setSurfaceTextureListener(new SurfaceTextureListener(){
                public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){if(playerListener!=null)playerListener.onSurfaceTextureAvailable(texture,width,height);if(!owner.disposed){setAlpha(0f);owner.createPlayer();}}
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){if(playerListener!=null)playerListener.onSurfaceTextureSizeChanged(texture,width,height);}
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(playerListener!=null)playerListener.onSurfaceTextureDestroyed(texture);owner.releasePlayer();return true;}
                public void onSurfaceTextureUpdated(SurfaceTexture texture){if(playerListener!=null)playerListener.onSurfaceTextureUpdated(texture);if(!owner.disposed&&owner.ready&&!owner.failed&&foreground&&owner.style.active)owner.requestCapture();}
            });
        }
        // Media3 owns its listener while our wrapper observes the same frames.
        @Override public void setSurfaceTextureListener(SurfaceTextureListener listener){playerListener=listener;}
        @Override public SurfaceTextureListener getSurfaceTextureListener(){return playerListener;}
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){return false;}
    }
    final class Session implements AutoCloseable {
        final String id;final boolean video,linearSampling;final File source,poster;final int width,height,tw,th;final SurfaceRenderer renderer;final int[] pixels;
        int handle=-1;long revision,renderedFrames,playerGeneration;Style style;boolean disposed,ready,failed;String error;
        final java.util.concurrent.atomic.AtomicBoolean captureQueued=new java.util.concurrent.atomic.AtomicBoolean();
        Bitmap image,frame;TextureView view;ExoPlayer player;float videoWidth,videoHeight;boolean imageLoading;
        Session(String id,boolean video,JSONObject a)throws Exception {
            this.id=id;this.video=video;linearSampling=linearSampling(a);source=file(a,"path",video);poster=file(a,"posterPath",false);
            float w=number(a,"widthPx",0,1,1024),h=number(a,"heightPx",0,1,1024);if(w!=(int)w||h!=(int)h)throw new IllegalArgumentException("invalid_dimensions");
            width=(int)w;height=(int)h;tw=powerOfTwo(width);th=powerOfTwo(height);style=new Style(a);
            renderer=new SurfaceRenderer(width,height,tw,th);pixels=new int[tw*th];
        }
        void start()throws Exception {render(Timing.INITIAL);loadImage();if(video)startVideo();}
        void loadImage(){
            final File input=video?poster:source;final long version=++revision,initiatingDispatch=activeDispatchSequence;final float blur=style.blur;imageLoading=input!=null;
            if(input==null)return;
            worker.execute(()->{
                Bitmap decoded=null;String problem=null;
                try {decoded=decode(input,width,height);if(Build.VERSION.SDK_INT<31&&blur>0){Bitmap blurred=blur(context,decoded,blur);decoded.recycle();decoded=blurred;}}
                catch(Exception e){if(decoded!=null)decoded.recycle();decoded=null;problem=e.getMessage();}
                final Bitmap result=decoded;final String failure=problem;
                main.post(()->{
                    if(disposed||sessions.get(id)!=this||version!=revision){if(result!=null)result.recycle();return;}
                    imageLoading=false;if(image!=null)image.recycle();image=result;if(failure!=null)error=failure;
                    render(Timing.DECODE,initiatingDispatch);
                });
            });
        }
        void update(JSONObject args)throws Exception {
            if(args.has("textureSampling"))throw new IllegalArgumentException("immutable_textureSampling");
            Style next=new Style(args);boolean blurChanged=next.blur!=style.blur;style=next;
            // API 31+ filters the already-decoded bitmap in RenderEffect. A
            // changing radius must not launch another decode for every frame.
            // Older images bake blur into the decoded bitmap; preserve that
            // path and the existing video-effect update behavior.
            if(blurChanged){if(Build.VERSION.SDK_INT<31)loadImage();applyEffects();}
            if(player!=null){player.setRepeatMode(style.loop?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);player.setPlaybackSpeed(style.speed);}
            playback();render(Timing.STYLE);
        }
        void startVideo() {
            worker.execute(()->{
                float w=0,h=0;String problem=null;
                try {android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();try{
                    metadata.setDataSource(source.getPath());w=Float.parseFloat(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));h=Float.parseFloat(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    String value=metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);int rotation=value==null?0:Integer.parseInt(value);if(Math.floorMod(rotation,180)==90){float swap=w;w=h;h=swap;}if(w<=0||h<=0)throw new IllegalArgumentException("invalid_video_dimensions");
                }finally{metadata.release();}}catch(Exception e){problem=e.getMessage();}
                final float sourceW=w,sourceH=h;final String failure=problem;
                main.post(()->{if(disposed)return;if(failure!=null||sourceW<=0||sourceH<=0){failed=true;error=failure==null?"invalid_video":failure;render(Timing.VIDEO_STATE);return;}
                    videoWidth=sourceW;videoHeight=sourceH;
                    view=new CaptureView(this);view.setOpaque(false);view.setClickable(false);view.setFocusable(false);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    // It becomes alpha-zero as soon as the empty surface is available.
                    // Decoded frames are presented only through the scene texture.
                    parent.addView(view,0,new FrameLayout.LayoutParams(width,height));if(view.isAvailable())createPlayer();
                });
            });
        }
        void createPlayer(){
            if(player!=null||disposed)return;
            try {
                player=new ExoPlayer.Builder(context).build();playerGeneration++;final ExoPlayer own=player;ready=false;failed=false;
                player.setVolume(0f);player.setRepeatMode(style.loop?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);player.setPlaybackSpeed(style.speed);
                player.addListener(new Player.Listener(){
                    @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size){if(player!=own)return;render(Timing.VIDEO_STATE);}
                    @Override public void onRenderedFirstFrame(){if(player!=own)return;ready=true;requestCapture();}
                    @Override public void onPlayerError(PlaybackException e){if(player!=own)return;failed=true;ready=false;error=e.getMessage();render(Timing.VIDEO_STATE);}
                });
                player.setVideoFrameMetadataListener((timeUs,releaseNs,format,mediaFormat)->requestCapture());
                if(Build.VERSION.SDK_INT<31)player.setVideoEffects(new ArrayList<>());applyEffects();player.setVideoTextureView(view);player.setMediaItem(MediaItem.fromUri(source.toURI().toString()));player.prepare();playback();
            }catch(Exception e){failed=true;error=e.getMessage();releasePlayer();render(Timing.VIDEO_STATE);}
        }
        void applyEffects(){
            if(player==null||videoWidth<=0||videoHeight<=0||Build.VERSION.SDK_INT>=31)return;
            ArrayList<androidx.media3.common.Effect> effects=new ArrayList<>();
            if(style.blur>0){int sample=videoWidth>2&&videoHeight>2?Math.max(1,(int)Math.ceil(style.blur/25f)):1;
                if(sample>1)effects.add(Presentation.createForWidthAndHeight(Math.max(2,Math.round(videoWidth/sample)),Math.max(2,Math.round(videoHeight/sample)),Presentation.LAYOUT_SCALE_TO_FIT));
                effects.add(new GaussianBlur(style.blur/sample*.4f+.6f));}
            player.setVideoEffects(effects);
        }
        void playback(){if(player!=null)player.setPlayWhenReady(foreground&&style.active&&!failed);}
        void requestCapture(){
            if(!captureQueued.compareAndSet(false,true))return;
            main.postDelayed(()->{try{if(!disposed&&ready&&!failed&&foreground&&style.active)capture();}finally{captureQueued.set(false);}},16);
        }
        void capture(){
            if(view==null||!view.isAvailable()||disposed)return;
            try {if(frame==null)frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);if(view.getBitmap(frame)!=null){renderedFrames++;render(Timing.VIDEO_FRAME);}}
            catch(Exception e){failed=true;error=e.getMessage();render(Timing.VIDEO_STATE);}
        }
        void render(int cause){render(cause,activeDispatchSequence);}
        void render(int cause,long dispatch){
            if(disposed)return;
            TimingObserver observer=timingObserver;long[] timing=observer==null?null:renderTiming;
            if(timing!=null){
                java.util.Arrays.fill(timing,0);timing[Timing.SEQUENCE]=++renderSequence;timing[Timing.DISPATCH_SEQUENCE]=dispatch;timing[Timing.CAUSE]=cause;
                timing[Timing.HANDLE_BEFORE]=handle;timing[Timing.HANDLE_AFTER]=handle;timing[Timing.DECODE_REVISION]=revision;
                timing[Timing.WIDTH]=width;timing[Timing.HEIGHT]=height;timing[Timing.TEXTURE_WIDTH]=tw;timing[Timing.TEXTURE_HEIGHT]=th;
                timing[Timing.OUTCOME]=Timing.EXCEPTION;Timing.stamp(timing,Timing.BEGIN_NS);
            }
            try {
                renderer.render(image,ready&&!failed?frame:null,videoWidth,videoHeight,style,pixels,timing);
                Timing.stage(timing,Timing.SINK_STAGE,Timing.SINK_BEGIN_NS);
                int next;try{next=sink.write(handle,pixels,tw,th,linearSampling);}finally{Timing.stamp(timing,Timing.SINK_END_NS);}
                if(next<0){failed=true;error="texture_upload_failed";if(timing!=null)timing[Timing.OUTCOME]=Timing.SINK_REJECTED;}
                else{handle=next;if(timing!=null){timing[Timing.OUTCOME]=Timing.OK;timing[Timing.FAILED_STAGE]=0;}}
            }catch(Exception e){failed=true;error="surface_render_failed: "+e.getMessage();}
            finally{if(timing!=null)timing[Timing.HANDLE_AFTER]=handle;Timing.stamp(timing,Timing.END_NS);reportTiming(observer,id,Timing.RENDER,timing);}
        }
        JSONObject state()throws Exception{return new JSONObject().put("id",id).put("texture",handle).put("width",tw).put("height",th).put("contentWidth",width).put("contentHeight",height)
            .put("textureSampling",linearSampling?"linear":"nearest")
            .put("presentationFrame",style.presentationFrame==null?JSONObject.NULL:style.presentationFrame.toJson())
            .put("state",failed?"error":video?(ready?(player!=null&&player.isPlaying()?"playing":"paused"):"loading"):(imageLoading?"loading":image!=null||source==null?"ready":"fallback"))
            .put("error",error==null?JSONObject.NULL:error).put("frames",renderedFrames).put("playerGeneration",playerGeneration).put("positionMs",player==null?0:player.getCurrentPosition()).put("speed",style.speed).put("loop",style.loop).put("muted",true);}
        void releasePlayer(){ExoPlayer old=player;player=null;if(old!=null)old.release();ready=false;}
        @Override public void close(){if(disposed)return;disposed=true;revision++;releasePlayer();if(view!=null){view.setSurfaceTextureListener(null);if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);view=null;}if(handle>=0)sink.free(handle);handle=-1;if(image!=null)image.recycle();if(frame!=null)frame.recycle();renderer.close();}
    }
    static int powerOfTwo(int n){int x=1;while(x<n)x*=2;return x;}
    static Bitmap decode(File source,int width,int height)throws Exception {
        if(!source.isFile())throw new java.io.IOException("file_not_found");
        BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.getPath(),b);
        if(b.outWidth<1||b.outHeight<1||b.outWidth>16384||b.outHeight>16384||(long)b.outWidth*b.outHeight>64L*1024*1024)throw new java.io.IOException("unsupported_image_dimensions");
        BitmapFactory.Options o=new BitmapFactory.Options();o.inScaled=false;o.inPreferredConfig=Bitmap.Config.ARGB_8888;o.inSampleSize=1;
        while(Math.max(b.outWidth,b.outHeight)/(o.inSampleSize*2)>=Math.max(width,height)*2)o.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(source.getPath(),o);if(bitmap==null)throw new java.io.IOException("unsupported_image");return bitmap;
    }
    @SuppressWarnings("deprecation")
    static Bitmap blur(Context context,Bitmap input,float radius){
        int sample=Math.max(1,(int)Math.ceil(radius/25f));Bitmap small=Bitmap.createScaledBitmap(input,Math.max(1,input.getWidth()/sample),Math.max(1,input.getHeight()/sample),true);
        Bitmap software=small.copy(Bitmap.Config.ARGB_8888,true);if(small!=input)small.recycle();
        android.renderscript.RenderScript rs=android.renderscript.RenderScript.create(context);android.renderscript.Allocation in=null,out=null;android.renderscript.ScriptIntrinsicBlur blur=null;
        try {in=android.renderscript.Allocation.createFromBitmap(rs,software);out=android.renderscript.Allocation.createTyped(rs,in.getType());blur=android.renderscript.ScriptIntrinsicBlur.create(rs,android.renderscript.Element.U8_4(rs));blur.setRadius(Math.max(.1f,Math.min(25f,radius/sample)));blur.setInput(in);blur.forEach(out);out.copyTo(software);Bitmap result=Bitmap.createScaledBitmap(software,input.getWidth(),input.getHeight(),true);if(result!=software)software.recycle();return result;}
        catch(RuntimeException error){software.recycle();throw error;}finally{if(blur!=null)blur.destroy();if(out!=null)out.destroy();if(in!=null)in.destroy();rs.destroy();}
    }
    static ColorMatrix colorMatrix(float brightness,float saturation){return colorMatrix(brightness,saturation,0);}
    static ColorMatrix colorMatrix(float brightness,float saturation,float brightnessBias){return new ColorMatrix(colorMatrixValues(brightness,saturation,brightnessBias));}
    static float[] colorMatrixValues(float brightness,float saturation,float brightnessBias){float s=Math.max(0,Math.min(2,saturation)),b=Math.max(0,Math.min(2,brightness)),bias=255f*Math.max(-1,Math.min(1,brightnessBias)),inverse=1-s,r=.213f*inverse,g=.715f*inverse,blue=.072f*inverse;
        return new float[]{(r+s)*b,g*b,blue*b,0,bias,r*b,(g+s)*b,blue*b,0,bias,r*b,g*b,(blue+s)*b,0,bias,0,0,0,1,0};}
    static int[] plane(int width,int height,float sourceWidth,float sourceHeight,Style style){float sx=width/Math.max(1,sourceWidth),sy=height/Math.max(1,sourceHeight),scale=style.fit.equals("crop")?Math.max(sx,sy):Math.min(sx,sy);
        int w=Math.max(1,Math.round((style.fit.equals("fill")?width:sourceWidth*scale)*style.zoom)),h=Math.max(1,Math.round((style.fit.equals("fill")?height:sourceHeight*scale)*style.zoom));return new int[]{w,h,Math.round((width-w)*style.focusX),Math.round((height-h)*style.focusY)};}
    /** Hardware Canvas matches Android image filtering and layer blending. The
     * resulting bounded RGBA surface is copied to the normal scene texture. */
    static final class SurfaceRenderer implements AutoCloseable {
        final int width,height,tw,th;final android.media.ImageReader images;final HardwareRenderer renderer;final RenderNode root,media,entry;
        SurfaceRenderer(int width,int height,int tw,int th){
            this.width=width;this.height=height;this.tw=tw;this.th=th;
            images=android.media.ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            renderer=new HardwareRenderer();renderer.setOpaque(false);renderer.setSurface(images.getSurface());
            root=new RenderNode("PodMediaSurface");root.setPosition(0,0,width,height);media=new RenderNode("PodMediaContent");media.setPosition(0,0,width,height);media.setClipToBounds(true);renderer.setContentRoot(root);
            entry=new RenderNode("PodMediaPresentation");entry.setClipToBounds(false);
            // Match Android's automatic compositing: fractional alpha may need
            // a layer, while opaque content may replay directly into the viewport.
            entry.setUseCompositingLayer(false,null);entry.setHasOverlappingRendering(true);
        }
        void render(Bitmap image,Bitmap frame,float videoWidth,float videoHeight,Style style,int[] pixels){
            render(image,frame,videoWidth,videoHeight,style,pixels,null);
        }
        void render(Bitmap image,Bitmap frame,float videoWidth,float videoHeight,Style style,int[] pixels,long[] timing){
            Timing.stage(timing,Timing.RECORD_STAGE,Timing.RECORD_BEGIN_NS);
            try{
            PresentationFrame presentation=style.presentationFrame;
            int contentWidth=presentation==null?width:presentation.width,contentHeight=presentation==null?height:presentation.height;
            media.setPosition(0,0,contentWidth,contentHeight);
            RecordingCanvas content=media.beginRecording(contentWidth,contentHeight);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix(style.brightness,style.saturation,style.brightnessBias)));
            if(image!=null){float sx=contentWidth/(float)image.getWidth(),sy=contentHeight/(float)image.getHeight(),scale=style.fit.equals("crop")?Math.max(sx,sy):Math.min(sx,sy);float w=(style.fit.equals("fill")?contentWidth:image.getWidth()*scale)*style.zoom,h=(style.fit.equals("fill")?contentHeight:image.getHeight()*scale)*style.zoom;w=Math.round(w);h=Math.round(h);float x=Math.round((contentWidth-w)*style.focusX),y=Math.round((contentHeight-h)*style.focusY);content.drawBitmap(image,null,new RectF(x,y,x+w,y+h),paint);}
            if(frame!=null&&videoWidth>0&&videoHeight>0){int[] p=plane(contentWidth,contentHeight,videoWidth,videoHeight,style);float captureWidth=frame.getWidth(),captureHeight=frame.getHeight(),inputScale=Math.min(captureWidth/videoWidth,captureHeight/videoHeight),iw=Build.VERSION.SDK_INT<31?videoWidth*inputScale:captureWidth,ih=Build.VERSION.SDK_INT<31?videoHeight*inputScale:captureHeight,sx=p[0]/iw,sy=p[1]/ih;Matrix matrix=new Matrix();matrix.setScale(sx,sy);matrix.postTranslate(p[2]-(captureWidth-iw)/2*sx,p[3]-(captureHeight-ih)/2*sy);content.drawBitmap(frame,matrix,paint);}
            media.endRecording();media.setPivotX(contentWidth/2f);media.setPivotY(contentHeight/2f);media.setRotationZ(style.rotation);
            if(Build.VERSION.SDK_INT>=31)media.setRenderEffect(style.blur>0?RenderEffect.createBlurEffect(style.blur,style.blur,style.blurEdgeMode.equals("clamp")?Shader.TileMode.CLAMP:Shader.TileMode.DECAL):null);
            if(presentation!=null){
                entry.setPosition(0,0,contentWidth,contentHeight);entry.setPivotX(contentWidth/2f);entry.setPivotY(contentHeight/2f);
                entry.setTranslationX(presentation.left);entry.setTranslationY(presentation.top);entry.setScaleX(presentation.scale);entry.setScaleY(presentation.scale);entry.setAlpha(presentation.opacity);
                RecordingCanvas presented=entry.beginRecording(contentWidth,contentHeight);recordPlane(presented,contentWidth,contentHeight,style);entry.endRecording();
            }
            RecordingCanvas canvas=root.beginRecording(width,height);canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.SRC);canvas.clipRect(0,0,width,height);
            if(presentation==null)recordPlane(canvas,width,height,style);else canvas.drawRenderNode(entry);
            root.endRecording();
            }finally{Timing.stamp(timing,Timing.RECORD_END_NS);}
            Timing.stage(timing,Timing.DRAW_STAGE,Timing.DRAW_BEGIN_NS);
            try{renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();}finally{Timing.stamp(timing,Timing.DRAW_END_NS);}
            Timing.stage(timing,Timing.ACQUIRE_STAGE,Timing.ACQUIRE_BEGIN_NS);
            try(android.media.Image imageOut=images.acquireLatestImage()){
                if(imageOut==null)throw new IllegalStateException("missing_rendered_frame");
                try(android.hardware.HardwareBuffer buffer=imageOut.getHardwareBuffer()){
                    Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));if(hardware==null)throw new IllegalStateException("unavailable_hardware_bitmap");
                    Timing.stamp(timing,Timing.ACQUIRE_END_NS);
                    try{
                        Bitmap software;
                        Timing.stage(timing,Timing.COPY_STAGE,Timing.COPY_BEGIN_NS);
                        try{software=hardware.copy(Bitmap.Config.ARGB_8888,false);}finally{Timing.stamp(timing,Timing.COPY_END_NS);}
                        boolean pixelsRead=false;
                        try{
                            Timing.stage(timing,Timing.PIXELS_STAGE,Timing.PIXELS_BEGIN_NS);
                            try{software.getPixels(pixels,0,tw,0,0,width,height);}finally{Timing.stamp(timing,Timing.PIXELS_END_NS);}
                            pixelsRead=true;
                        }finally{
                            if(timing!=null&&pixelsRead)timing[Timing.FAILED_STAGE]=Timing.CLEANUP_STAGE;
                            Timing.stamp(timing,Timing.CLEANUP_BEGIN_NS);software.recycle();
                        }
                    }finally{if(timing!=null&&timing[Timing.CLEANUP_BEGIN_NS]==0)Timing.stamp(timing,Timing.CLEANUP_BEGIN_NS);hardware.recycle();}
                }
            }finally{
                // Failed acquire/wrap may close a partially acquired resource
                // before its end marker. Later stages remain zero in that case.
                if(timing!=null&&timing[Timing.ACQUIRE_END_NS]==0)Timing.stamp(timing,Timing.ACQUIRE_END_NS);
                if(timing!=null&&timing[Timing.CLEANUP_BEGIN_NS]!=0)Timing.stamp(timing,Timing.CLEANUP_END_NS);
            }
        }
        private void recordPlane(RecordingCanvas canvas,int contentWidth,int contentHeight,Style style){
            canvas.save();canvas.clipRect(0,0,contentWidth,contentHeight);canvas.drawColor(style.base);canvas.drawRenderNode(media);
            if(style.overlayOpacity>0){Paint overlay=new Paint();overlay.setColor((style.overlay&0xffffff)|(Math.round(style.overlayOpacity*255)<<24));canvas.drawRect(0,0,contentWidth,contentHeight,overlay);}
            canvas.restore();
        }
        @Override public void close(){renderer.destroy();root.discardDisplayList();media.discardDisplayList();entry.discardDisplayList();images.close();}
    }
    @Override public void close(){closed=true;main.post(()->{for(Session s:sessions.values())s.close();sessions.clear();parent=null;});worker.shutdown();}
}
