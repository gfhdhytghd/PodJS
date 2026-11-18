package dev.podjs.runtime;

@FunctionalInterface
interface PodFileProgress extends java.util.function.LongConsumer {
    default void phase(String name) {}
}
