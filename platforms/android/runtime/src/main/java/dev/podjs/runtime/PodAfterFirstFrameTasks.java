package dev.podjs.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Process-local evidence jobs; never writes a completion marker to disk. */
final class PodAfterFirstFrameTasks {
    private final Consumer<Runnable> schedule;
    private final Set<String> presented=new HashSet<>(), submitted=new HashSet<>();
    private final Map<String,Runnable> waiting=new HashMap<>();
    PodAfterFirstFrameTasks(Consumer<Runnable> schedule){this.schedule=schedule;}
    void submitOnce(String sandbox,Runnable task) {
        synchronized(this) {
            if(!submitted.add(sandbox))return;
            if(!presented.contains(sandbox)){waiting.put(sandbox,task);return;}
        }
        schedule.accept(task);
    }
    void afterFirstFrame(String sandbox) {
        Runnable task;
        synchronized(this) {
            if(!presented.add(sandbox))return;
            task=waiting.remove(sandbox);
        }
        if(task!=null)schedule.accept(task);
    }
}
