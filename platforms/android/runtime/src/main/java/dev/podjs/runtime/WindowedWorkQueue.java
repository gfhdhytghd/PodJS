package dev.podjs.runtime;

/** Durable continuation of an opaque authorized job. No credentials or payload
 * are stored here. The host serializes these operations with registration/cancel.
 * Persist the next identity before enqueue; a stale parent can only reconcile it. */
final class WindowedWorkQueue {
    static final long PROGRESS_DELAY_MS=1000, BASE_RETRY_MS=30000, MAX_RETRY_MS=900000, STANDBY_MS=30000;
    enum State { MISSING, WAITING, RUNNING, FINISHED }
    enum Policy { KEEP, REPLACE, APPEND }
    static final class Job {
        final String token,id,parent;final int failures;final long due;final boolean standby,held;
        Job(String token,String id,String parent,int failures,long due,boolean standby,boolean held){this.token=token;this.id=id;this.parent=parent;this.failures=failures;this.due=due;this.standby=standby;this.held=held;}
        Job flags(boolean standby,boolean held){return new Job(token,id,parent,failures,due,standby,held);}
    }
    interface Ports {
        Job read()throws Exception;void write(Job job)throws Exception;void clear()throws Exception;
        State state(String id)throws Exception;String running()throws Exception;long scheduled(String id)throws Exception;
        void enqueue(Job job,Policy policy)throws Exception;String id();long now();
    }
    private final Ports p;
    WindowedWorkQueue(Ports ports){p=ports;}
    static long retryDelay(int failures){return Math.min(MAX_RETRY_MS,BASE_RETRY_MS*(1L<<Math.max(0,Math.min(5,failures-1))));}
    private Job fresh(String token,int failures,long delay,boolean standby,boolean held,String parent){return new Job(token,p.id(),parent,failures,p.now()+delay,standby,held);}
    private void publish(Job job,Policy policy)throws Exception{p.write(job);if(!job.held)p.enqueue(job,policy);}
    void enqueue(String token,boolean standby,boolean held)throws Exception{
        if(token==null||!token.matches("[a-zA-Z0-9_-]{1,96}"))throw new IllegalArgumentException("invalid_manual_token");
        Job old=p.read();
        if(old!=null&&token.equals(old.token)){
            if(held){p.write(old.flags(old.standby&&standby,true));return;}
            if(old.standby&&!standby&&p.state(old.id)!=State.RUNNING){publish(fresh(token,old.failures,0,false,false,null),Policy.REPLACE);return;}
            if(old.standby&&!standby)p.write(old.flags(false,false));
            resume(false);return;
        }
        publish(fresh(token,0,standby?STANDBY_MS:0,standby,held,null),Policy.REPLACE);
    }
    void defer(String token)throws Exception{Job old=p.read();if(old!=null&&old.token.equals(token))p.write(old.flags(old.standby,true));}
    void resume(boolean held)throws Exception{
        Job job=p.read();if(job==null||held)return;
        if(job.held){publish(fresh(job.token,job.failures,0,false,false,null),Policy.REPLACE);return;}
        // Adopt a running legacy request; replace a legacy delayed request so
        // an old WorkManager attempt count cannot retain its multi-hour delay.
        if(job.id==null){String running=p.running();if(running!=null)p.write(new Job(job.token,running,null,job.failures,p.now(),false,false));else publish(fresh(job.token,job.failures,0,false,false,null),Policy.REPLACE);return;}
        State state=p.state(job.id);
        if(state==State.MISSING){p.enqueue(job,job.parent==null?Policy.REPLACE:Policy.APPEND);return;}
        if(state==State.FINISHED){publish(fresh(job.token,job.failures,Math.max(0,Math.min(MAX_RETRY_MS,job.due-p.now())),job.standby,false,null),Policy.REPLACE);return;}
        long scheduled=p.scheduled(job.id);
        if(state==State.WAITING&&scheduled!=Long.MAX_VALUE&&scheduled>p.now()+MAX_RETRY_MS)publish(fresh(job.token,job.failures,Math.max(0,Math.min(MAX_RETRY_MS,job.due-p.now())),job.standby,false,null),Policy.REPLACE);
    }
    boolean start(String token,String id)throws Exception{
        Job job=p.read();if(job==null||!job.token.equals(token)||job.held)return false;
        if(job.id==null){p.write(new Job(token,id,null,job.failures,p.now(),false,false));return true;}
        if(!job.id.equals(id)){resume(false);return false;}
        if(job.standby)p.write(job.flags(false,false));return true;
    }
    boolean next(String token,String id,boolean progress,boolean periodic)throws Exception{
        Job job=p.read();if(job==null||!job.token.equals(token)||job.held)return false;
        if(periodic){if(p.running()!=null)return false;}else if(!id.equals(job.id))return false;
        int failures=progress?0:Math.min(31,job.failures+1);
        publish(fresh(token,failures,progress?PROGRESS_DELAY_MS:retryDelay(failures),false,false,periodic?null:id),periodic?Policy.REPLACE:Policy.APPEND);return true;
    }
    boolean active()throws Exception{return p.running()!=null;}
    boolean yielded(String token,String id)throws Exception{Job job=p.read();if(job==null||!job.token.equals(token)||!id.equals(job.id)||job.held)return false;publish(fresh(token,job.failures,BASE_RETRY_MS,false,false,id),Policy.APPEND);return true;}
    boolean progress(String token,boolean held)throws Exception{
        Job job=p.read();if(job==null){enqueue(token,false,held);return true;}if(!job.token.equals(token))return false;
        if(held||job.held){p.write(new Job(job.token,job.id,job.parent,0,job.due,false,true));return false;}
        return next(token,"foreground",true,true);
    }
    void finished(String token,String id)throws Exception{Job job=p.read();if(job!=null&&job.token.equals(token)&&(job.id==null||job.id.equals(id))&&!job.held)p.clear();}
}
