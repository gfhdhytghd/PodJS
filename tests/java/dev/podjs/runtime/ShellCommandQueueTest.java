package dev.podjs.runtime;
import java.util.*;
import java.util.concurrent.*;
public final class ShellCommandQueueTest {
 public static void main(String[] args)throws Exception {
  ShellCommandQueue.requireUid(0);ShellCommandQueue.requireUid(2000);
  for(int uid:new int[]{1000,10000,102000,202000,-1}){try{ShellCommandQueue.requireUid(uid);throw new AssertionError();}catch(SecurityException denied){assert denied.getMessage().equals("denied");}}
  Set<String> names=new HashSet<>(Arrays.asList("demo.go"));ShellCommandQueue q=new ShellCommandQueue();
  assert q.submit(names,"unknown","{}").result.get().error.equals("unknown_command");
  ShellCommandQueue.Request first=q.submit(names,"demo.go","{}"),second=q.submit(names,"demo.go","{}");
  assert q.next(Collections.emptySet())==null;assert q.next(names)==first;assert q.next(names)==null;
  try{first.result.get(5,TimeUnit.MILLISECONDS);throw new AssertionError();}catch(TimeoutException expected){assert q.expire(first);}
  assert first.result.get().error.equals("timeout");assert q.next(names)==second;
  q.complete(first.id,null,"late");assert !second.result.isDone();q.complete(second.id,null,"{}");assert second.result.get().error==null;
  ShellCommandQueue.Request expired=q.submit(names,"demo.go","{}");assert !q.expire(expired);assert q.next(names)==null;
  for(int n=0;n<8;n++)assert !q.submit(names,"demo.go","{}").result.isDone();assert q.submit(names,"demo.go","{}").result.get().error.equals("busy");q.detach();assert q.next(names)==null;
  ServiceWorkStats stats=new ServiceWorkStats();stats.add("sql.gzipJson",1,2,3,0,1,true);assert ServiceWorkStats.phase("sql.gzipJson").equals("sql.gzipJson");assert stats.shellSql().get("sql.gzipJson")[0]==1;stats.emit(10,true,false,line->{});assert stats.shellSql().get("sql.gzipJson")[0]==1;
  System.out.println("ShellCommandQueueTest: UID denial, unknown command, timeout, FIFO, stale reply, queued cancellation, bounded admission, detach, gzipJson counters PASS");
 }
}
