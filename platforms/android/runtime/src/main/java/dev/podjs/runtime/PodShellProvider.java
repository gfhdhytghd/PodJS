package dev.podjs.runtime;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Only call() has a data path. UID is checked before context, arguments, config or logging. */
public final class PodShellProvider extends ContentProvider {
    static final Handler MAIN=new Handler(Looper.getMainLooper());
    static final ShellCommandQueue QUEUE=new ShellCommandQueue();
    private static final Set<String> ready=new HashSet<>();
    private static PodRuntimeView owner;
    private static java.util.function.Consumer<String> events;
    private static final int TIMEOUT_MS=15000;
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        ShellCommandQueue.requireUid(Binder.getCallingUid());
        return authorizedCall(method,arg);
    }
    @Override public Bundle call(String authority,String method,String arg,Bundle extras) {
        ShellCommandQueue.requireUid(Binder.getCallingUid());
        return authorizedCall(method,arg);
    }
    private Bundle authorizedCall(String method,String arg) {
        if(Looper.myLooper()==Looper.getMainLooper())return bundle("unavailable",null);
        try {
            Set<String> commands=new HashSet<>();
            Set<String> readOnlyCommands=new HashSet<>();
            try(java.io.InputStream input=getContext().getAssets().open("pod.manifest.json")) {
                JSONObject manifest=PodManifest.read(input);
                JSONArray names=manifest.optJSONArray("shellCommands");
                JSONArray readOnlyNames=manifest.optJSONArray("shellReadOnlyCommands");
                if(readOnlyNames!=null)for(int i=0;i<readOnlyNames.length();i++)readOnlyCommands.add(readOnlyNames.getString(i));
                if(names!=null)for(int i=0;i<names.length();i++)commands.add(names.getString(i));
            }
            if(!commands.contains(method))return bundle("unknown_command",null);
            if(arg!=null && arg.length()>8192)return bundle("invalid_argument",null);
            String json=arg==null?"{}":new JSONObject(arg).toString();
            ShellCommandQueue.Request request=QUEUE.submit(commands,method,json);
            MAIN.post(()->{
                if(request.result.isDone())return;
                try {
                    // Readiness and launch decisions are serialized with detach/effect on MAIN.
                    if(readOnlyCommands.contains(method)&&(owner==null||!ready.contains(method))) {
                        QUEUE.reject(request,"not_ready");drain();return;
                    }
                    if(owner==null) {
                        Intent launch=getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
                        if(launch==null){QUEUE.detach();return;}
                        getContext().startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    }
                    drain();
                }catch(Exception ignored){QUEUE.detach();}
            });
            ShellCommandQueue.Result result;
            try {result=request.result.get(TIMEOUT_MS,TimeUnit.MILLISECONDS);}
            catch(TimeoutException timeout){cancel(request);return bundle("timeout",null);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();cancel(request);return bundle("unavailable",null);}
            return bundle(result.error,result.json);
        }catch(JSONException invalid){return bundle("invalid_argument",null);}
        catch(Exception ignored){return bundle("unavailable",null);}
    }
    private static void cancel(ShellCommandQueue.Request request) {
        boolean running=QUEUE.expire(request);
        MAIN.post(()->{if(running&&events!=null)try{events.accept(new JSONObject().put("t","shell.cancel").put("id",request.id).toString());}catch(Exception ignored){}drain();});
    }
    static void effect(PodRuntimeView view,JSONObject value,java.util.function.Consumer<String> sink) {
        String type=value.optString("t");
        if("shell.ready".equals(type)) {
            if(owner!=view){if(owner!=null)QUEUE.detach();ready.clear();owner=view;events=sink;}
            JSONArray names=value.optJSONArray("commands");
            ready.clear();if(names!=null)for(int i=0;i<Math.min(64,names.length());i++)ready.add(names.optString(i));
        } else if(owner==view && "shell.result".equals(type)) {
            String error=value.optString("error","");
            if(!Arrays.asList("","invalid_argument","unavailable","not_ready","not_found","command_failed").contains(error))error="command_failed";
            String json=value.optString("json","null");
            if(json.length()>32768){error="result_too_large";json=null;}
            QUEUE.complete(value.optLong("id"),error.isEmpty()?null:error,json);
        }
        drain();
    }
    static void detach(PodRuntimeView view){if(owner==view){owner=null;events=null;ready.clear();QUEUE.detach();}}
    private static void drain() {
        if(events==null)return;
        ShellCommandQueue.Request request=QUEUE.next(ready);if(request==null)return;
        try {events.accept(new JSONObject().put("t","shell.command").put("id",request.id).put("method",request.method).put("args",new JSONObject(request.arg)).toString());}
        catch(Exception ignored){QUEUE.complete(request.id,"unavailable",null);drain();}
    }
    private static Bundle bundle(String error,String json) {
        Bundle result=new Bundle();result.putBoolean("ok",error==null);
        result.putString("error",error==null?"":error);result.putString("json",error==null?(json==null?"null":json):"null");return result;
    }
    // Abstract ContentProvider methods deliberately have no data implementation.
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){throw new SecurityException("denied");}
    @Override public String getType(Uri uri){throw new SecurityException("denied");}
    @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("denied");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new SecurityException("denied");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new SecurityException("denied");}
}
