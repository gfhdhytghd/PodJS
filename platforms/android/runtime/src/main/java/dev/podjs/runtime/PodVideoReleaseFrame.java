package dev.podjs.runtime;

import org.json.JSONObject;

/** UI-thread ACTION_UP snapshot, paired once with the guest's release identity.
 * A later pause cannot replace the recorded pixels with a newly decoded frame. */
final class PodVideoReleaseFrame {
    private String owner;
    private long oldGeneration,time,targetGeneration,ordinal;
    private boolean visible,ready;
    private String kind;
    void clear(){owner=null;}
    void record(String owner,long generation,long time,long targetGeneration,long ordinal,
                boolean visible,boolean ready,String kind){
        this.owner=owner;this.oldGeneration=generation;this.time=time;
        this.targetGeneration=targetGeneration;this.ordinal=ordinal;
        this.visible=visible;this.ready=ready;this.kind=kind;
    }
    boolean emit(String owner,PodBenchmark.VideoAction action){
        if(action==null||this.owner==null||!this.owner.equals(owner)||action.sourceGeneration!=oldGeneration)return false;
        this.owner=null;
        long now=android.os.SystemClock.uptimeMillis();
        if(time>now||now-time>2000)return false;
        try{return PodBenchmark.emit(action.event("release_frame")
            .put("decoded_visible",action.delta==1&&visible)
            .put("playback_ready",action.delta==1&&ready)
            .put("frame_kind",action.delta==1?kind:"none")
            .put("target_generation",action.delta==1?targetGeneration:0)
            .put("source_ordinal",action.delta==1?ordinal:0),time);
        }catch(Exception ignored){return false;}
    }
}
