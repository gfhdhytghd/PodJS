package dev.podjs.runtime;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Both transport lanes retain FIFO order. The cursor survives yields so a
 * continuously replenished service-effect lane cannot starve legacy NET (or
 * vice versa), even when one atomic dispatch exhausts the whole time slice. */
final class HostEffectPump {
    private final Supplier<String> effects,network;
    private final BiConsumer<String,Boolean> dispatchEffect;
    private final Consumer<String> dispatchNetwork;
    private final LongSupplier clock;
    private boolean nextNetwork;
    HostEffectPump(Supplier<String> effects,Supplier<String> network,
            BiConsumer<String,Boolean> dispatchEffect,Consumer<String> dispatchNetwork,LongSupplier clock){
        this.effects=effects;this.network=network;this.dispatchEffect=dispatchEffect;
        this.dispatchNetwork=dispatchNetwork;this.clock=clock;
    }
    void reset(){nextNetwork=false;}
    boolean drain(boolean foreground,boolean checkpoint){
        long began=clock.getAsLong();int count=0,empty=0;
        while(checkpoint||(count<64&&(count==0||clock.getAsLong()-began<HostTurnQueue.BUDGET_NS))){
            boolean net=nextNetwork;nextNetwork=!nextNetwork;
            String value=(net?network:effects).get();
            if(value==null){if(++empty==2)return false;continue;}
            empty=0;count++;
            if(net)dispatchNetwork.accept(value);else dispatchEffect.accept(value,foreground);
        }
        // Nothing was prefetched or removed past the budget. One conservative
        // extra turn handles exact-boundary exhaustion without losing work.
        return true;
    }
}
