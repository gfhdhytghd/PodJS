package dev.podjs.runtime;
import org.json.JSONArray;
import org.json.JSONObject;
/** Bounded numeric slots. Guest-supplied strings are never copied to logcat. */
final class IdleDiagnostics {
 static String format(JSONObject row,long frames){
  try{
   int version=row.optInt("version");if(version!=1&&version!=2)return null;
   Object elapsed=row.get("elapsedMs");if(!(elapsed instanceof Number))return null;
   double ms=((Number)elapsed).doubleValue();if(!Double.isFinite(ms)||ms!=Math.floor(ms)||ms<1||ms>300000)return null;
   JSONArray counts=row.getJSONArray("counts");if(counts.length()>127)return null;
   boolean[] seen=new boolean[128];StringBuilder result=new StringBuilder(version==2?"metric=js schema=2 windowMs=":"metric=js windowMs=").append((long)ms).append(" nativeFrames=").append(Math.max(0,frames)).append(" counts=");
   for(int i=0;i<counts.length();i++){
    JSONArray pair=counts.getJSONArray(i);if(pair.length()!=2||!(pair.get(0) instanceof Number)||!(pair.get(1) instanceof Number))return null;
    double id=pair.getDouble(0),count=pair.getDouble(1);
    if(!Double.isFinite(id)||id!=Math.floor(id)||id<1||id>127||seen[(int)id]||!Double.isFinite(count)||count!=Math.floor(count)||count<0||count>1000000000)return null;
    seen[(int)id]=true;if(i>0)result.append(',');result.append((int)id).append(':').append((long)count);
   }
   return result.toString();
  }catch(Exception invalid){return null;}
 }
}
