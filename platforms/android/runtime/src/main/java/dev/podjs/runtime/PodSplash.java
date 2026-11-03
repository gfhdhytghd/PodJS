package dev.podjs.runtime;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.core.splashscreen.SplashScreen;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.Set;

/** Activity-scoped native splash. No guest/app policy lives here. */
public final class PodSplash {
    private static final WeakHashMap<Activity,PodSplash> hosts=new WeakHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> holds=new HashSet<>();
    private final boolean fade;
    private final float scale;
    private final int duration;
    private boolean texture,done,closed,exitStarted,removed;
    private View providerView;
    private Runnable removeProvider;
    private final Runnable timeout=()->finish("timeout");
    PodSplash(boolean fade,float scale,int duration,int timeoutMs){
        this.fade=fade;this.scale=scale;this.duration=duration;
        main.postDelayed(timeout,timeoutMs);
    }
    public static PodSplash install(Activity activity){
        int enabled=activity.getResources().getIdentifier("pod_splash_enabled","bool",activity.getPackageName());
        if(enabled==0||!activity.getResources().getBoolean(enabled))return null;
        PodSplash gate=new PodSplash(activity.getResources().getBoolean(resource(activity,"pod_splash_fade","bool")),
            Float.parseFloat(activity.getResources().getString(resource(activity,"pod_splash_scale","string"))),
            activity.getResources().getInteger(resource(activity,"pod_splash_duration_ms","integer")),
            activity.getResources().getInteger(resource(activity,"pod_splash_timeout_ms","integer")));
        hosts.put(activity,gate);
        SplashScreen splash=SplashScreen.installSplashScreen(activity);
        // AndroidX's keep predicate cancels preDraw, which also prevents the
        // TextureView surface/guest from booting. Hand off to its native provider
        // at first draw and retain that provider until our content/hold gate opens.
        splash.setKeepOnScreenCondition(()->false);
        splash.setOnExitAnimationListener(provider->gate.attach(provider.getView(),provider::remove));
        android.util.Log.i("PodSplash","installed");return gate;
    }
    private static int resource(Activity a,String name,String type){return a.getResources().getIdentifier(name,type,a.getPackageName());}
    static PodSplash of(Context c){return c instanceof Activity?hosts.get((Activity)c):null;}
    void attach(View view,Runnable remove){
        if(providerView==view)return;
        if(providerView!=null){remove.run();return;}
        providerView=view;removeProvider=remove;
        if(closed){removeOnce();return;}
        view.bringToFront();android.util.Log.i("PodSplash","visible");exitIfReady();
    }
    boolean obscuring(){return keeping()||providerView!=null&&!removed;}
    public boolean keeping(){return !done&&!closed;}
    public void hold(String owner){if(keeping()){if(holds.size()>=32&&!holds.contains(owner))throw new IllegalStateException("splash holds full");holds.add(owner);}}
    public void release(String owner){if(holds.remove(owner))texture=false;check();}
    public void texturePresented(){texture=true;check();}
    private void check(){if(texture&&holds.isEmpty())finish("content");}
    public void recovery(){finish("recovery");}
    void recoveryStatus(org.json.JSONObject state){
        String durable=state.optString("phase","NONE"),progress=state.optString("progressPhase","CHECKING");
        // Routine ACTIVE/no-source CHECKING also mounts RecoveryView. Keep its
        // admission inspection hidden, but expose actual migration or failure.
        if(migrationPhase(durable)||migrationPhase(progress))recovery();
    }
    private static boolean migrationPhase(String phase){return java.util.Arrays.asList("DETECTED","SNAPSHOTTED","COPYING","VERIFYING","READY","ACTIVATING","PAUSED","BLOCKED").contains(phase);}
    private void finish(String reason){if(done||closed)return;done=true;holds.clear();main.removeCallbacks(timeout);android.util.Log.i("PodSplash","release reason="+reason);exitIfReady();}
    private void exitIfReady(){
        if(!done||providerView==null||exitStarted||closed)return;
        exitStarted=true;android.util.Log.i("PodSplash","exit durationMs="+duration+" scale="+scale);
        providerView.animate().alpha(fade?0f:1f).scaleX(scale).scaleY(scale).setDuration(duration).withEndAction(this::removeOnce).start();
        // Animation callbacks may be lost on stop/detach; never retain the provider.
        main.postDelayed(this::removeOnce,duration+100L);
    }
    private void removeOnce(){if(removed||removeProvider==null)return;removed=true;removeProvider.run();android.util.Log.i("PodSplash","removed");}
    public void close(Activity a){closed=true;main.removeCallbacksAndMessages(null);if(providerView!=null)providerView.animate().cancel();removeOnce();hosts.remove(a);}
}
