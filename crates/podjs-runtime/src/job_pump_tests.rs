// Included in lib.rs's tests module: exercise the real C ABI and QuickJS,
// including the retained-event mode, rather than a Promise mock.
fn job_fixture(kind: &str) -> String {
    format!(r#"
      globalThis.done=false; globalThis.inputs=0; globalThis.seen=[];
      globalThis.frame=(buttons)=>{{ if(buttons) inputs++; }};
      const chunk=JSON.stringify(Array.from({{length:1024}},(_,i)=>i));
      let started=false, at=0, checksum=0;
      globalThis.__pocketPumpServices=()=>{{
        const events=JSON.parse(pod.takeEvents()||'[]');
        if(started||!events.length)return; started=true;
        const payload=events[0].value;
        const responseChunks={kind:?}==='response'?JSON.parse(payload):null;
        function step(){{
          if({kind:?}==='chain') {{ for(let k=0;k<256;k++)checksum+=(at+k)&255; }}
          else {{
            const row=JSON.parse({kind:?}==='response'?responseChunks[at%responseChunks.length]:chunk);
            checksum+=row[at%row.length];
          }}
          at++;
          if(at<24000)Promise.resolve().then(step);
          else{{done=true;pod.emit(JSON.stringify({{at,checksum,inputs}}));}}
        }}
        Promise.resolve().then(step);
      }};
    "#)
}

fn job_runtime(source: &str) -> *mut PodRuntime {
    let target=CString::new("android-watch").unwrap();
    let caps=CString::new("[]").unwrap();
    let runtime=pod_runtime_create(&valid_config(&target,&caps));
    validate(runtime,"android-watch",b"pak",source.as_bytes(),&[]);
    assert_eq!(pod_runtime_eval_bundle(runtime,source.as_ptr(),source.len(),std::ptr::null()),OK);
    runtime
}

fn job_start(runtime: *mut PodRuntime) {
    let chunk=serde_json::to_string(&(0..1024).collect::<Vec<_>>()).unwrap();
    // A real ~500KB service response, below the 1MiB host admission limit.
    let value=serde_json::to_string(&vec![chunk;128]).unwrap();
    let event=CString::new(json!({"t":"service.result","id":1,"ok":true,"value":value}).to_string()).unwrap();
    assert_eq!(pod_runtime_post_event(runtime,event.as_ptr()),OK);
}

fn job_responsiveness_case(kind: &str) {
        let runtime=job_runtime(&job_fixture(kind));
        if std::env::var_os("PODJS_TEST_LEGACY_JOB_PUMP").is_none() {
            assert_eq!(pod_runtime_set_job_budget(runtime,64,2000),OK);
        }
        job_start(runtime);
        let began=Instant::now();
        assert_eq!(pod_runtime_pump_service_results(runtime),OK);
        let first_ms=began.elapsed().as_secs_f64()*1000.0;
        let done=runtime_mut(runtime).unwrap().guest.with(|ctx|ctx.globals().get::<_,bool>("done").unwrap());
        eprintln!("watch-hang case={kind} firstSliceMs={first_ms:.3} done={done}");
        // A 50ms input turn gate, fixed before measuring the old implementation.
        // The total workload must NOT finish before input can be delivered.
        assert!(first_ms<50.0&&!done,"{kind}: first turn blocked {first_ms:.3}ms; done={done}");
        let mut input=PodInputFrame {struct_size:std::mem::size_of::<PodInputFrame>() as u32,buttons:1,
            analog:spec::ANALOG_CENTER,touches:std::ptr::null(),touch_count:0,
            rotary_primary_millidegrees:0,rotary_secondary_millidegrees:0};
        assert_eq!(pod_runtime_frame(runtime,&mut input),OK);
        assert_eq!(runtime_mut(runtime).unwrap().guest.with(|ctx|ctx.globals().get::<_,u32>("inputs").unwrap()),1);
        let mut turns=0;
        while pod_runtime_has_pending_jobs(runtime) {
            let began=Instant::now();
            assert_eq!(pod_runtime_pump_jobs(runtime),OK);
            assert!(began.elapsed().as_millis()<50,"continuation exceeded input turn budget");
            turns+=1;assert!(turns<24000,"continuations must make progress");
        }
        let effect: Value=serde_json::from_str(cstr(pod_runtime_poll_effect(runtime)).unwrap()).unwrap();
        let expected=if kind=="chain" {24000u64*32640} else {(0..24000u64).map(|v|v%1024).sum()};
        assert_eq!(effect,json!({"at":24000,"checksum":expected,"inputs":1}));
        assert!(pod_runtime_poll_effect(runtime).is_null());
        assert!(runtime_mut(runtime).unwrap().guest.job_stats().yields>0);
        pod_runtime_destroy(runtime);
}

#[test]
fn interactive_service_chain_yields_to_input(){job_responsiveness_case("chain");}
#[test]
fn interactive_service_response_yields_to_input(){job_responsiveness_case("response");}
#[test]
fn interactive_service_json_yields_to_input(){job_responsiveness_case("json");}

#[test]
fn bounded_jobs_match_legacy_final_bytes_for_chain_response_and_json() {
    for kind in ["chain","response","json"] {
        let mut outputs=Vec::new();
        for bounded in [false,true] {
            let r=job_runtime(&job_fixture(kind));
            if bounded{assert_eq!(pod_runtime_set_job_budget(r,64,2000),OK);}
            job_start(r);assert_eq!(pod_runtime_pump_service_results(r),OK);
            while pod_runtime_has_pending_jobs(r){assert_eq!(pod_runtime_pump_jobs(r),OK);}
            outputs.push(cstr(pod_runtime_poll_effect(r)).unwrap().to_owned());
            assert!(pod_runtime_poll_effect(r).is_null());
            pod_runtime_destroy(r);
        }
        assert_eq!(outputs[0],outputs[1],"final result bytes changed for {kind}");
    }
}

#[test]
fn job_budget_retains_fifo_errors_events_and_exactly_once_completion() {
    assert_eq!(pod_runtime_set_job_budget(std::ptr::null_mut(),1,1),ERR_ARGUMENT);
    assert!(!pod_runtime_has_pending_jobs(std::ptr::null_mut()));
    assert_eq!(pod_runtime_pump_jobs(std::ptr::null_mut()),ERR_ARGUMENT);
    let source=r#"
      globalThis.order=[];globalThis.pumps=0;globalThis.inputs=0;
      globalThis.frame=(buttons)=>{inputs+=buttons;};
      globalThis.__pocketPumpServices=()=>{
        pumps++;pod.takeEvents();
        Promise.resolve().then(()=>{order.push(1);return Promise.resolve(2);})
          .then(v=>{order.push(v);throw Error('expected');})
          .catch(()=>{order.push(3);return 4;})
          .finally(()=>{order.push(4);})
          .then(()=>{order.push(5);pod.emit(JSON.stringify({order,pumps,events:JSON.parse(pod.takeEvents()||'[]')}));});
      };
    "#;
    let r=job_runtime(source);
    assert_eq!(pod_runtime_set_job_budget(r,0,1),ERR_ARGUMENT);
    assert_eq!(pod_runtime_set_job_budget(r,1,0),ERR_ARGUMENT);
    assert_eq!(pod_runtime_set_job_budget(r,4097,1),ERR_ARGUMENT);
    assert_eq!(pod_runtime_set_job_budget(r,1,16001),ERR_ARGUMENT);
    assert_eq!(pod_runtime_set_job_budget(r,1,16000),OK);
    assert_eq!(pod_runtime_pump_service_results(r),OK);
    // Delayed Promise jobs must not steal navigation/lifecycle/future events.
    for event in [json!({"t":"back"}),json!({"t":"future.event","index":2})] {
        let event=CString::new(event.to_string()).unwrap();assert_eq!(pod_runtime_post_event(r,event.as_ptr()),OK);
    }
    let mut turns=0;
    while pod_runtime_has_pending_jobs(r){assert_eq!(pod_runtime_pump_jobs(r),OK);turns+=1;assert!(turns<100);}
    let v:Value=serde_json::from_str(cstr(pod_runtime_poll_effect(r)).unwrap()).unwrap();
    assert_eq!(v,json!({"order":[1,2,3,4,5],"pumps":1,"events":[]}));
    assert_eq!(runtime_mut(r).unwrap().bridge.borrow().events.len(),2);
    assert!(turns>5); // max_jobs=1 stops even with a large time allowance
    assert_eq!(pod_runtime_frame(r,std::ptr::null()),OK);
    assert_eq!(runtime_mut(r).unwrap().frame_number,1);
    pod_runtime_destroy(r);
}

#[test]
fn job_time_budget_and_lifecycle_checkpoint_do_not_wait_for_microtask_quiescence() {
    let source=r#"
      globalThis.count=0;globalThis.checkpoint=0;
      globalThis.frame=()=>{};
      globalThis.__pocketPumpServices=()=>{
        for(const event of JSON.parse(pod.takeEvents()||'[]')){
          if(event.t==='lifecycle')checkpoint++;
          if(event.t==='service.result'){
            function more(){count++;if(count<1000)Promise.resolve().then(more);}
            Promise.resolve().then(more);
          }
        }
      };
    "#;
    let r=job_runtime(source);assert_eq!(pod_runtime_set_job_budget(r,4096,1),OK);
    job_start(r);assert_eq!(pod_runtime_pump_service_results(r),OK);
    let n=runtime_mut(r).unwrap().guest.with(|c|c.globals().get::<_,u32>("count").unwrap());
    assert!(n>0&&n<1000,"time budget must yield before the 4096-job limit");
    assert_eq!(pod_runtime_set_lifecycle(r,2),OK);
    assert_eq!(pod_runtime_pump_services(r),OK);
    assert_eq!(runtime_mut(r).unwrap().guest.with(|c|c.globals().get::<_,u32>("checkpoint").unwrap()),1);
    assert_eq!(pod_runtime_frame(r,std::ptr::null()),IDLE);
    while pod_runtime_has_pending_jobs(r){assert_eq!(pod_runtime_pump_jobs(r),OK);}
    assert_eq!(runtime_mut(r).unwrap().guest.with(|c|c.globals().get::<_,u32>("count").unwrap()),1000);
    pod_runtime_destroy(r);
}

#[test]
fn self_replenishing_jobs_cannot_starve_the_input_that_stops_them() {
    let source=r#"
      globalThis.n=0;let stop=false,started=false;
      globalThis.frame=(buttons)=>{if(buttons)stop=true;};
      globalThis.__pocketPumpServices=()=>{
        if(started)return;started=true;
        function step(){n++;if(!stop)Promise.resolve().then(step);else pod.emit(JSON.stringify({n,stop}));}
        Promise.resolve().then(step);
      };
    "#;
    let r=job_runtime(source);assert_eq!(pod_runtime_set_job_budget(r,4,2000),OK);
    assert_eq!(pod_runtime_pump_service_results(r),OK);
    assert!(pod_runtime_has_pending_jobs(r));
    assert_eq!(runtime_mut(r).unwrap().guest.with(|c|c.globals().get::<_,u32>("n").unwrap()),4);
    let frame=PodInputFrame{struct_size:std::mem::size_of::<PodInputFrame>() as u32,buttons:1,
        analog:spec::ANALOG_CENTER,touches:std::ptr::null(),touch_count:0,
        rotary_primary_millidegrees:0,rotary_secondary_millidegrees:0};
    assert_eq!(pod_runtime_frame(r,&frame),OK);
    assert!(!pod_runtime_has_pending_jobs(r));
    let v:Value=serde_json::from_str(cstr(pod_runtime_poll_effect(r)).unwrap()).unwrap();
    assert_eq!(v,json!({"n":5,"stop":true}));
    pod_runtime_destroy(r);
}

#[test]
fn atomic_job_overrun_is_counted_without_interrupting_its_state_mutations() {
    let source=r#"
      globalThis.state=0;globalThis.frame=()=>{};
      globalThis.__pocketPumpServices=()=>{
        Promise.resolve().then(()=>{state=1;testAtomicWork();state=2;})
          .then(()=>pod.emit(JSON.stringify({state})));
      };
    "#;
    let r=job_runtime(source);
    runtime_mut(r).unwrap().guest.with(|ctx|{
        let work=Function::new(ctx.clone(),||{
            let began=Instant::now();while began.elapsed()<std::time::Duration::from_millis(80){std::hint::spin_loop();}
        }).unwrap();ctx.globals().set("testAtomicWork",work).unwrap();
    });
    assert_eq!(pod_runtime_set_job_budget(r,64,2000),OK);
    let began=Instant::now();assert_eq!(pod_runtime_pump_service_results(r),OK);
    assert!(began.elapsed()>=std::time::Duration::from_millis(80));
    let rt=runtime_mut(r).unwrap();assert!(rt.guest.job_stats().max_job_micros>=80000);
    assert_eq!(rt.guest.with(|c|c.globals().get::<_,u32>("state").unwrap()),2);
    assert!(pod_runtime_has_pending_jobs(r));
    assert_eq!(pod_runtime_pump_jobs(r),OK);
    let v:Value=serde_json::from_str(cstr(pod_runtime_poll_effect(r)).unwrap()).unwrap();assert_eq!(v,json!({"state":2}));
    pod_runtime_destroy(r);
}
