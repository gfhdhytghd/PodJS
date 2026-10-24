package dev.podjs.runtime;
import java.util.*;
import org.json.*;
public final class NativeResumeTest {
 static String intent(String activity,String extras){return "intent:#Intent;component=com.lightningstudio.watchrss/."+activity+";"+extras+"end";}
 static void yes(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  yes(PodNativeResume.target(intent("DetailActivity","l.itemId=42;B.fromWatchLater=true;")).getString("articleId").equals("42"));
  yes(PodNativeResume.target(intent("BiliDetailActivity","S.aid=7;S.bvid=;S.cid=8;S.rssItemId=;B.rssMode=false;")).getJSONObject("route").getString("aid").equals("7"));
  JSONObject douyin=PodNativeResume.target(intent("DouyinDetailActivity","S.awemeId=123;S.playUrl=https%3A%2F%2Fprivate.invalid%2Fexpired;")).getJSONObject("route");yes(douyin.length()==2&&!douyin.has("playUrl"));
  yes(PodNativeResume.target(intent("DouyinHistoryActivity","")).getJSONObject("route").getString("name").equals("history"));
  for(String invalid:new String[]{intent("RssPlayerActivity","S.playUrl=https://expired;"),intent("DetailActivity","l.itemId=-1;"),intent("DetailActivity","l.itemId=9007199254740992;"),intent("DetailActivity","l.itemId=42;SEL;"),intent("DetailActivity","l.itemId=42;action=android.intent.action.VIEW;"),intent("DetailActivity","l.itemId=42;l.itemId=43;"),intent("DetailActivity","l.itemId=42;S.unknown=x;"),intent("DetailActivity","l.itemId=%zz;"),intent("MainActivity","").replace("watchrss/","evil/"),"https://example.invalid/#Intent;end"})yes(PodNativeResume.target(invalid)==null);
  Map<String,Object> prefs=new HashMap<>();prefs.put("intent_uri",intent("DetailActivity","l.itemId=42;"));Set<String> unused=new HashSet<>(prefs.keySet());JSONObject kv=new JSONObject();PodStoreMappings.convert("app_resume_state",prefs,unused,kv,new JSONObject(),new JSONObject());yes(unused.isEmpty());yes(kv.getJSONObject("watchrss.resume-state.v1").getString("kind").equals("reader"));
  System.out.println("Native resume: stable whitelist, transient/unsafe fallback and store wiring OK");
 }
}
