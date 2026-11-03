package dev.podjs.runtime;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Same-process invalidation only. Persistent data remains the source of truth. */
final class ProcessDataSignals {
    private static final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    private static final class Listener {final String owner;final Consumer<String> sink;Listener(String owner,Consumer<String> sink){this.owner=owner;this.sink=sink;}}
    static Runnable subscribe(String owner,Consumer<String> sink){Listener l=new Listener(owner,sink);listeners.add(l);return ()->listeners.remove(l);}
    static void publish(String owner,String topic){
        if(topic==null||!topic.matches("[a-z0-9][a-z0-9.-]{0,63}"))throw new IllegalArgumentException("invalid_data_topic");
        for(Listener l:listeners)if(l.owner.equals(owner))try{l.sink.accept(topic);}catch(RuntimeException ignored){}
    }
}
