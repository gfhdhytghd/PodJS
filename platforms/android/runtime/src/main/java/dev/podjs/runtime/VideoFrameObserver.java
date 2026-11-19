package dev.podjs.runtime;

import org.json.JSONObject;

/** Video prepare failure and optional state updates independent of settlement. */
interface VideoFrameObserver {
    void fail(String code,String message);
    default void fail(String code,String message,int mirrorIndex){fail(code,message);}
    default void firstFrame(JSONObject value){}
    default void volumeChanged(JSONObject value){}
    default void progress(JSONObject value){}
    default void stateChanged(JSONObject value){}
}
