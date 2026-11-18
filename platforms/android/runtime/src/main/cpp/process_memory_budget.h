#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <mutex>
#include <map>
#include <cstddef>

// One ledger per process, shared by Java, the renderer and every QuickJS realm.
// PSS already includes observed residents: only growth since the start of the
// last sample and promises not yet allocated are added to that observation.
namespace podjs_memory {
enum Owner : uint32_t { QuickJs, Image, Graphics, Media, Scratch, Text, OwnerCount };
class Budget {
 public:
  void configure(uint64_t bytes) {
    std::lock_guard lock(mutex_);
    if (configured_) return; // Never reset a live ledger when a second view opens.
    configured_=true; limit_=bytes;
  }
  uint64_t limit() const { std::lock_guard lock(mutex_); return limit_; }
  uint64_t sampleStart() {std::lock_guard lock(mutex_);return ++generation_;}
  void sample(uint64_t pss,uint64_t start) {
    std::lock_guard lock(mutex_); pss_=pss; observed_=start;
    cohorts_.erase(cohorts_.begin(),cohorts_.lower_bound(start));
  }
  bool reserve(uint32_t owner,uint64_t bytes,bool background=false,bool quietQuota=false,uint64_t extraHeadroom=0) {
    std::lock_guard lock(mutex_);
    if(owner>=OwnerCount)return false;
    if(!limit_) { pending_[owner]+=bytes;return true; }
    const uint64_t quotaBytes=quota(owner);
    const uint64_t cap=owner==QuickJs?(background?quotaBytes-std::min<uint64_t>(quotaBytes,4000000):limit_):quotaBytes;
    if(owner==QuickJs&&resident_[owner]+pending_[owner]+bytes>quotaBytes*3/4)pressure_=true;
    if(bytes>cap || resident_[owner]+pending_[owner]>cap-bytes) {if(!quietQuota)pressure_=true;return false;}
    if(bytes>limit_ || extraHeadroom>limit_-bytes) {pressure_=true;return false;}
    const uint64_t margin=((owner==QuickJs&&!background)||owner==Graphics)?4000000:12000000;
    const uint64_t remaining=limit_-bytes-extraHeadroom;
    if(upperLocked(margin)>remaining) {
      pressure_=true;
      // Leave the foreground result and input pumps enough room to process
      // reclamation while there is still space below the budget. Large guest
      // buffers and headless jobs do not borrow this margin.
      if(owner!=QuickJs||background||bytes>32*1024||upperLocked(0)>remaining)return false;
    }
    pending_[owner]+=bytes;return true;
  }
  bool reserveQuickJsStartup(uint64_t bytes) {
    std::lock_guard lock(mutex_);
    pending_[QuickJs]+=bytes;
    if(limit_&&(upperLocked(4000000)>limit_||
                resident_[QuickJs]+pending_[QuickJs]>quota(QuickJs)*3/4))pressure_=true;
    return true;
  }
  uint64_t commit(uint32_t owner,uint64_t bytes) {
    std::lock_guard lock(mutex_);if(owner>=OwnerCount)return 0;
    bytes=std::min(bytes,pending_[owner]);pending_[owner]-=bytes;
    resident_[owner]+=bytes;auto& cohort=cohorts_[generation_];cohort.live+=bytes;cohort.peak=std::max(cohort.peak,cohort.live);return generation_;
  }
  void cancel(uint32_t owner,uint64_t bytes) {
    std::lock_guard lock(mutex_);if(owner<OwnerCount)pending_[owner]-=std::min(bytes,pending_[owner]);
  }
  void release(uint32_t owner,uint64_t bytes,uint64_t generation) {
    std::lock_guard lock(mutex_);if(owner<OwnerCount)resident_[owner]-=std::min(bytes,resident_[owner]);
    auto it=cohorts_.find(generation);if(it!=cohorts_.end())it->second.live-=std::min(bytes,it->second.live);
    // Keep the cohort high-water mark until its PSS observation. Reusable
    // scratch must not be charged cumulatively on every frame. Allocator
    // retention beyond this logical peak is covered by headroom and feedback.
  }
  uint64_t resident(uint32_t owner) const {std::lock_guard lock(mutex_);return owner<OwnerCount?resident_[owner]:0;}
  uint64_t available(uint32_t owner) const {std::lock_guard lock(mutex_);if(owner>=OwnerCount)return 0;if(!limit_)return UINT64_MAX;const auto cap=quota(owner);return cap-std::min(cap,resident_[owner]+pending_[owner]);}
  uint64_t pending() const {std::lock_guard lock(mutex_);uint64_t n=0;for(auto b:pending_)n+=b;return n;}
  uint64_t upper() const {std::lock_guard lock(mutex_);return upperLocked();}
  bool takePressure() {std::lock_guard lock(mutex_);bool p=pressure_;pressure_=false;return p;}
 private:
  uint64_t quota(uint32_t owner) const {
    // Decimal MB; these are ceilings, never independent process budgets.
    static constexpr uint64_t mb[]={32,6,16,32,6,3};
    return limit_*mb[owner]/128;
  }
  uint64_t upperLocked(uint64_t margin=12000000) const {
    uint64_t n=pss_;for(auto it=cohorts_.lower_bound(observed_);it!=cohorts_.end();++it)n+=it->second.peak;for(auto b:pending_)n+=b;
    return n+std::min<uint64_t>(margin,limit_/8);
  }
  mutable std::mutex mutex_;
  bool configured_=false,pressure_=false;
  uint64_t limit_=0,pss_=45000000,generation_=1,observed_=0;
  struct Cohort {uint64_t live=0,peak=0;};
  std::map<uint64_t,Cohort> cohorts_;
  std::array<uint64_t,OwnerCount> resident_{},pending_{};
};
inline Budget process;
// Allocation-time callback used by Rust without crossing JNI per malloc.
inline uint64_t allocation(uint32_t owner,size_t bytes,uint32_t action,uint64_t generation) {
  switch(action){case 0:return process.reserve(owner,bytes);case 1:return process.commit(owner,bytes);
    case 4:return process.reserve(owner,bytes,true);
    case 5:return owner==QuickJs&&process.reserveQuickJsStartup(bytes);
    case 2:process.cancel(owner,bytes);break;case 3:process.release(owner,bytes,generation);break;default:return false;}
  return true;
}
}
