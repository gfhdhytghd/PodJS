package dev.podjs.runtime;

import java.util.*;
import java.util.concurrent.*;

/** Bounded FIFO across Binder callers. No Android dependencies or supplied text in errors. */
final class ShellCommandQueue {
    static void requireUid(int uid) { if(uid!=2000 && uid!=0) throw new SecurityException("denied"); }
    static final class Result {
        final String error,json;
        Result(String error,String json){this.error=error;this.json=json;}
    }
    static final class Request {
        final long id; final String method,arg;
        final CompletableFuture<Result> result=new CompletableFuture<>();
        Request(long id,String method,String arg){this.id=id;this.method=method;this.arg=arg;}
    }
    private final ArrayDeque<Request> pending=new ArrayDeque<>();
    private Request active;
    private long sequence;
    synchronized Request submit(Set<String> commands,String method,String arg) {
        Request request=new Request(++sequence,method,arg);
        if(!commands.contains(method))request.result.complete(new Result("unknown_command",null));
        else if(pending.size()+(active==null?0:1)>=8)request.result.complete(new Result("busy",null));
        else pending.add(request);
        return request;
    }
    synchronized Request next(Set<String> ready) {
        if(active!=null)return null;
        while(!pending.isEmpty() && pending.peek().result.isDone())pending.remove();
        if(pending.isEmpty() || !ready.contains(pending.peek().method))return null;
        return active=pending.remove();
    }
    synchronized void complete(long id,String error,String json) {
        if(active!=null && active.id==id){active.result.complete(new Result(error,json));active=null;}
    }
    synchronized void reject(Request request,String error) {
        pending.remove(request);
        if(active==request)active=null;
        request.result.complete(new Result(error,null));
    }
    synchronized boolean expire(Request request) {
        boolean running=active==request;
        request.result.complete(new Result("timeout",null));
        pending.remove(request);if(running)active=null;
        return running;
    }
    synchronized void detach() {
        if(active!=null){active.result.complete(new Result("unavailable",null));active=null;}
        for(Request request:pending)request.result.complete(new Result("unavailable",null));
        pending.clear();
    }
}
