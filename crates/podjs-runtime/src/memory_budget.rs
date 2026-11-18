//! Host-owned process admission. Default hosts remain unlimited.
use std::sync::atomic::{AtomicUsize,Ordering};
use rquickjs::allocator::{Allocator,RustAllocator};
type Callback=extern "C" fn(u32,usize,u32,u64)->u64;
static CALLBACK:AtomicUsize=AtomicUsize::new(0);
static LIMIT:AtomicUsize=AtomicUsize::new(0);
thread_local!{static DENIALS:std::cell::Cell<usize>=const{std::cell::Cell::new(0)};}
thread_local! {
    static FOREGROUND_STARTUP: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

// QuickJS can fail irrecoverably while loading the context or the app entry point.
// Keep those allocations in the process ledger, and use regular admission again
// before returning to the event loop. Headless jobs keep their normal quota.
pub struct ForegroundStartup {
    previous: bool,
    thread: std::marker::PhantomData<std::rc::Rc<()>>,
}

impl ForegroundStartup {
    pub fn enter() -> Self {
        Self {
            previous: FOREGROUND_STARTUP.with(|active| active.replace(true)),
            thread: std::marker::PhantomData,
        }
    }
}

impl Drop for ForegroundStartup {
    fn drop(&mut self) {
        FOREGROUND_STARTUP.with(|active| active.set(self.previous));
    }
}

pub fn denial_count()->usize{DENIALS.with(|n|n.get())}
pub fn limit()->usize { LIMIT.load(Ordering::Acquire) }
#[unsafe(no_mangle)]
pub extern "C" fn pod_runtime_memory_budget(bytes:usize,callback:Option<Callback>) {
    LIMIT.store(bytes,Ordering::Release);
    if let Some(callback)=callback { CALLBACK.store(callback as usize,Ordering::Release); }
}
fn account(bytes:usize,action:u32,generation:u64)->u64 {account_owner(0,bytes,action,generation)}
fn account_owner(owner:u32,bytes:usize,action:u32,generation:u64)->u64 {
    let callback=CALLBACK.load(Ordering::Acquire);
    let result=if callback==0 {1} else {(unsafe {std::mem::transmute::<usize,Callback>(callback)})(owner,bytes,action,generation)};
    if (action==0||action==4)&&result==0{DENIALS.with(|n|n.set(n.get().saturating_add(1)));}result
}
/// Resident tokens also pin CPU texture copies until the final renderer snapshot dies.
pub struct Lease {owner:u32,bytes:usize,generation:Option<u64>}
impl Lease {
 pub fn reserve(owner:u32,bytes:usize)->Option<Self>{
  Self::reserve_with_action(owner,bytes,0)
 }
 /// Bulk byte snapshots use the shared lower-priority JS lane. Preserve the
 /// foreground allocation headroom needed to construct their JS wrappers.
 pub fn reserve_bulk_js(bytes:usize)->Option<Self>{
  if bytes==0{return Some(Self{owner:0,bytes:0,generation:None})}
  Self::reserve_with_action(0,bytes,4)
 }
 fn reserve_with_action(owner:u32,bytes:usize,action:u32)->Option<Self>{
  if limit()==0{return Some(Self{owner,bytes:0,generation:None})}
  if account_owner(owner,bytes,action,0)==0{return None}
  Some(Self{owner,bytes,generation:None})
 }
 pub fn commit(&mut self){if self.bytes>0&&self.generation.is_none(){self.generation=Some(account_owner(self.owner,self.bytes,1,0));}}
}
impl Drop for Lease {fn drop(&mut self){if self.bytes>0{account_owner(self.owner,self.bytes,if self.generation.is_some(){3}else{2},self.generation.unwrap_or(0));}}}
#[derive(Default)]
pub struct ProcessAllocator {background:bool}
impl ProcessAllocator {
    pub fn background() -> Self { Self { background: true } }

    fn reserve_action(&self) -> u32 {
        if self.background {
            4
        } else if FOREGROUND_STARTUP.with(|active| active.get()) {
            5
        } else {
            0
        }
    }
}
// RustAllocator stores its own aligned header. Reserve rounded payload + header
// before allocation; successful realloc reserves the full replacement peak.
fn charged(size:usize)->Option<usize>{size.checked_add(31).map(|n|n&!15)?.checked_add(16)}
unsafe impl Allocator for ProcessAllocator {
    fn alloc(&mut self,size:usize)->*mut u8 {
        let Some(bytes)=charged(size) else{return std::ptr::null_mut()};
        if account(bytes,self.reserve_action(),0)==0{return std::ptr::null_mut()}
        let p=RustAllocator.alloc(size+16);
        if p.is_null(){account(bytes,2,0);return p}
        let generation=account(bytes,1,0);unsafe{p.cast::<u64>().write(generation);p.add(8).cast::<usize>().write(bytes);p.add(16)}
    }
    fn calloc(&mut self,count:usize,size:usize)->*mut u8 {
        let Some(size)=count.checked_mul(size) else{return std::ptr::null_mut()};
        let p=self.alloc(size);if !p.is_null(){unsafe{p.write_bytes(0,size)}}p
    }
    unsafe fn dealloc(&mut self,p:*mut u8) {
        let raw=unsafe{p.sub(16)};let generation=unsafe{raw.cast::<u64>().read()};let bytes=unsafe{raw.add(8).cast::<usize>().read()};
        unsafe{RustAllocator.dealloc(raw)};account(bytes,3,generation);
    }
    unsafe fn realloc(&mut self,p:*mut u8,size:usize)->*mut u8 {
        let next=self.alloc(size);if next.is_null(){return next}
        unsafe {std::ptr::copy_nonoverlapping(p,next,Self::usable_size(p).min(size));self.dealloc(p)};next
    }
    unsafe fn usable_size(p:*mut u8)->usize{unsafe{RustAllocator::usable_size(p.sub(16))-16}}
}

#[cfg(test)] mod tests {
 use super::*;
 #[test]
 fn startup_admission_is_scoped_to_the_foreground_on_this_thread() {
     let foreground = ProcessAllocator::default();
     let background = ProcessAllocator::background();
     assert_eq!(foreground.reserve_action(), 0);
     {
         let _startup = ForegroundStartup::enter();
         assert_eq!(foreground.reserve_action(), 5);
         assert_eq!(background.reserve_action(), 4);
         drop(ForegroundStartup::enter());
         assert_eq!(foreground.reserve_action(), 5);
         std::thread::spawn(|| assert_eq!(ProcessAllocator::default().reserve_action(), 0))
             .join().unwrap();
     }
     assert_eq!(foreground.reserve_action(), 0);
 }

 #[test] fn allocator_preserves_realloc_and_zeroes_calloc(){unsafe{
  let mut a=ProcessAllocator::default();let p=a.calloc(32,8);assert!(!p.is_null());
  assert!(std::slice::from_raw_parts(p,256).iter().all(|v|*v==0));p.write_bytes(7,256);
  let next=a.realloc(p,1024);assert!(!next.is_null());assert!(std::slice::from_raw_parts(next,256).iter().all(|v|*v==7));
  assert!(ProcessAllocator::usable_size(next)>=1024);a.dealloc(next);
  assert!(a.calloc(usize::MAX,2).is_null());assert!(a.alloc(usize::MAX).is_null());
 }}
}

