#include "../platforms/android/runtime/src/main/cpp/process_memory_budget.h"
#include <cassert>
#include <thread>
#include <vector>
#include <atomic>
int main(){
 using namespace podjs_memory;
 Budget b;b.configure(128000000);
 assert(b.reserve(Image,1000000));auto old=b.commit(Image,1000000);
 auto start=b.sampleStart();b.sample(60000000,start);
 assert(b.upper()==72000000); // resident already present in PSS
 assert(b.reserve(Image,2000000));auto fresh=b.commit(Image,2000000);
 assert(b.upper()==74000000);
 b.release(Image,1000000,old);assert(b.upper()==74000000); // no fictitious PSS drop
 b.release(Image,2000000,fresh);assert(b.upper()==74000000);
 for(int frame=0;frame<120;++frame){assert(b.reserve(Image,2000000));auto reused=b.commit(Image,2000000);b.release(Image,2000000,reused);assert(b.upper()==74000000);}
 start=b.sampleStart();b.sample(60000000,start);assert(b.upper()==72000000);
 assert(b.reserve(Image,6000000));assert(!b.reserve(Image,1));
 assert(b.available(Image)==0);b.cancel(Image,6000000);assert(b.pending()==0);assert(b.available(Image)==6000000);
 b.configure(2048000000);assert(b.limit()==128000000);
 Budget media;media.configure(128000000);assert(media.available(Media)==32000000);assert(media.reserve(Media,32000000));assert(!media.reserve(Media,1));media.cancel(Media,32000000);
 Budget optional;optional.configure(128000000);assert(optional.reserve(Media,32000000));assert(!optional.takePressure());
 assert(!optional.reserve(Media,1,false,true));assert(!optional.takePressure());
 optional.cancel(Media,32000000);auto saturated=optional.sampleStart();optional.sample(120000000,saturated);
 assert(!optional.reserve(Media,1,false,true));assert(optional.takePressure());
 Budget priority;priority.configure(128000000);
 assert(priority.reserve(QuickJs,20000000));priority.commit(QuickJs,20000000);
 assert(priority.reserve(QuickJs,8000000,true));priority.commit(QuickJs,8000000);
 assert(!priority.reserve(QuickJs,1,true));assert(priority.reserve(QuickJs,4000000));priority.commit(QuickJs,4000000);
 // Foreground can borrow unused process capacity; an advisory JS partition
 // must not break UI cleanup when TOTAL PSS still has ample headroom.
 assert(priority.available(QuickJs)==0);assert(priority.reserve(QuickJs,4000000));priority.cancel(QuickJs,4000000);
 auto observed=priority.sampleStart();priority.sample(120000000,observed);
 assert(priority.reserve(QuickJs,4000000));priority.commit(QuickJs,4000000);assert(!priority.reserve(QuickJs,1));
 // A codec factory may span several PSS observations. Its unallocated
 // promise stays pending until physical construction reports completion.
 Budget delayed;delayed.configure(128000000);assert(delayed.reserve(Media,6000000));
 auto firstSample=delayed.sampleStart();delayed.sample(60000000,firstSample);assert(delayed.pending()==6000000);assert(delayed.upper()==78000000);
 auto secondSample=delayed.sampleStart();delayed.sample(60000000,secondSample);assert(delayed.upper()==78000000);
 auto created=delayed.commit(Media,6000000);assert(delayed.pending()==0);assert(delayed.upper()==78000000);
 auto residentSample=delayed.sampleStart();delayed.sample(66000000,residentSample);assert(delayed.upper()==78000000);
 delayed.release(Media,6000000,created);assert(delayed.upper()==78000000);
 Budget concurrent;concurrent.configure(128000000);std::atomic<int> accepted=0;
 std::vector<std::thread> threads;
 for(int i=0;i<32;i++)threads.emplace_back([&]{if(concurrent.reserve(Image,1000000))accepted++;});
 for(auto& t:threads)t.join();assert(accepted==6);assert(concurrent.pending()==6000000);
 // An allocation committed after sampling began must remain additional even
 // when its reservation was made before that sample.
 Budget race;race.configure(128000000);assert(race.reserve(Image,1000000));
 auto epoch=race.sampleStart();auto generation=race.commit(Image,1000000);
 race.sample(60000000,epoch);assert(race.upper()==73000000);
 race.release(Image,1000000,generation);assert(race.upper()==73000000);
 auto after=race.sampleStart();race.sample(60000000,after);assert(race.upper()==72000000);
}
