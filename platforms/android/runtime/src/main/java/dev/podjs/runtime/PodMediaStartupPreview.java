package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One transient frame-zero bitmap, independent of decoder startup. Main-thread
 * ownership and generation guards prevent a late decode/commit crossing routes. */
final class PodMediaStartupPreview {
    final Context context;final Handler main;final PodMediaPrefixCache cache;
    ViewGroup parent;ImageView image;Bitmap bitmap;String uri;long generation;
    Runnable cancelPaint=()->{};boolean handingOff;Consumer<JSONObject> pending;
    PodMediaStartupPreview(Context context,Handler main,PodMediaPrefixCache cache){this.context=context;this.main=main;this.cache=cache;}
    void attach(ViewGroup parent){this.parent=parent;}
    static void mark(String stage){android.util.Log.i("PodJSPerf","startup stage="+stage+" atMs="+android.os.SystemClock.elapsedRealtime()+" epochMs="+System.currentTimeMillis());}
    // Called on the stream worker. Only a verified complete-store path is accepted.
    void present(JSONObject args,BooleanSupplier active,Consumer<JSONObject> done)throws Exception {
        android.util.Log.i("PodJSPerf","startup stage=storedPreviewRequest atMs="+android.os.SystemClock.elapsedRealtime()+" epochMs="+System.currentTimeMillis()+" guestEntryEpochMs="+args.optLong("guestEntryMs"));
        String source=args.getString("uri");if(!source.startsWith("file:"))throw new IllegalArgumentException("invalid_preview_source");File media=new File(Uri.parse(source).getPath());
        if(!media.getParentFile().equals(cache.localStore.root)||!media.getName().matches("[a-f0-9]{64}\\.mp4"))throw new IllegalArgumentException("invalid_preview_source");
        String token=media.getName().substring(0,64);JSONObject verified=cache.localStore.lookupToken(token);
        if(verified==null)throw new IllegalArgumentException("preview_source_unavailable");
        long began=PodStartupTrace.now();mark("storedImageDecodeStart");
        Bitmap pixels=BitmapFactory.decodeFile(cache.localStore.frame(token).getAbsolutePath());
        PodStartupTrace.stage("storedImageDecode",began);
        if(pixels==null)throw new IllegalArgumentException("preview_decode_failed");
        JSONObject bounds=args.getJSONObject("bounds");
        for(String key:new String[]{"x","y","width","height"}){double n=bounds.optDouble(key,Double.NaN);if(!Double.isFinite(n)||Math.abs(n)>8192||((key.equals("width")||key.equals("height"))&&n<=0)){pixels.recycle();throw new IllegalArgumentException("invalid_preview_bounds");}}
        main.post(()->{
            if(!active.getAsBoolean()||parent==null){pixels.recycle();reply(done,false,0);return;}
            hide(0);long own=++generation;uri=source;bitmap=pixels;pending=done;
            float density=context.getResources().getDisplayMetrics().density;
            int width=Math.max(1,Math.round((float)bounds.optDouble("width")*density)),height=Math.max(1,Math.round((float)bounds.optDouble("height")*density));
            image=new ImageView(context);image.setClickable(false);image.setFocusable(false);image.setBackgroundColor(android.graphics.Color.BLACK);image.setScaleType(ImageView.ScaleType.MATRIX);image.setImageBitmap(pixels);
            FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(width,height);layout.leftMargin=Math.round((float)bounds.optDouble("x")*density);layout.topMargin=Math.round((float)bounds.optDouble("y")*density);parent.addView(image,layout);
            android.graphics.Matrix transform=PodVideoServices.videoTransform(width,height,pixels.getWidth(),pixels.getHeight(),0,"standard",0,0);
            transform.preScale(width/(float)image.getDrawable().getIntrinsicWidth(),height/(float)image.getDrawable().getIntrinsicHeight());image.setImageMatrix(transform);
            PodSplash splash=PodSplash.of(context);if(splash!=null)splash.texturePresented();
            mark("storedImageInstalled");
            final long deadline=android.os.SystemClock.uptimeMillis()+2000;
            Runnable wait=new Runnable(){public void run(){
                if(own!=generation)return;
                if(!active.getAsBoolean()){hide(own);return;}
                if(splash!=null&&splash.obscuring()&&android.os.SystemClock.uptimeMillis()<deadline){main.postDelayed(this,16);return;}
                cancelPaint=PodAfterPaint.await(image,main,1,(frame,at)->{},committed->{
                    if(own!=generation)return;
                    boolean visible=committed&&(splash==null||!splash.obscuring())&&active.getAsBoolean();
                    if(visible){mark("storedImageVisible");pending=null;reply(done,true,own);}else hide(own);
                });
            }};wait.run();
        });
    }
    void raise(){if(image!=null&&parent!=null)parent.bringChildToFront(image);}
    /** Texture update + matching source + window commit, not decoder readiness. */
    void handoff(String source,View texture,BooleanSupplier current){
        if(image==null||handingOff||!uri.equals(source)||!current.getAsBoolean())return;
        long own=generation;handingOff=true;mark("storedVideoTexture");
        cancelPaint=PodAfterPaint.await(texture,main,1,(frame,at)->{},committed->{
            if(own!=generation)return;
            handingOff=false;
            if(committed&&current.getAsBoolean()){mark("storedVideoVisible");hide(own);}
        });
    }
    static void reply(Consumer<JSONObject> done,boolean visible,long token){try{done.accept(new JSONObject().put("presented",visible).put("token",token));}catch(Exception ignored){}}
    void hide(long token){if(token!=0&&token!=generation)return;long previous=generation;Consumer<JSONObject> waiting=pending;pending=null;generation++;cancelPaint.run();cancelPaint=()->{};handingOff=false;uri=null;
        if(image!=null){image.setImageDrawable(null);if(image.getParent() instanceof ViewGroup)((ViewGroup)image.getParent()).removeView(image);image=null;}
        if(bitmap!=null){bitmap.recycle();bitmap=null;}
        if(waiting!=null)reply(waiting,false,previous);
    }
}
