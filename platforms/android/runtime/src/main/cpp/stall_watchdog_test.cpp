#include "stall_watchdog.h"
#include <cassert>
#include <iostream>
int main(){
  using W=podjs_health::StallWatchdog;
  W w;assert(!w.observe(10*W::thresholdNs)); // static/paused renderer
  w.demand(0);w.demand(W::thresholdNs); // replacements never postpone it
  auto r=w.observe(W::thresholdNs+1);assert(r&&r->stage==W::Stage::queued&&r->stalls==1);
  assert(!w.observe(9*W::thresholdNs)); // only once before progress
  w.progress(9*W::thresholdNs,false);assert(!w.observe(99*W::thresholdNs));
  for(uint64_t i=0;i<32;i++){
    auto now=(i+100)*4*W::thresholdNs;
    w.stage(W::Stage::render,now);w.observe(now+W::thresholdNs+1);w.progress(now+2*W::thresholdNs,false);
  }
  auto summary=w.summary();assert(summary.stalls==33&&summary.logs==W::maxLogs);
  w.stage(W::Stage::control,1000*W::thresholdNs);w.progress(1000*W::thresholdNs+1,true);
  assert(!w.observe(1000*W::thresholdNs+100)); // completed control reset progress
  std::cout<<"renderer watchdog: demand, idle, recovery, starvation, cap PASS\n";
}
