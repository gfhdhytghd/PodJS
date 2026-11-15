package dev.podjs.runtime;

/** Lifecycle permission never changes the user's play/pause intent. */
final class PodVideoBackgroundPolicy {
    static boolean mayPlay(boolean lifecyclePaused,boolean allowed,boolean prewarming){
        return !lifecyclePaused||allowed&&!prewarming;
    }
}
