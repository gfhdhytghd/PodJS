package dev.podjs.runtime;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;
/** Platform sharing primitives; the application decides which devices expose its preference. */
final class PodSharingServices {
 private final Context context;
 PodSharingServices(Context context){this.context=context;}
 JSONObject environment()throws Exception{
  PackageManager packages=context.getPackageManager();boolean wear=false;
  String[] libraries=packages.getSystemSharedLibraryNames();if(libraries!=null)for(String name:libraries)if("com.google.android.wearable".equals(name))wear=true;
  if(!wear)for(String marker:new String[]{"com.google.android.wearable.app","com.google.android.wearable.frameworks"})try{packages.getPackageInfo(marker,0);wear=true;break;}catch(Exception ignored){}
  return new JSONObject().put("sdkInt",Build.VERSION.SDK_INT).put("wearOs",wear);
 }
 void text(JSONObject args){
  Object text=args.opt("text"),title=args.opt("chooserTitle");
  if(!(text instanceof String)||((String)text).isEmpty()||(title!=null&&title!=JSONObject.NULL&&!(title instanceof String)))throw new IllegalArgumentException("invalid_share_text");
  if(!(context instanceof Activity))throw new IllegalStateException("sharing_requires_activity");
  Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,(String)text);
  context.startActivity(Intent.createChooser(send,title instanceof String?(String)title:"Share"));
 }
}
