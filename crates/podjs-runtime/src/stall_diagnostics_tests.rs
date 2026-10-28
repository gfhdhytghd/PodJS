// Included beside the existing real-C-ABI service pump fixtures.
#[test]
fn stall_diagnostics_capture_service_job_and_handle_survives_runtime_drop() {
    use crate::stall_diagnostics::*;
    use std::sync::{Arc,Mutex,mpsc};
    use std::time::Duration;
    unsafe extern "C" fn sink(user:usize,ptr:*const u8,len:usize){
        let out=unsafe{&*(user as *const Mutex<Vec<String>>)};
        out.lock().unwrap().push(String::from_utf8_lossy(unsafe{std::slice::from_raw_parts(ptr,len)}).into());
    }
    let source=r#"
      globalThis.answer=0;
      globalThis.frame=()=>{};
      function serviceResultArtificialLongFunction(){
        diagnosticTest.entered();
        const until=Date.now()+650;while(Date.now()<until){globalThis.answer=42;}
      }
      globalThis.__pocketPumpServices=()=>{if(pod.takeEvents())Promise.resolve().then(serviceResultArtificialLongFunction);};
    "#;
    let runtime=job_runtime(source);
    assert_eq!(pod_runtime_set_job_budget(runtime,64,2000),OK);
    let (tx,rx)=mpsc::channel();
    runtime_mut(runtime).unwrap().guest.mount("diagnosticTest",|ctx,ns|{
        ns.set("entered",Function::new(ctx.clone(),move||{tx.send(()).unwrap();})?)?;Ok(())
    }).unwrap();
    let out=Arc::new(Mutex::new(Vec::<String>::new()));
    assert!(pod_runtime_js_stack_probe(runtime,None,0).is_null());
    assert!(pod_runtime_js_stack_probe(std::ptr::null_mut(),Some(sink),0).is_null());
    let probe=pod_runtime_js_stack_probe(runtime,Some(sink),Arc::as_ptr(&out) as usize);
    assert!(!probe.is_null());
    let addr=probe as usize;
    let watcher=std::thread::spawn(move||{
        rx.recv_timeout(Duration::from_secs(2)).unwrap();std::thread::sleep(Duration::from_millis(320));
        pod_js_stack_probe_request(addr as *const _,2,320);
    });
    job_start(runtime);
    assert_eq!(pod_runtime_pump_service_results(runtime),OK);
    watcher.join().unwrap();
    let lines=out.lock().unwrap();assert_eq!(lines.len(),1);
    assert!(lines[0].contains("serviceResultArtificialLongFunction@"),"{}",lines[0]);
    println!("C_ABI_CAPTURED {}",lines[0]);drop(lines);
    assert_eq!(runtime_mut(runtime).unwrap().guest.with(|ctx|ctx.globals().get::<_,i32>("answer").unwrap()),42);
    pod_runtime_destroy(runtime);
    // This handle owns no runtime pointer. Post-destruction probes cannot
    // dereference QuickJS, and close releases its final Arc.
    pod_js_stack_probe_request(probe,2,1000);pod_js_stack_probe_cancel(probe);pod_js_stack_probe_close(probe);
    pod_js_stack_probe_close(std::ptr::null_mut());
}
