package dev.podjs.runtime;

import org.json.JSONObject;
import java.util.ArrayDeque;

/** ACTION_UP pixels survive a subsequent pointer down. The guest release binds
 * the matching native snapshot once; late decode can never change its pixels. */
final class PodVideoReleaseFrame {
    private static final class Capture {
        String owner,kind;long generation,time,target,ordinal,seq;boolean visible,ready;
    }
    private final ArrayDeque<Capture> pending=new ArrayDeque<>();
    synchronized void clear(){pending.clear();}
    synchronized void record(String owner,long generation,long time,long targetGeneration,long ordinal,
                boolean visible,boolean ready,String kind){
        Capture c=new Capture();c.owner=owner;c.generation=generation;c.time=time;c.target=targetGeneration;c.ordinal=ordinal;
        c.visible=visible;c.ready=ready;c.kind=kind;pending.add(c);while(pending.size()>8)pending.removeFirst();
    }
    /** Use the original native gesture timestamp, not the sequence encoding. */
    synchronized long bind(long seq){
        long now=android.os.SystemClock.uptimeMillis(),down=PodBenchmark.actionTime(seq);
        for(Capture c:pending)if(c.seq==seq)return c.generation;
        for(Capture c:pending)if(c.seq==0&&c.time>=down&&c.time<=now&&now-c.time<=2000){c.seq=seq;return c.generation;}
        return 0;
    }
    synchronized boolean emit(String owner,PodBenchmark.VideoAction action){
        if(action==null)return false;
        Capture found=null;
        for(Capture c:pending)if(c.seq==action.seq&&c.owner.equals(owner)&&c.generation==action.sourceGeneration){found=c;break;}
        if(found==null)for(Capture c:pending)if(c.seq==0&&c.time>=PodBenchmark.actionTime(action.seq)&&c.owner.equals(owner)&&c.generation==action.sourceGeneration){found=c;break;}
        if(found==null)return false;pending.remove(found);
        long now=android.os.SystemClock.uptimeMillis();if(found.time>now||now-found.time>2000)return false;
        try{return PodBenchmark.emit(action.event("release_frame")
            .put("decoded_visible",action.delta==1&&found.visible)
            .put("playback_ready",action.delta==1&&found.ready)
            .put("frame_kind",action.delta==1?found.kind:"none")
            .put("target_generation",action.delta==1?found.target:0)
            .put("source_ordinal",action.delta==1?found.ordinal:0),found.time);
        }catch(Exception ignored){return false;}
    }
}
