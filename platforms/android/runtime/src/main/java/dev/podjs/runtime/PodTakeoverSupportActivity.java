package dev.podjs.runtime;

/** Private receipt management, reachable from the admitted application's log history. */
public final class PodTakeoverSupportActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){
        super.onCreate(state);
        setContentView(new PodTakeoverSupportAndroid.Page(this,this::finish,true));
    }
}
