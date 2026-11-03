package dev.podjs.runtime;

/** Cache failures for 30 s, then exponentially back off to five minutes. */
final class FailureBackoff {
    private Exception error; private int failures; private long retry;
    void check()throws Exception {check(System.nanoTime());}
    void check(long now)throws Exception {if(error!=null&&now<retry)throw error;}
    void failed(Exception error){failed(error,System.nanoTime());}
    void failed(Exception error,long now){this.error=error;failures=Math.min(5,failures+1);retry=now+Math.min(300_000_000_000L,30_000_000_000L<<(failures-1));}
    void success(){error=null;failures=0;retry=0;}
}