#[cfg(test)] mod denial_tests {
 use super::*;
 use std::sync::{Arc,atomic::{AtomicIsize,Ordering}};
 struct FailOnce(Arc<AtomicIsize>,bool);
 impl FailOnce {fn denied(&self)->bool {let n=self.0.load(Ordering::Relaxed);if n<0{return false} if self.1&&n==0{return true} self.0.fetch_sub(1,Ordering::Relaxed)==0}}
 unsafe impl Allocator for FailOnce {
  fn alloc(&mut self,n:usize)->*mut u8{if self.denied(){std::ptr::null_mut()}else{RustAllocator.alloc(n)}}
  fn calloc(&mut self,n:usize,s:usize)->*mut u8{if self.denied(){std::ptr::null_mut()}else{RustAllocator.calloc(n,s)}}
  unsafe fn realloc(&mut self,p:*mut u8,n:usize)->*mut u8{if self.denied(){std::ptr::null_mut()}else{unsafe{RustAllocator.realloc(p,n)}}}
  unsafe fn dealloc(&mut self,p:*mut u8){unsafe{RustAllocator.dealloc(p)}}
  unsafe fn usable_size(p:*mut u8)->usize{unsafe{RustAllocator::usable_size(p)}}
 }
 #[test]
 fn registered_promise_reactions_survive_settlement_without_allocation(){
  for (reject, expected) in [(false,"first:42,second:42"),(true,"catch:42") ]{
   let countdown=Arc::new(AtomicIsize::new(-1));
   let guest=pocket_mod::Guest::new_with_alloc(FailOnce(countdown.clone(),true)).unwrap();
   guest.eval("register promise reactions",r#"
    globalThis.received=[];
    const pending=new Promise((resolve,reject)=>{globalThis.succeed=resolve;globalThis.fail=reject;});
    pending.then(value=>received.push('first:'+value),value=>received.push('catch:'+value));
    (async()=>{try{received.push('second:'+(await pending));}catch{}})();
    globalThis.frame=()=>{};
   "#).unwrap();
   guest.with(|ctx|{
    let settle:rquickjs::Function=ctx.globals().get(if reject{"fail"}else{"succeed"}).unwrap();
    countdown.store(0,Ordering::Relaxed);
    let result=settle.call::<_,()>((42,));
    countdown.store(-1,Ordering::Relaxed);
    result.unwrap();
   });
   guest.drain_jobs();
   guest.eval("verify promise reactions",&format!("if(received.join(',')!=={expected:?}) throw Error('registered promise reactions were lost');")).unwrap();
  }
 }
 #[test] fn async_allocation_denials_preserve_recoverable_guest(){
  for arrays in [false,true] {for compile in [false,true] {for persistent in [false,true] {for fail_at in 0..160 {
   eprintln!("arrays={arrays} compile={compile} persistent={persistent} fail_at={fail_at}");
   let countdown=Arc::new(AtomicIsize::new(-1));
   let guest=pocket_mod::Guest::new_with_alloc(FailOnce(countdown.clone(),persistent)).unwrap();
   guest.eval("setup",if arrays{"function A(){};A.prototype={};globalThis.keep=Object.create(A.prototype);async function f(){const a=Reflect.construct(Array,[1,2,3],A);return Array.prototype.slice.call(a);} globalThis.frame=()=>{};"}else{"async function f(){await Promise.resolve(1);return 2;} globalThis.frame=()=>{};"}).unwrap();
   countdown.store(fail_at,Ordering::Relaxed);
   if compile {let _=guest.eval("deny-source","f().then(()=>{},()=>{});");}else{guest.with(|ctx|{let f: rquickjs::Function=ctx.globals().get("f").unwrap();
    if f.call::<_,rquickjs::Value>(()).is_err(){let _=ctx.catch();}
   });}guest.drain_jobs();
   countdown.store(-1,Ordering::Relaxed);guest.drain_jobs();
   guest.eval("recovery","globalThis.recovered=42;").unwrap();
  }}}}
 }
}
