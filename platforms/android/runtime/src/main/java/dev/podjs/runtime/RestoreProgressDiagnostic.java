package dev.podjs.runtime;
import org.json.JSONObject;
import java.util.*;

/** Strict numeric mirror for the existing app-log diagnostic, never the supplied text. */
final class RestoreProgressDiagnostic {
 static final String[] FIELDS=("version ready full headsTotal headsDone downloadTotal downloadDone downloadTotalBytes downloadDoneBytes decompressTotal decompressDone decompressTotalBytes decompressDoneBytes applyTotal applyDone applyTotalBytes applyDoneBytes projectionTotal projectionDone projectionTotalBytes projectionDoneBytes").split(" ");
 static String format(JSONObject row) {
  try {
   if(!"restore_progress".equals(row.optString("event"))||row.length()!=FIELDS.length+1)return null;
   StringBuilder line=new StringBuilder("restore_progress");
   for(String key:FIELDS){Object raw=row.get(key);if(!(raw instanceof Number))return null;double n=((Number)raw).doubleValue();long value=((Number)raw).longValue();if(!Double.isFinite(n)||n!=value||value<0||value>9007199254740991L)return null;line.append(' ').append(key).append('=').append(value);}
   if(row.getLong("version")!=1||row.getLong("ready")>1||row.getLong("full")>1)return null;
   return line.toString();
  }catch(Exception invalid){return null;}
 }
}
