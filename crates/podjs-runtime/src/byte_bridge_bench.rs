//! Opt-in host benchmark. Uses the real QuickJS bindings with cumulative
//! requested allocations (Rust + QuickJS's RustAllocator), excluding startup.
use super::*;
use std::{
    alloc::{GlobalAlloc, Layout, System},
    cell::Cell,
    time::Instant,
};
thread_local! {static ACTIVE:Cell<bool>=const{Cell::new(false)};static ALLOCS:Cell<usize>=const{Cell::new(0)};static BYTES:Cell<usize>=const{Cell::new(0)};}
struct Count;
fn count(n: usize) {
    let _ = ACTIVE.try_with(|v| {
        if v.get() {
            ALLOCS.with(|c| c.set(c.get() + 1));
            BYTES.with(|c| c.set(c.get() + n));
        }
    });
}
unsafe impl GlobalAlloc for Count {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        count(l.size());
        unsafe { System.alloc(l) }
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        count(l.size());
        unsafe { System.alloc_zeroed(l) }
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        unsafe { System.dealloc(p, l) }
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        count(n);
        unsafe { System.realloc(p, l, n) }
    }
}
#[global_allocator]
static ALLOCATOR: Count = Count;
#[test]
#[ignore = "host microbenchmark; run alone with --ignored --nocapture"]
fn byte_bridge_host_benchmark() {
    let rt = rquickjs::Runtime::new_with_alloc(rquickjs::allocator::RustAllocator).unwrap();
    let c = rquickjs::Context::full(&rt).unwrap();
    let bridge = Rc::new(RefCell::new(PodBridge::default()));
    c.with(|ctx|{
  let ns=rquickjs::Object::new(ctx.clone()).unwrap();crate::byte_bridge::install(&ctx,&ns,bridge.clone()).unwrap();
  ns.set("encodeBase64",Function::new(ctx.clone(),encode_base64).unwrap()).unwrap();ns.set("decodeBase64",Function::new(ctx.clone(),decode_base64).unwrap()).unwrap();ctx.globals().set("pod",ns).unwrap();
  // Echo the old host's JSON envelope, including Base64 decode/encode.
  ctx.globals().set("legacy",Function::new(ctx.clone(),|s:String|->String{let v:Value=serde_json::from_str(&s).unwrap();let data=base64::engine::general_purpose::STANDARD.decode(v["dataBase64"].as_str().unwrap()).unwrap();json!({"dataBase64":base64::engine::general_purpose::STANDARD.encode(data)}).to_string()}).unwrap()).unwrap();
  ctx.globals().set("metadata",Function::new(ctx.clone(),|s:String|->String{let v:Value=serde_json::from_str(&s).unwrap();json!({"byteHandle":v["byteHandle"]}).to_string()}).unwrap()).unwrap();
  ctx.eval::<(),_>("globalThis.data=new Uint8Array(65536);for(let i=0;i<data.length;i++)data[i]=i%256;globalThis.raw=()=>pod.takeBytes(JSON.parse(metadata(JSON.stringify({byteHandle:pod.putBytes(data)}))).byteHandle);globalThis.old=()=>pod.decodeBase64(JSON.parse(legacy(JSON.stringify({dataBase64:pod.encodeBase64(data)}))).dataBase64);for(let i=0;i<50;i++){raw();old();}").unwrap();
  for mode in ["old","raw","raw","old"] {
   unsafe {rquickjs::qjs::JS_RunGC(rquickjs::qjs::JS_GetRuntime(ctx.as_raw().as_ptr()));}ALLOCS.with(|c|c.set(0));BYTES.with(|c|c.set(0));ACTIVE.with(|v|v.set(true));let began=Instant::now();
   ctx.eval::<(),_>(format!("for(let i=0;i<2000;i++){{let v={mode}();if(v.length!==65536||v[255]!==255)throw Error('bytes');}}" )).unwrap();
   let elapsed=began.elapsed();ACTIVE.with(|v|v.set(false));
   println!("mode={mode} blocks=2000 blockBytes=65536 usPerBlock={:.3} allocatedBytesPerBlock={} allocationsPerBlock={:.2}",elapsed.as_secs_f64()*1e6/2000.0,BYTES.with(|c|c.get())/2000,ALLOCS.with(|c|c.get()) as f64/2000.0);
  }
 });
    assert!(bridge.borrow().bytes.is_empty_for_test());
}
