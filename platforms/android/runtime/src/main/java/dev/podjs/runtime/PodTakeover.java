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
    static String sourcePackage(Context c) {
        try {
            android.os.Bundle metadata=c.getPackageManager().getApplicationInfo(c.getPackageName(),android.content.pm.PackageManager.GET_META_DATA).metaData;
            return metadata==null?c.getPackageName():metadata.getString("dev.podjs.takeover.sourcePackage",c.getPackageName());
        } catch(android.content.pm.PackageManager.NameNotFoundException invalid){throw new IllegalStateException("package_identity_unavailable",invalid);}
    }
    private static PodTakeoverCoordinator coordinator(Context c,File sandbox,ProgressReporter progress) {
        return new PodTakeoverCoordinator(new File(sandbox,"files/package-takeover"),
                () -> detect(c,sandbox),new PodTakeoverConverters(new PodRoomVersionsConverter(new File(sandbox,"databases/watchrss.db"),
                    PodRoomAndroidDb::new,(File generation) -> PodStoreConverter.identity(new File(generation,"stores-source")),progress::roomCopied).withActiveFactory(PodRoomAndroidDb::recoverPublished).withTimings(PodTakeoverDiagnostics::timed),
                    new PodStoreConverter(sandbox,new PodStoreAndroidCrypto(c),
                        "com.lightningstudio.watchrss.podjs.rehearsal".equals(c.getPackageName())&&
                        (c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0).withTimings(PodTakeoverDiagnostics::timed),
                    new PodFileConverter(sandbox,PodRoomAndroidDb::new,(file,faces) -> {
                        for(int i=0;i<faces;i++)if(new android.graphics.Typeface.Builder(file).setTtcIndex(i).build()==null)
                            throw new IOException("font_unloadable");
                    },boundary -> {},progress::fileCopied,c.getPackageName(),sourcePackage(c)).withDiagnostics(line -> android.util.Log.w("PodTakeoverFiles",line))), new PodTakeoverCoordinator.Checkpoint() {
                    public void saved(PodTakeoverCoordinator.Phase phase) {
                        progress.phase(phase);
                        if ((c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                            android.util.Log.i("PodTakeoverPhase", phase.name());
                    }
                    public void timed(String step,long nanos){PodTakeoverDiagnostics.timed(step,nanos);}
                    public void worked(String step,long done,long total){progress.worked(step,done,total);}
                    public void failed(PodTakeoverCoordinator.Phase phase,Exception error) {
                        PodTakeoverDiagnostics.warn(phase.name(),error);
                    }
                },new PodTakeoverBackup(c,sandbox)).withActiveValidation(PodActiveValidationAndroid.policy(c));
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
        PodTakeoverAdmission.Paths result=admission.paths(() -> {
            File root=sandbox(c);
            return PodTakeoverAdmission.Paths.resolve(coordinator(c,root,progress),
                    new File(root,"files/podjs"),new File(root,"databases/podjs.sqlite"));
        });
        // Post-admission only: never change converters, source or backup directories.
        if(!result.data.equals(new File(sandbox(c),"files/podjs")))
            PodWorkManagerTakeover.afterAdmission(c,result.data.getParentFile().getName());
        PodProfileInstaller.afterAdmission(c);
        return result;
    }
    private static PodTakeoverAdmission.Paths paths(Context c) throws Exception {return paths(c,new ProgressReporter(c,null));}
    private static PodTakeoverAdmission.Paths requiredPaths(Context c) {
        try { return paths(c); }
        catch(Exception failure){PodTakeoverDiagnostics.warn("ADMISSION",failure);throw new IllegalStateException("takeover_pending");}
    }
    public static boolean ready(Context c) {
        try { paths(c); return true; }
        catch(Exception failure){PodTakeoverDiagnostics.warn("ADMISSION",failure);return false;}
    }
    public static void requireReady(Context c) { requiredPaths(c); }
    /** Owner-only bounded idle entry: singleton "inactive" or explicit IDs.
     * No guest/JS service exposes this operation. */
    public static PodInactiveGenerationCollector.Result collectInactiveGenerations(Context c,java.util.Collection<String> approved,int maxEntries)throws Exception {
        File sandbox=sandbox(c);
        return coordinator(c,sandbox).collectInactiveGenerations(approved,maxEntries);
    }
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
            if(!progress.failure.isEmpty())result.put("reason",progress.failure);
        } catch(Exception ignored){}
        return result;
    }
    private interface StatusListener { void updated(JSONObject status); }
    private static final class ProgressState {
        volatile String phase="CHECKING",generation="",failure="";
        volatile int heldPercent;
        final AtomicLong roomBytes=new AtomicLong(),fileBytes=new AtomicLong(),totalBytes=new AtomicLong();
        long copied(){return Math.addExact(roomBytes.get(),fileBytes.get());}
        synchronized int percent(){
            int value;
            switch(phase) {
                case "NONE":value=100;break;case "DETECTED":value=5;break;case "SNAPSHOTTED":value=20;break;
                case "COPYING":value=25;break;case "VERIFYING":value=80;break;case "READY":value=93;break;
                case "ACTIVATING":value=95;break;case "ACTIVE":value=99;break;default:value=0;
            }
            heldPercent=Math.max(heldPercent,value);return heldPercent;
        }
    }
    private static final ConcurrentHashMap<String,ProgressState> progressStates=new ConcurrentHashMap<>();
    private static final class ProgressReporter {
        final Context context;final String key;final ProgressState state;final StatusListener listener;
        int savedPercent=-1;long lastPublish;
        ProgressReporter(Context context,StatusListener listener) {
            this.context=context;this.key=context.getApplicationInfo().dataDir;this.listener=listener;
            this.state=progressStates.computeIfAbsent(key,ignored->new ProgressState());
        }
        File root(){return new File(sandbox(context),"files/package-takeover");}
        void restore() {
            try {
                Properties journal=PodTakeoverCoordinator.read(new File(root(),"journal"));
                String generation=journal.getProperty("generation","");
                if(!generation.equals(state.generation))synchronized(state) {
                    state.generation=generation;state.roomBytes.set(0);state.fileBytes.set(0);state.totalBytes.set(0);
                }
                Properties saved=PodTakeoverCoordinator.read(new File(root(),"progress"));
                if("1".equals(saved.getProperty("protocol"))) {
                    state.heldPercent=Math.max(state.heldPercent,Math.max(0,Math.min(99,Integer.parseInt(saved.getProperty("percent","0")))));
                    if(generation.equals(saved.getProperty("generation"))) {
                        state.roomBytes.accumulateAndGet(Long.parseLong(saved.getProperty("roomBytes","0")),Math::max);
                        state.fileBytes.accumulateAndGet(Long.parseLong(saved.getProperty("fileBytes","0")),Math::max);
                    }
                }
                state.phase=journal.getProperty("phase","CHECKING");
            } catch(Exception failure){PodTakeoverDiagnostics.warn("PROGRESS",failure);}
        }
        void reset(){restore();state.failure="";publish(true);}
        void phase(PodTakeoverCoordinator.Phase phase) {restore();state.phase=phase.name();publish(true);}
        void worked(String step,long done,long total) {
            int start,end;
            switch(step) {
                case "snapshot":start=5;end=20;break;
                case "room":start=25;end=55;break;
                case "roomVerify":start=55;end=65;break;
                case "stores":start=65;end=68;break;
                case "files":start=68;end=80;state.totalBytes.set(state.roomBytes.get()+Math.max(total,0));break;
                case "bundle":start=80;end=93;break;
                default:return;
            }
            int value=start+(int)((end-start)*(total<=0?1d:Math.max(0d,Math.min(1d,done/(double)total))));
            synchronized(state){state.heldPercent=Math.max(state.heldPercent,value);}publish(false);
        }
        void roomCopied(long bytes){state.roomBytes.accumulateAndGet(bytes,Math::max);publish(false);}
        void fileCopied(long bytes){state.fileBytes.accumulateAndGet(bytes,Math::max);publish(false);}
        void complete(){state.failure="";state.phase="ACTIVE";state.heldPercent=100;publish(true);}
        void failed(String reason){state.failure=reason;state.phase="PAUSED";publish(true);}
        void publish(boolean force) {
            int percent=state.percent();long now=android.os.SystemClock.uptimeMillis();
            if(!force&&percent==savedPercent&&now-lastPublish<200)return;
            // Percent and byte counters are a display checkpoint, never admission
            // evidence. Persist only for a real migration; fresh boot stays read-only.
            if(!state.generation.isEmpty()&&percent!=savedPercent)try {
                Properties p=new Properties();p.setProperty("protocol","1");p.setProperty("generation",state.generation);
                p.setProperty("percent",Integer.toString(Math.min(99,percent)));p.setProperty("roomBytes",Long.toString(state.roomBytes.get()));p.setProperty("fileBytes",Long.toString(state.fileBytes.get()));
                PodTakeoverCoordinator.atomic(new File(root(),"progress"),p);
            }catch(Exception failure){PodTakeoverDiagnostics.warn("PROGRESS",failure);}
            savedPercent=percent;lastPublish=now;if(listener!=null)listener.updated(status(context));
        }
    }
    private static final ExecutorService takeoverExecutor=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"podjs-package-takeover");thread.setDaemon(true);return thread;
    });

    /** Admission runs before the runtime. A native splash already covers CHECKING;
     * do not inflate/measure a recovery page that a healthy boot never displays. */
    public static boolean showRecoveryIfNeeded(android.app.Activity activity,Runnable admitted) {
        PodTakeoverAdmission cached=admissions.get(activity.getApplicationInfo().dataDir);
        if(cached!=null&&cached.admitted()!=null&&PodWorkManagerTakeover.completedInProcess(activity,cached.admitted().data)) {
            android.util.Log.i("PodJSPerf","startup stage=admission cached=true");
            return false;
        }
        PodSplash splash=PodSplash.of(activity);
        RecoveryController controller=new RecoveryController(activity,admitted);
        // Hosts without a splash keep their existing immediate checking page.
        if(splash==null||!splash.keeping())controller.show(new JSONObject());
        controller.start();
        return true;
    }
    /** Compatibility entry point: successful background admission recreates the Activity. */
    public static boolean showRecoveryIfNeeded(android.app.Activity activity) {
        return showRecoveryIfNeeded(activity,activity::recreate);
    }
    static final class RecoveryController {
        final WeakReference<android.app.Activity> activity;final Runnable admitted;
        // Admission is independent of drawing. Deliver it before a queued first
        // traversal's sync barrier, rather than drawing the checking tree first.
        final android.os.Handler main=android.os.Handler.createAsync(android.os.Looper.getMainLooper());
        final AtomicBoolean running=new AtomicBoolean();
        RecoveryView view;boolean finished;
        final Runnable checking=()->{if(!finished&&running.get()&&alive())show(currentStatus());};
        final Runnable refresh=new Runnable(){public void run(){if(!finished&&running.get()&&alive()){update(status(activity.get()));main.postDelayed(this,200);}}};
        RecoveryController(android.app.Activity activity,Runnable admitted){
            this.activity=new WeakReference<>(activity);this.admitted=admitted;
        }
        boolean alive(){android.app.Activity owner=activity.get();return owner!=null&&!owner.isFinishing()&&!owner.isDestroyed();}
        JSONObject currentStatus(){android.app.Activity owner=activity.get();return owner==null?new JSONObject():status(owner);}
        void show(JSONObject state){
            if(finished||!alive())return;
            android.app.Activity owner=activity.get();
            if(view==null){
                long began=PodStartupTrace.now();view=new RecoveryView(owner);
                view.retry.setOnClickListener(v->start());
                PodStartupTrace.stage("recoveryViewCreate",began);
                began=PodStartupTrace.now();owner.setContentView(view);
                PodStartupTrace.stage("recoverySetContent",began);
            }
            view.render(state,running.get());
        }
        void update(JSONObject state) {
            main.post(()->{
                if(finished||!alive())return;
                PodSplash splash=PodSplash.of(activity.get());
                boolean recovery=PodSplash.isRecoveryState(state);
                if(view!=null||recovery)show(state);
                if(splash!=null)splash.recoveryStatus(state);
            });
        }
        void finish(boolean ready,long posted){
            PodStartupTrace.stage("admissionMainQueueWait",posted);
            main.removeCallbacks(checking);main.removeCallbacks(refresh);
            if(finished||!alive())return;
            if(ready){finished=true;admitted.run();}
            else {
                show(status(activity.get()));
                PodSplash splash=PodSplash.of(activity.get());if(splash!=null)splash.recovery();
            }
        }
        void start() {
            if(finished||!alive()||!running.compareAndSet(false,true))return;
            if(view!=null)view.render(status(activity.get()),true);
            // Retain a bounded checking fallback for unusually slow admission.
            main.postDelayed(checking,120);main.postDelayed(refresh,200);
            Context context=activity.get().getApplicationContext();
            long queued=PodStartupTrace.now();
            takeoverExecutor.execute(()->{
                PodStartupTrace.stage("admissionQueueWait",queued);
                long began=PodStartupTrace.now();
                boolean ready=false;ProgressReporter reporter=new ProgressReporter(context,this::update);reporter.reset();
                try {paths(context,reporter);ready=true;reporter.complete();}
                catch(Exception failure){PodTakeoverDiagnostics.warn("ADMISSION",failure);reporter.failed("takeover_pending".equals(failure.getMessage())?"":"migration_admission_failed");}
                catch(OutOfMemoryError failure){reporter.failed("migration_memory_limit");}
                finally{running.set(false);}
                PodStartupTrace.stage("admissionWorker",began);
                running.set(false);
                boolean result=ready;long posted=PodStartupTrace.now();
                main.post(()->finish(result,posted));
            });
        }
    }
    /** Round-watch recovery page: pure black, progress ring hugging the round edge, centred
     * WatchRSS-palette text (titleMedium 21sp, bodyMedium 14sp, bodySmall 12sp) and an M3 pill retry. */
    private static final class RecoveryView extends android.widget.FrameLayout {
        static final int FOREGROUND=0xfff5f5f5,SECONDARY=0xffb6b6b6,PRIMARY=0xffff6026,TRACK=0xff2a2a2a,ERROR=0xffff8a80;
        final android.widget.TextView title,percent,detail,code,next;final Ring ring;final android.widget.TextView retry;
        RecoveryView(Context context) {
            super(context);setBackgroundColor(0xff000000);float dp=getResources().getDisplayMetrics().density;
            ring=new Ring(context,dp);addView(ring,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
            android.widget.LinearLayout column=new android.widget.LinearLayout(context);column.setOrientation(android.widget.LinearLayout.VERTICAL);column.setGravity(android.view.Gravity.CENTER);
            int side=(int)(36*dp);column.setPadding(side,side,side,side);addView(column,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
            title=text(context,column,21,FOREGROUND,0);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            percent=text(context,column,34,FOREGROUND,(int)(4*dp));
            detail=text(context,column,12,SECONDARY,(int)(2*dp));
            // Support code on its own single line, shrunk to fit, so long codes never add rows on the round screen.
            code=text(context,column,11,SECONDARY,(int)(2*dp));code.setMaxLines(1);
            code.setAutoSizeTextTypeUniformWithConfiguration(7,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            next=text(context,column,12,SECONDARY,(int)(8*dp));
            retry=new android.widget.TextView(context);retry.setText("重试迁移");retry.setTextSize(14);retry.setTextColor(0xff000000);retry.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable pill=new android.graphics.drawable.GradientDrawable();pill.setColor(PRIMARY);pill.setCornerRadius(20*dp);retry.setBackground(pill);
            retry.setPadding((int)(24*dp),0,(int)(24*dp),0);retry.setClickable(true);retry.setFocusable(true);
            android.widget.LinearLayout.LayoutParams button=new android.widget.LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,(int)(36*dp));button.topMargin=(int)(8*dp);column.addView(retry,button);
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
        private static String reasonText(String reason) {
            switch(reason) {
                case "migration_timeout":return "迁移用时过长";
                case "migration_memory_limit":return "内容过大，内存不足";
                case "migration_row_too_large":return "有一项内容过大";
                case "migration_interrupted":return "迁移被系统中断";
                case "stage_failed":return "迁移步骤出错";
                case "space_insufficient":return "存储空间不足";
                case "user_locked":return "手表已锁定";
                case "migration_admission_failed":return "迁移记录校验失败";
                case "migration_storage_invalid":return "迁移目录中有异常文件";
                case "verification_scratch_invalid":return "校验目录中有异常文件";
                case "active_validation_failed":return "迁移后数据校验失败";
                case "unknown_user_files":return "发现无法识别的数据";
                case "target_conflict":return "目标位置已有数据";
                case "store_unsupported_or_failed":return "部分设置无法迁移";
                case "store_needs_reauth_user_confirmation":return "账号需要重新登录";
                default:return "";
            }
        }
        private static String reasonLine(String reason) {
            if(reason.isEmpty())return "";
            String text=reasonText(reason);
            return "原因："+(text.isEmpty()?"未分类错误":text);
        }
        private static String nextStep(String phase,String reason) {
            if("PAUSED".equals(phase)) {
                if("migration_row_too_large".equals(reason)||"migration_storage_invalid".equals(reason)||"verification_scratch_invalid".equals(reason))return "原数据未改动，\n请截图联系支持。";
                return "已完成的部分会保留，可重试或重开应用继续。";
            }
            if(!"BLOCKED".equals(phase))return "迁移期间不会修改原数据，\n请保持应用在前台。";
            if("user_locked".equals(reason))return "请先解锁手表，再重试。";
            if("space_insufficient".equals(reason))return "请清理存储空间，再重试。";
            if("store_needs_reauth_user_confirmation".equals(reason))return "原数据已保留，\n完成后请重新登录。";
            if("active_validation_failed".equals(reason))return "数据已迁移但校验失败，\n请截图联系支持。";
            return "原数据未改动，\n请截图联系支持。";
        }
        void render(JSONObject status,boolean running) {
            String phase=status.optString("progressPhase",status.optString("phase","CHECKING"));
            String durable=status.optString("phase",phase),reason=status.optString("reason","");int value=status.optInt("percent",0);long copied=status.optLong("copiedBytes",0);
            boolean halted="PAUSED".equals(durable)||"BLOCKED".equals(durable)||"PAUSED".equals(phase);
            String displayPhase="PAUSED".equals(durable)||"BLOCKED".equals(durable)?durable:phase;
            title.setText(phaseLabel(displayPhase));percent.setText(value+"%");
            ring.set(value/100f,running&&value==0,"BLOCKED".equals(durable)&&!running?ERROR:PRIMARY);
            // Halted pages drop the numeric readouts (the ring keeps progress) so reason, next step and retry fit the round screen.
            percent.setVisibility(halted?GONE:VISIBLE);
            String bytesLine=!halted&&copied>0?"已复制 "+bytes(copied):"";
            detail.setText(bytesLine+(reason.isEmpty()?"":(bytesLine.isEmpty()?"":"\n")+reasonLine(reason)));detail.setVisibility(detail.length()>0?VISIBLE:GONE);
            code.setText(reason);code.setVisibility(reason.isEmpty()?GONE:VISIBLE);
            next.setText(nextStep(displayPhase,reason));
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
