package dev.podjs.runtime;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;

/** Fixed cache failure categories; never log a Throwable message or source. */
final class PodMediaPrefixFailure {
 static String reason(Throwable error){
  boolean io=false,invalid=false;
  for(int i=0;error!=null&&i<12;i++,error=error.getCause()){
   if(error instanceof PodVideoShortEdge.Rejected)return PodVideoShortEdge.ERROR;
   String message=error.getMessage();
   if(error instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException){
    int status=((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)error).responseCode;
    if(status==401||status==412||status==429)return "http_restricted";
    if(status==403||status==404||status==410)return "http_expired";
    return "http_failure";
   }
   if(error instanceof SecurityException||"media_credentials_origin_rejected".equals(message))return "security";
   if("media_traffic_stopped".equals(message))return "traffic_stopped";
   if("media_request_cancelled".equals(message)||error instanceof java.util.concurrent.CancellationException)return "cancelled";
   if("media_source_expired".equals(message))return "source_expired";
   if("media_cache_metadata_budget".equals(message))return "metadata_budget";
   if("media_cache_registration_missing".equals(message))return "registration_missing";
   if("cache_closed".equals(message))return "closed";
   if("invalid_frame_audio_source".equals(message))return "invalid_audio";
   if("invalid_media_mirror".equals(message)||"too_many_media_mirrors".equals(message))return "invalid_mirror";
   if("invalid_media_cache_headers".equals(message)||"too_many_headers".equals(message))return "invalid_headers";
   if("invalid_media_cache_source".equals(message))return "invalid_source";
   for(String reason:Arrays.asList("key_empty","key_length","scheme","host","credentials","url_length"))if(("invalid_media_cache_source_"+reason).equals(message))return "invalid_source_"+reason;
   io|=error instanceof IOException;invalid|=error instanceof IllegalArgumentException||error instanceof org.json.JSONException;
  }
  return invalid?"invalid_argument":io?"io":"unknown";
 }
 static String code(Throwable error,boolean stopped){
  String reason=reason(error);
  if(reason.equals(PodVideoShortEdge.ERROR))return PodVideoShortEdge.ERROR;
  if(stopped)return "media_traffic_stopped";
  if(reason.equals("security"))return "permission_denied";
  if(reason.equals("http_restricted"))return "restricted";
  if(reason.equals("http_expired"))return "source_expired";
  if(reason.equals("http_failure"))return "network_error";
  if(reason.equals("traffic_stopped"))return "media_traffic_stopped";
  if(reason.equals("cancelled"))return "cancelled";
  if(reason.equals("source_expired"))return "source_expired";
  if(reason.equals("metadata_budget"))return "resource_exhausted";
  if(reason.equals("closed"))return "service_closed";
  if(reason.startsWith("invalid_"))return "invalid_argument";
  if(reason.equals("io")||reason.equals("registration_missing"))return "cache_error";
  return "media_cache_error";
 }
 static int errno(Throwable error){for(int i=0;error!=null&&i<12;i++,error=error.getCause())if(error instanceof android.system.ErrnoException)return Math.max(0,Math.min(65535,((android.system.ErrnoException)error).errno));return 0;}
 static int httpStatus(Throwable error){for(int i=0;error!=null&&i<12;i++,error=error.getCause())if(error instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)return Math.max(0,Math.min(999,((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)error).responseCode));return 0;}
 static void record(String method,Throwable error,PodMediaPrefixCache cache){
  String operation=method.startsWith("video.cache.")?method.substring("video.cache.".length()):"other";
  if(!Arrays.asList("register","window","prepare","inspect","remove","stats","clear","stopTraffic","local.lookup","local.list","local.prepare","local.configure","local.watched","local.clear","local.cancel").contains(operation))operation="other";
  long entries=0,metadata=0;int available=0;
  try{JSONObject stats=cache==null?null:cache.memoryStats();if(stats!=null){entries=stats.optLong("entries",0);metadata=stats.optLong("metadataUtf16BytesUpperBound",0);available=1;}}catch(Exception ignored){}
  android.util.Log.w("PodMediaCache","cache_failure operation="+operation+" reason="+reason(error)+" stats="+available+" entries="+Math.max(0,entries)+" metadata_bytes="+Math.max(0,metadata)+" http_status="+httpStatus(error)+" errno="+errno(error));
 }
}
