package dev.podjs.runtime;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TreeSet;

/** Main-thread, guest-scoped display leases. No wake lock or synthetic input. */
final class PodScreenServices implements AutoCloseable {
    interface Sink { void changed(JSONObject value); }
    private final Context context;
    private final Activity activity;
    private final Sink sink;
    private final TreeSet<String> owners=new TreeSet<>();
    private boolean resumed,applied,previousFlag,closed;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){ if(!closed)publish(); }
    };
    PodScreenServices(Context context,boolean resumed,Sink sink){
        this.context=context;this.activity=context instanceof Activity?(Activity)context:null;this.resumed=resumed;this.sink=sink;
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(receiver,filter);
    }
    static String owner(JSONObject args)throws Exception{
        Object value=args.opt("owner");if(!(value instanceof String))throw new IllegalArgumentException("owner_required");
        String owner=(String)value;if(owner.trim().isEmpty()||owner.length()>128||owner.indexOf('\0')>=0)throw new IllegalArgumentException("invalid_owner");return owner;
    }
    static boolean flag(JSONObject args,String key){Object value=args.opt(key);if(!(value instanceof Boolean))throw new IllegalArgumentException(key+"_must_be_boolean");return (Boolean)value;}
    JSONObject execute(String method,JSONObject args)throws Exception{
        if(closed)throw new IllegalStateException("Display service closed");
        if("display.keepAwake".equals(method)){
            String owner=owner(args);boolean active=flag(args,"active");
            if(active){if(activity==null||activity.isFinishing()||activity.isDestroyed())throw new IllegalStateException("Activity unavailable");if(!owners.contains(owner)&&owners.size()>=64)throw new IllegalStateException("Too many display owners");owners.add(owner);}else owners.remove(owner);
            update();publish();
        }else if(!"display.state".equals(method))throw new IllegalArgumentException("Unsupported display method");
        return state();
    }
    void lifecycle(boolean resumed){if(closed)return;this.resumed=resumed;update();publish();}
    private boolean windowFlag(){return activity!=null&&(activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0;}
    JSONObject state()throws Exception{
        PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        return new JSONObject().put("interactive",power==null||power.isInteractive()).put("resumed",resumed).put("applied",applied).put("windowKeepScreenOn",windowFlag()).put("owners",new JSONArray(owners));
    }
    private void update(){
        if(!resumed||owners.isEmpty()||activity==null||activity.isFinishing()||activity.isDestroyed()){clear();return;}
        if(!applied){previousFlag=windowFlag();activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);applied=true;}
    }
    private void clear(){if(applied&&activity!=null&&!previousFlag)activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);applied=false;previousFlag=false;}
    private void publish(){try{sink.changed(state());}catch(Exception error){android.util.Log.w("PodJS","Display state delivery failed",error);}}
    @Override public void close(){if(closed)return;closed=true;owners.clear();clear();context.unregisterReceiver(receiver);}
}
