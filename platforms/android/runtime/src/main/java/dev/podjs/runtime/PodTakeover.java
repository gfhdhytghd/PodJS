package dev.podjs.runtime;

import android.content.Context;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** All Android entry points and production storage roots use this fail-closed gate.
 * No runtime can coexist with activation: blocked entries never construct a runtime,
 * ACTIVE is terminal, and there is deliberately no guest mutation/activation API. */
public final class PodTakeover {
    private PodTakeover() {}
    /** The app sandbox with symlinks resolved once. Inside the app mount namespace
     * dataDir (/data/user/0/<pkg>) may not be canonical, and every takeover
     * symlink/canonical check below compares against paths under this root. */
    static File sandbox(Context c) {
        File raw=new File(c.getApplicationInfo().dataDir);
        try { return PodTakeoverSource.canonicalSandbox(raw); }
        catch(IOException error){throw new IllegalStateException("sandbox_unresolvable",error);}
    }
    private static PodTakeoverCoordinator coordinator(Context c,File sandbox,ProgressReporter progress) {
        return new PodTakeoverCoordinator(new File(sandbox,"files/package-takeover"),
                () -> detect(c,sandbox),new PodTakeoverConverters(new PodRoomV18Converter(new File(sandbox,"databases/watchrss.db"),
                    PodRoomAndroidDb::new,(File generation) -> PodStoreConverter.identity(new File(generation,"stores-source")),progress::roomCopied),
                    new PodStoreConverter(sandbox,new PodStoreAndroidCrypto(c),
                        "com.lightningstudio.watchrss.podjs.rehearsal".equals(c.getPackageName())&&
                        (c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0),
                    new PodFileConverter(sandbox,PodRoomAndroidDb::new,(file,faces) -> {
                        for(int i=0;i<faces;i++)if(new android.graphics.Typeface.Builder(file).setTtcIndex(i).build()==null)
                            throw new IOException("font_unloadable");
                    },boundary -> {},progress::fileCopied)), phase -> {
                        progress.phase(phase);
                        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                            android.util.Log.i("PodTakeoverPhase", phase.name());
                    },new PodTakeoverBackup(c,sandbox));
    }
    private static PodTakeoverCoordinator coordinator(Context c,File sandbox) {
        return coordinator(c,sandbox,new ProgressReporter(c,null));
    }
    // Keyed by Android's in-memory sandbox path, not Activity identity. Distinct
    // fixture/storage contexts must not accidentally share an admitted generation.
    private static final java.util.concurrent.ConcurrentHashMap<String,PodTakeoverAdmission> admissions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static PodTakeoverAdmission.Paths paths(Context c,ProgressReporter progress) throws Exception {
        PodTakeoverAdmission admission=admissions.computeIfAbsent(c.getApplicationInfo().dataDir,
                ignored -> new PodTakeoverAdmission());
        return admission.paths(() -> {
            File root=sandbox(c);
            return PodTakeoverAdmission.Paths.resolve(coordinator(c,root,progress),
                    new File(root,"files/podjs"),new File(root,"databases/podjs.sqlite"));
        });
    }
    private static PodTakeoverAdmission.Paths paths(Context c) throws Exception {return paths(c,new ProgressReporter(c,null));}
    private static PodTakeoverAdmission.Paths requiredPaths(Context c) {
        try { return paths(c); }
        catch(Exception failure){throw new IllegalStateException("takeover_pending");}
    }
    public static boolean ready(Context c) {
        try { paths(c); return true; }
        catch(Exception failure){android.util.Log.w("PodTakeover","admission refused",failure);return false;}
    }
    public static void requireReady(Context c) { requiredPaths(c); }
    public static File dataRoot(Context c) { return requiredPaths(c).data; }
    public static File filesRoot(Context c) { return requiredPaths(c).files; }
    public static File database(Context c) { return requiredPaths(c).database; }
    /** Read-only public projection: no paths, source values, secrets or mutable operation. */
    public static JSONObject status(Context c) {
        JSONObject result=new JSONObject();
        try {
            Properties p=PodTakeoverCoordinator.read(new File(sandbox(c),"files/package-takeover/journal"));
            result.put("protocol",1).put("phase",p.getProperty("phase","NONE"));
            for(String key:new String[]{"reason","resumePhase","requiredBytes","availableBytes"})
                if(p.containsKey(key))result.put(key,p.getProperty(key));
        } catch(Exception error){try{result.put("phase","BLOCKED").put("reason","journal_unreadable");}catch(Exception ignored){}}
        ProgressState progress=progressStates.get(c.getApplicationInfo().dataDir);
        if(progress!=null)try {
            result.put("progressPhase",progress.phase).put("percent",progress.percent())
                    .put("copiedBytes",progress.copied()).put("totalBytes",progress.totalBytes.get());
        } catch(Exception ignored){}
        return result;
    }
    private interface StatusListener { void updated(JSONObject status); }
    private static final class ProgressState {
        volatile String phase="CHECKING";
        volatile int heldPercent;
        final AtomicLong roomBytes=new AtomicLong(),fileBytes=new AtomicLong(),totalBytes=new AtomicLong();
        long copied(){return Math.addExact(roomBytes.get(),fileBytes.get());}
        int percent(){
            switch(phase) {
                case "NONE":return 100;case "DETECTED":return 5;case "SNAPSHOTTED":return 20;
                case "COPYING":long total=totalBytes.get(),copied=copied();return total<=0?25:(int)Math.min(80,25+(copied*55/Math.max(1,total)));
                case "VERIFYING":return 86;case "READY":return 93;case "ACTIVATING":return 97;case "ACTIVE":return 100;
                case "PAUSED":case "BLOCKED":return heldPercent;default:return 0;
            }
        }
    }
    private static final ConcurrentHashMap<String,ProgressState> progressStates=new ConcurrentHashMap<>();
    private static final class ProgressReporter {
        final Context context;final String key;final ProgressState state;final StatusListener listener;
        ProgressReporter(Context context,StatusListener listener) {
            this.context=context;this.key=context.getApplicationInfo().dataDir;this.listener=listener;
            this.state=progressStates.computeIfAbsent(key,ignored->new ProgressState());
        }
        void reset(){state.phase="CHECKING";state.heldPercent=0;state.roomBytes.set(0);state.fileBytes.set(0);state.totalBytes.set(0);publish();}
        void phase(PodTakeoverCoordinator.Phase phase) {
            int previous=state.percent();if(phase==PodTakeoverCoordinator.Phase.PAUSED||phase==PodTakeoverCoordinator.Phase.BLOCKED)state.heldPercent=previous;
            state.phase=phase.name();
            try {
                Properties p=PodTakeoverCoordinator.read(new File(sandbox(context),"files/package-takeover/journal"));
                long required=Long.parseLong(p.getProperty("requiredBytes","0"));
                state.totalBytes.set(required>32L*1024*1024?(required-32L*1024*1024)/3:0);
            } catch(Exception ignored){}
            publish();
        }
        void roomCopied(long bytes){state.roomBytes.set(bytes);publish();}
        void fileCopied(long bytes){state.fileBytes.addAndGet(bytes);publish();}
        void publish(){if(listener!=null)listener.updated(status(context));}
    }
    private static final ExecutorService takeoverExecutor=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"podjs-package-takeover");thread.setDaemon(true);return thread;
    });

    /** Native fallback is necessary because ordinary guest JS must not run before admission.
     * Cold-process admission always runs on the dedicated takeover thread; onCreate only builds
     * this native view and returns. */
    public static boolean showRecoveryIfNeeded(android.app.Activity activity,Runnable admitted) {
        PodTakeoverAdmission cached=admissions.get(activity.getApplicationInfo().dataDir);
        if(cached!=null&&cached.admitted()!=null) {
            android.util.Log.i("PodJSPerf","startup stage=admission cached=true");
            return false;
        }
        RecoveryView view=new RecoveryView(activity);
        activity.setContentView(view);
        new RecoveryController(activity,view,admitted).start();
        return true;
    }
    /** Compatibility entry point: successful background admission recreates the Activity. */
    public static boolean showRecoveryIfNeeded(android.app.Activity activity) {
        return showRecoveryIfNeeded(activity,activity::recreate);
    }
    private static final class RecoveryController {
        final WeakReference<android.app.Activity> activity;final RecoveryView view;final Runnable admitted;
        final AtomicBoolean running=new AtomicBoolean();
        RecoveryController(android.app.Activity activity,RecoveryView view,Runnable admitted){
            this.activity=new WeakReference<>(activity);this.view=view;this.admitted=admitted;view.retry.setOnClickListener(v->start());
        }
        void update(JSONObject state) {
            android.app.Activity owner=activity.get();if(owner==null)return;
            owner.runOnUiThread(()->{if(!owner.isFinishing()&&!owner.isDestroyed())view.render(state,running.get());});
        }
        void start() {
            if(!running.compareAndSet(false,true))return;
            view.render(new JSONObject(),true);
            android.app.Activity owner=activity.get();if(owner==null){running.set(false);return;}
            Context context=owner.getApplicationContext();
            long queued=PodStartupTrace.now();
            takeoverExecutor.execute(()->{
                PodStartupTrace.stage("admissionQueueWait",queued);
                long began=PodStartupTrace.now();
                boolean ready=false;ProgressReporter reporter=new ProgressReporter(context,this::update);reporter.reset();
                try {paths(context,reporter);ready=true;}
                catch(Exception failure){android.util.Log.w("PodTakeover","background admission pending",failure);}
                PodStartupTrace.stage("admissionWorker",began);
                running.set(false);update(status(context));
                if(ready) {
                    android.app.Activity current=activity.get();
                    long posted=PodStartupTrace.now();
                    if(current!=null)current.runOnUiThread(()->{
                        PodStartupTrace.stage("admissionMainQueueWait",posted);
                        if(!current.isFinishing()&&!current.isDestroyed())admitted.run();
                    });
                }
            });
        }
    }
    /** Round-watch recovery page: pure black, progress ring hugging the round edge, centred
     * WatchRSS-palette text (titleMedium 21sp, bodyMedium 14sp, bodySmall 12sp) and an M3 pill retry. */
    private static final class RecoveryView extends android.widget.FrameLayout {
        static final int FOREGROUND=0xfff5f5f5,SECONDARY=0xffb6b6b6,PRIMARY=0xffff6026,TRACK=0xff2a2a2a,ERROR=0xffff8a80;
        final android.widget.TextView title,percent,detail,next;final Ring ring;final android.widget.TextView retry;
        RecoveryView(Context context) {
            super(context);setBackgroundColor(0xff000000);float dp=getResources().getDisplayMetrics().density;
            ring=new Ring(context,dp);addView(ring,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
            android.widget.LinearLayout column=new android.widget.LinearLayout(context);column.setOrientation(android.widget.LinearLayout.VERTICAL);column.setGravity(android.view.Gravity.CENTER);
            int side=(int)(36*dp);column.setPadding(side,side,side,side);addView(column,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
            title=text(context,column,21,FOREGROUND,0);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            percent=text(context,column,34,FOREGROUND,(int)(4*dp));
            detail=text(context,column,12,SECONDARY,(int)(2*dp));
            next=text(context,column,12,SECONDARY,(int)(8*dp));
            retry=new android.widget.TextView(context);retry.setText("重试迁移");retry.setTextSize(14);retry.setTextColor(0xff000000);retry.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable pill=new android.graphics.drawable.GradientDrawable();pill.setColor(PRIMARY);pill.setCornerRadius(20*dp);retry.setBackground(pill);
            retry.setPadding((int)(24*dp),0,(int)(24*dp),0);retry.setClickable(true);retry.setFocusable(true);
            android.widget.LinearLayout.LayoutParams button=new android.widget.LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,(int)(40*dp));button.topMargin=(int)(10*dp);column.addView(retry,button);
            render(new JSONObject(),true);
        }
        private static android.widget.TextView text(Context context,android.widget.LinearLayout column,int sp,int color,int top) {
            android.widget.TextView view=new android.widget.TextView(context);view.setGravity(android.view.Gravity.CENTER);view.setTextSize(sp);view.setTextColor(color);view.setIncludeFontPadding(false);
            android.widget.LinearLayout.LayoutParams params=new android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);params.topMargin=top;column.addView(view,params);return view;
        }
        /** Edge ring: track + sweep for determinate progress, rotating 90° arc while indeterminate. */
        static final class Ring extends android.view.View {
            final android.graphics.Paint track=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),arc=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.RectF bounds=new android.graphics.RectF();final float stroke,inset;
            float progress;boolean indeterminate=true;int color=PRIMARY;
            Ring(Context context,float dp) {
                super(context);stroke=6*dp;inset=5*dp;
                for(android.graphics.Paint paint:new android.graphics.Paint[]{track,arc}){paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);}
                track.setColor(TRACK);
            }
            void set(float value,boolean spin,int tint){progress=Math.max(0f,Math.min(1f,value));indeterminate=spin;color=tint;invalidate();}
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float size=Math.min(getWidth(),getHeight()),half=stroke/2+inset;
                bounds.set((getWidth()-size)/2+half,(getHeight()-size)/2+half,(getWidth()+size)/2-half,(getHeight()+size)/2-half);
                canvas.drawArc(bounds,0,360,false,track);arc.setColor(color);
                if(indeterminate){float start=(android.os.SystemClock.uptimeMillis()%1400)*360f/1400f-90f;canvas.drawArc(bounds,start,90,false,arc);postInvalidateOnAnimation();}
                else if(progress>0)canvas.drawArc(bounds,-90,360*progress,false,arc);
            }
        }
        private static String bytes(long value) {
            if(value>=1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f GB",value/(1024d*1024*1024));
            if(value>=1024L*1024)return String.format(java.util.Locale.ROOT,"%.1f MB",value/(1024d*1024));
            if(value>=1024)return String.format(java.util.Locale.ROOT,"%.1f KB",value/1024d);return value+" B";
        }
        private static String phaseLabel(String phase) {
            switch(phase) {
                case "CHECKING":case "DETECTED":return "正在检查数据";
                case "SNAPSHOTTED":return "正在准备迁移";
                case "COPYING":return "正在迁移数据";
                case "VERIFYING":return "正在校验数据";
                case "READY":case "ACTIVATING":case "ACTIVE":return "即将完成";
                case "PAUSED":return "迁移已暂停";
                case "BLOCKED":return "迁移未完成";
                default:return "数据迁移";
            }
        }
        private static String nextStep(String phase,String reason) {
            if("PAUSED".equals(phase))return "迁移候选已保留，可安全重试；重启应用也会从日志断点继续。";
            if(!"BLOCKED".equals(phase))return "迁移期间不会修改原应用数据，请保持应用在前台。";
            if("user_locked".equals(reason))return "请先解锁手表，然后重新检查。";
            if("space_insufficient".equals(reason))return "请释放存储空间后重新检查。";
            if("store_needs_reauth_user_confirmation".equals(reason))return "需要重新登录确认；现有候选与原数据均已保留。";
            return "迁移未发布，原数据保持不变；请记录原因并联系支持。";
        }
        void render(JSONObject status,boolean running) {
            String phase=status.optString("progressPhase",status.optString("phase","CHECKING"));
            String durable=status.optString("phase",phase),reason=status.optString("reason","");int value=status.optInt("percent",0);long copied=status.optLong("copiedBytes",0);
            boolean halted="PAUSED".equals(durable)||"BLOCKED".equals(durable);
            title.setText(phaseLabel(halted?durable:phase));percent.setText(value+"%");
            ring.set(value/100f,running&&value==0,"BLOCKED".equals(durable)&&!running?ERROR:PRIMARY);
            detail.setText((copied>0?"已复制 "+bytes(copied):"")+(reason.isEmpty()?"":(copied>0?"\n":"")+"原因："+reason));detail.setVisibility(detail.length()>0?VISIBLE:GONE);
            next.setText(nextStep(durable,reason));
            boolean canRetry=!running&&halted;retry.setVisibility(canRetry?VISIBLE:GONE);retry.setEnabled(canRetry);
            setContentDescription(title.getText()+" "+value+"%");
        }
    }
    static PodTakeoverCoordinator.Source detect(Context c) throws IOException { return detect(c,sandbox(c)); }
    private static PodTakeoverCoordinator.Source detect(Context c,File root) throws IOException {
        // No Context directory getter here: those may mkdir even on read-only preflight.
        android.os.UserManager users=(android.os.UserManager)c.getSystemService(Context.USER_SERVICE);
        return PodTakeoverSource.detect(root,users==null||users.isUserUnlocked());
    }
}
