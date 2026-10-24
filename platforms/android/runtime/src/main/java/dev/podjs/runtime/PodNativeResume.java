package dev.podjs.runtime;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
/** Data conversion only: never Intent.parseUri/startActivity, never replay a URL or player session. */
final class PodNativeResume {
    static JSONObject home()throws org.json.JSONException {return new JSONObject().put("version",1).put("kind","home");}
    static JSONObject target(String uri) {
        try {
            if(uri==null||uri.length()>8192||!uri.startsWith("intent:#Intent;")||!uri.endsWith(";end")||uri.matches("(?s).*[\\x00-\\x1f\\x7f].*"))return null;
            Map<String,String> fields=new HashMap<>();
            String body=uri.substring("intent:#Intent;".length(),uri.length()-4);
            for(String token:body.split(";",-1)){
                int eq=token.indexOf('=');if(eq<1)return null;
                String key=decode(token.substring(0,eq)),value=decode(token.substring(eq+1));
                if(fields.put(key,value)!=null)return null;
            }
            String component=fields.remove("component");
            String pkg="com.lightningstudio.watchrss";
            if(component==null||!component.startsWith(pkg+"/"))return null;
            String activity=component.substring(pkg.length()+1);
            if(activity.startsWith(pkg+"."))activity=activity.substring(pkg.length());
            if(!activity.matches("\\.[A-Za-z]+Activity"))return null;
            Set<String> allowed=new HashSet<>();JSONObject result=null;
            switch(activity){
            case ".MainActivity":case ".HomeFeedListActivity":result=home();break;
            case ".DetailActivity":
                allowed.addAll(Arrays.asList("l.itemId","B.fromWatchLater","B.useOriginalContent"));
                String item=fields.get("l.itemId");if(positive(item,16)&&Long.parseLong(item)<=9007199254740991L)result=new JSONObject().put("version",1).put("kind","reader").put("articleId",item);break;
            case ".BiliDetailActivity":case ".BiliPlayerActivity":
                allowed.addAll(Arrays.asList("S.aid","S.bvid","S.cid","S.rssItemId","B.rssMode","S.title","S.owner","S.pageTitle"));
                String aid=fields.get("S.aid"),bvid=fields.get("S.bvid"),cid=fields.get("S.cid");
                JSONObject bili=new JSONObject().put("name","detail");
                if(positive(aid,19))bili.put("aid",aid);
                if(bvid!=null&&bvid.matches("BV[0-9A-Za-z]{10}"))bili.put("bvid",bvid);
                if(positive(cid,19))bili.put("cid",cid);
                if(bili.has("aid")||bili.has("bvid"))result=route("bili",bili);break;
            case ".DouyinDetailActivity":
                allowed.addAll(Arrays.asList("S.awemeId","S.title","S.author","S.summary","S.playUrl","S.coverUrl"));
                if(positive(fields.get("S.awemeId"),32))result=route("douyin",new JSONObject().put("name","detail").put("awemeId",fields.get("S.awemeId")));break;
            case ".DouyinEntryActivity":
                allowed.addAll(Arrays.asList("B.resume_to_video_flow","S.resume_aweme_id"));
                // Stable content only; do not reconstruct an in-flight player from transient URLs.
                String aweme=fields.get("S.resume_aweme_id");
                result=route("douyin",positive(aweme,32)?new JSONObject().put("name","detail").put("awemeId",aweme):new JSONObject().put("name","entry"));break;
            case ".DouyinHistoryActivity":result=route("douyin",new JSONObject().put("name","history"));break;
            default:return null;
            }
            for(Map.Entry<String,String> field:fields.entrySet()){
                if(!allowed.contains(field.getKey()))return null;
                if(field.getKey().startsWith("B.")&&!field.getValue().equals("true")&&!field.getValue().equals("false"))return null;
            }
            return result;
        }catch(Exception invalid){return null;}
    }
    private static JSONObject route(String kind,JSONObject value)throws org.json.JSONException {return new JSONObject().put("version",1).put("kind",kind).put("route",value);}
    private static boolean positive(String value,int max){return value!=null&&value.length()<=max&&value.matches("[1-9][0-9]*");}
    private static String decode(String value)throws Exception{return URLDecoder.decode(value.replace("+","%2B"),StandardCharsets.UTF_8.name());}
}
