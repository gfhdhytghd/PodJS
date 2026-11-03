package dev.podjs.runtime;

/** No-progress work pays at least 20 times its CPU duration before re-entry.
 * The caller conservatively charges process CPU, including concurrent work.
 * Durable progress/new user intent resets the idle backoff. No polling loop. */
final class CloudCpuBudget {
    static long delayMillis(long cpuMillis,int idleRounds){
        long cpu=Math.max(0,cpuMillis);
        long quiet=cpu>Long.MAX_VALUE/20?Long.MAX_VALUE:cpu*20;
        long backoff=Math.min(900000L,30000L<<Math.max(0,Math.min(5,idleRounds-1)));
        return Math.max(quiet,backoff);
    }
}
