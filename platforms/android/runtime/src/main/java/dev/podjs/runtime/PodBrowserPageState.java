package dev.podjs.runtime;
import org.json.JSONObject;
/** Small owned browser descriptor; provider/URL policy is rechecked at opening. */
final class PodBrowserPageState {
    static JSONObject decode(String saved) {
        JSONObject state=PodModalState.decode(saved);
        try {
            if(state==null || !PodModalState.onlyKeys(state,"v","options","url","scale","x","y","zoom"))return null;
            JSONObject options=state.getJSONObject("options");
            if(!Boolean.TRUE.equals(options.opt("restoreActivity")) || !"inApp".equals(options.opt("mode")))return null;
            if(!(state.opt("url") instanceof String))return null;
            String scale=state.optString("scale","");
            if(!scale.equals("standard")&&!scale.equals("expanded")&&!scale.equals("shrunk"))return null;
            for(String field:new String[]{"x","y"}) {
                Object value=state.opt(field);
                if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).intValue()||((Number)value).intValue()<0)return null;
            }
            Object zoom=state.opt("zoom");
            if(!(zoom instanceof Number))return null;
            double factor=((Number)zoom).doubleValue();
            return Double.isFinite(factor)&&factor>=.01&&factor<=100?state:null;
        }catch(Exception invalid){return null;}
    }
}
