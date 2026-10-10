package dev.podjs.debug;
import android.content.*;
import android.app.Activity;
import android.os.Bundle;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Debug-source-set-only bridge. No disk persistence, raw intent logging or unbounded queues. */
public final class PodDebugExternal {
 private static final Map<Integer,Request> pending=new LinkedHashMap<>();
 private static int sequence;
 private static final int PROVIDER_TIMEOUT_MS=1500;
 private static final Semaphore dumpWorkers=new Semaphore(32);
 private static Consumer<String> sink;
 private static final android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
 private static boolean enabled(Context c){return (c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;}
 private static final class Request {int id;JSONObject event;BroadcastReceiver.PendingResult broadcast;CountDownLatch done=new CountDownLatch(1);String text="debug_guest_unavailable";boolean delivered,completed;Activity activity;Intent resultData;int resultCode=Activity.RESULT_CANCELED;}
 private static synchronized Request enqueue(Context c,String kind,String action,JSONObject extras,JSONArray args,BroadcastReceiver.PendingResult broadcast){return enqueue(c,kind,action,extras,args,broadcast,4000,null);}
 private static synchronized Request enqueue(Context c,String kind,String action,JSONObject extras,JSONArray args,BroadcastReceiver.PendingResult broadcast,int timeoutMs){return enqueue(c,kind,action,extras,args,broadcast,timeoutMs,null);}
 private static synchronized Request enqueue(Context c,String kind,String action,JSONObject extras,JSONArray args,BroadcastReceiver.PendingResult broadcast,int timeoutMs,Activity activity){
  Request r=new Request();r.broadcast=broadcast;r.activity=activity;
  if(!enabled(c)||pending.size()>=32){finish(r,1,"debug_guest_unavailable");return r;}
  try{r.id=++sequence;r.event=new JSONObject().put("t","debug.external").put("id",r.id).put("deadline",System.currentTimeMillis()+timeoutMs).put("kind",kind).put("action",action).put("extras",extras).put("args",args);pending.put(r.id,r);timer.postDelayed(()->expire(r),timeoutMs);deliver(r);}catch(Exception e){finish(r,1,"debug_invalid_request");}
  return r;
 }
 private static void deliver(Request r){if(sink!=null&&!r.delivered){r.delivered=true;sink.accept(r.event.toString());}}
 private static synchronized void expire(Request r){if(pending.remove(r.id)!=null)finish(r,1,"debug_guest_timeout");}
 private static synchronized void finish(Request r,int code,String text){if(r.completed)return;r.completed=true;r.text=text;if(r.broadcast!=null){r.broadcast.setResultCode(code);r.broadcast.setResultData(text);r.broadcast.finish();r.broadcast=null;}if(r.activity!=null){Activity owner=r.activity;r.activity=null;timer.post(()->{if(!owner.isDestroyed()){owner.setResult(r.resultCode,r.resultData);owner.finish();}});}r.done.countDown();}
 private static synchronized void cancel(Request r){pending.remove(r.id,r);finish(r,1,"debug_guest_cancelled");}
 private static JSONObject extras(Intent i)throws Exception {
  JSONObject extras=new JSONObject();Bundle b=i.getExtras();if(b!=null){if(b.size()>32)throw new IllegalArgumentException();for(String k:b.keySet()){Object v=b.get(k);if(v instanceof String||v instanceof Number||v instanceof Boolean){if(k.length()>256||String.valueOf(v).length()>16384)throw new IllegalArgumentException();extras.put(k,v);}}}return extras;
 }
 public static void intent(Context c,String action,Intent i,BroadcastReceiver.PendingResult result){
  try{enqueue(c,"intent",action,extras(i),new JSONArray(),result);}catch(Exception e){if(result!=null){result.setResultCode(1);result.setResultData("debug_invalid_request");result.finish();}}
 }
 /** An alias remains alive beneath the main UI until the guest replies. Destruction cancels it. */
 public abstract static class ResultActivity extends Activity {
  private Request request;
  protected abstract String mainActivity();
  @Override protected void onCreate(Bundle state){
   super.onCreate(state);
   try{
    if(!enabled(this)||state!=null){finish();return;}
    Bundle metadata=getPackageManager().getActivityInfo(getIntent().getComponent(),android.content.pm.PackageManager.GET_META_DATA).metaData;
    String action=metadata.getString("pod.debug.action");boolean returns=metadata.getBoolean("pod.debug.result",false);
    if(returns){int timeout=metadata.getInt("pod.debug.timeout",120000);if(timeout<4000||timeout>600000)throw new IllegalArgumentException();request=enqueue(this,"intent",action,extras(getIntent()),new JSONArray(),null,timeout,this);}
    else intent(this,action,getIntent(),null);
    if(request==null||!request.completed)startActivity(new Intent(this,Class.forName(mainActivity())).addFlags(returns?Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP:Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    if(!returns)finish();
   }catch(Exception ignored){if(request!=null)cancel(request);finish();}
  }
  @Override protected void onDestroy(){if(request!=null)cancel(request);super.onDestroy();}
 }
 private static void activityResult(Request r,JSONObject result)throws Exception {
  if(r.activity==null||result==null)return;
  Object rawCode=result.get("code");if(!(rawCode instanceof Number)||((Number)rawCode).doubleValue()!=((Number)rawCode).intValue())throw new IllegalArgumentException();
  int code=((Number)rawCode).intValue();JSONObject values=result.optJSONObject("extras");if(result.has("extras")&&values==null)throw new IllegalArgumentException();Intent data=new Intent();
  if(values!=null){if(values.length()>32||values.toString().length()>60000)throw new IllegalArgumentException();Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();Object v=values.get(key);if(key.length()>256||String.valueOf(v).length()>16384)throw new IllegalArgumentException();if(v instanceof String)data.putExtra(key,(String)v);else if(v instanceof Boolean)data.putExtra(key,(Boolean)v);else if(v instanceof Integer)data.putExtra(key,(Integer)v);else if(v instanceof Long)data.putExtra(key,(Long)v);else if(v instanceof Number)data.putExtra(key,((Number)v).doubleValue());else throw new IllegalArgumentException();}}
  r.resultCode=code;r.resultData=data;
 }
 public static synchronized void close(Consumer<String> owner){if(sink!=owner)return;sink=null;for(Request r:pending.values())finish(r,1,"debug_guest_closed");pending.clear();}
 public static synchronized Object execute(Context c,String method,JSONObject args,Consumer<String> next)throws Exception {
  if(!enabled(c))throw new SecurityException();
  if(method.equals("debug.external.subscribe")){sink=next;for(Request r:pending.values())deliver(r);return JSONObject.NULL;}
  if(method.equals("debug.external.unsubscribe")){close(next);return JSONObject.NULL;}
  if(method.equals("debug.external.reply")){Request r=pending.remove(args.getInt("id"));if(r!=null){String text=args.optString("data","");try{activityResult(r,args.optJSONObject("result"));finish(r,args.optInt("code",0),text.length()>60000?text.substring(0,60000):text);}catch(Exception invalid){finish(r,1,"debug_invalid_result");}}return JSONObject.NULL;}
  throw new IllegalArgumentException();
 }
 public static class Provider extends ContentProvider {
  public boolean onCreate(){return true;}
  public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o){return null;}
  public String getType(android.net.Uri u){return null;}
  public android.net.Uri insert(android.net.Uri u,ContentValues v){return null;}
  public int update(android.net.Uri u,ContentValues v,String s,String[] a){return 0;}
  public int delete(android.net.Uri u,String s,String[] a){return 0;}
  public void dump(java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){
   // ActivityThread closes fd immediately after dump returns. Own a duplicate;
   // never retain the caller's PrintWriter or wait for guest work on main.
   if(!dumpWorkers.tryAcquire()){writer.println("debug_guest_unavailable");return;}
   final android.os.ParcelFileDescriptor duplicate;
   try{duplicate=android.os.ParcelFileDescriptor.dup(fd);}catch(java.io.IOException e){dumpWorkers.release();writer.println("debug_output_unavailable");return;}
   Request r=enqueue(getContext(),"dump","",new JSONObject(),new JSONArray(java.util.Arrays.asList(args==null?new String[0]:args)),null,PROVIDER_TIMEOUT_MS);
   Thread worker=new Thread(()->{
    try(java.io.PrintWriter output=new java.io.PrintWriter(new android.os.ParcelFileDescriptor.AutoCloseOutputStream(duplicate))){
     try{if(!r.done.await(PROVIDER_TIMEOUT_MS,TimeUnit.MILLISECONDS))expire(r);}
     catch(InterruptedException e){cancel(r);Thread.currentThread().interrupt();}
     output.println(r.text);
    }finally{dumpWorkers.release();}
   },"PodDebugDump");
   worker.setDaemon(true);
   try{worker.start();}catch(RuntimeException e){cancel(r);try{duplicate.close();}catch(java.io.IOException ignored){}dumpWorkers.release();writer.println("debug_guest_cancelled");}
  }
 }
}
