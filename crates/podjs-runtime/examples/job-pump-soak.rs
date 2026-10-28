//! Deterministic host scheduler soak using production PodRuntime/QuickJS/KV.
//! No Android UI, GPU, real network or user data is accessed. IO completion
//! failures are synthetic host facts; frame input and lifecycle use the C ABI.
use anyhow::{Result, ensure};
use podjs_runtime::*;
use serde_json::{Value,json};
use std::{collections::BTreeMap,ffi::{CStr,CString},path::PathBuf,time::{Duration,Instant}};

const SOURCE:&str=include_str!("job-pump-soak.js");
fn arg(name:&str,default:&str)->String{std::env::args().find_map(|v|v.strip_prefix(&format!("--{name}=")).map(str::to_owned)).unwrap_or_else(||default.into())}
fn ok(code:i32)->Result<()>{ensure!(code==0,"runtime {code}: {}",unsafe{CStr::from_ptr(pod_runtime_last_error())}.to_string_lossy());Ok(())}
fn hash(bytes:&[u8])->String{let mut v=0xcbf29ce484222325u64;for b in bytes{v=(v^u64::from(*b)).wrapping_mul(0x100000001b3);}format!("{v:016x}")}
struct Guest(*mut PodRuntime);
impl Drop for Guest{fn drop(&mut self){pod_runtime_destroy(self.0);}}
struct Scratch(PathBuf);
impl Drop for Scratch{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
fn create(root:&Scratch,legacy:bool)->Result<Guest>{
    let target=CString::new("android-watch")?;let caps=CString::new("[\"data.kv\"]")?;
    let data=CString::new(root.0.to_str().unwrap())?;
    let cfg=PodRuntimeConfig{struct_size:std::mem::size_of::<PodRuntimeConfig>() as u32,target_id:target.as_ptr(),host_abi:ABI_VERSION,
        raster_density:2,physical_width:240,physical_height:240,display_density:1.0,display_shape:0,
        safe_top:0.0,safe_right:0.0,safe_bottom:0.0,safe_left:0.0,data_dir:data.as_ptr(),capabilities_json:caps.as_ptr()};
    let r=pod_runtime_create(&cfg);ensure!(!r.is_null(),"create runtime");let guest=Guest(r);
    if !legacy{ok(pod_runtime_set_job_budget(r,64,2000))?;}
    ok(pod_runtime_load_pak(r,b"pak".as_ptr(),3))?;
    let manifest=CString::new(json!({"target":"android-watch","hostAbi":1,
        "pocketjsRevision":option_env!("PODJS_POCKETJS_REVISION").unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"),
        "pakHash":hash(b"pak"),"bundleHash":hash(SOURCE.as_bytes()),"capabilities":["data.kv"]}).to_string())?;
    ok(pod_runtime_validate_package(r,manifest.as_ptr()))?;
    ok(pod_runtime_eval_bundle(r,SOURCE.as_ptr(),SOURCE.len(),std::ptr::null()))?;
    Ok(guest)
}
fn post(r:*mut PodRuntime,event:Value)->Result<()>{let raw=CString::new(event.to_string())?;ok(pod_runtime_post_event(r,raw.as_ptr()))}
fn effects(r:*mut PodRuntime)->Result<Vec<Value>>{
    let mut out=vec![];
    loop{let p=pod_runtime_poll_effect(r);if p.is_null(){break;}out.push(serde_json::from_str(unsafe{CStr::from_ptr(p)}.to_str()?)?);}
    Ok(out)
}
fn cpu_ms()->f64{
    let mut v=libc::timespec{tv_sec:0,tv_nsec:0};
    unsafe{libc::clock_gettime(libc::CLOCK_THREAD_CPUTIME_ID,&mut v);}
    v.tv_sec as f64*1000.0+v.tv_nsec as f64/1_000_000.0
}
fn usage()->libc::rusage{
    let mut value=unsafe{std::mem::zeroed()};
    #[cfg(target_os="linux")] let who=libc::RUSAGE_THREAD;
    #[cfg(not(target_os="linux"))] let who=libc::RUSAGE_SELF;
    unsafe{libc::getrusage(who,&mut value);}
    value
}
fn measured(phase:&str,limit:f64,max:&mut f64,call:impl FnOnce()->i32)->Result<()>{
    let before=usage();let cpu=cpu_ms();let start=Instant::now();ok(call())?;let ms=start.elapsed().as_secs_f64()*1000.0;*max=max.max(ms);
    if ms>=limit {
        let cpu=cpu_ms()-cpu;let after=usage();
        anyhow::bail!("host turn freeze: phase={phase} wallMs={ms:.3} threadCpuMs={cpu:.3} minflt={} majflt={} voluntarySwitches={} involuntarySwitches={} >= fixed {limit:.3}ms",
            after.ru_minflt-before.ru_minflt,after.ru_majflt-before.ru_majflt,after.ru_nvcsw-before.ru_nvcsw,after.ru_nivcsw-before.ru_nivcsw);
    }
    Ok(())
}
fn main()->Result<()>{
    let seconds:u64=arg("seconds","600").parse()?;
    let mut seed:u64=arg("seed","470213").parse()?;let initial_seed=seed;
    let limit:f64=arg("max-turn-ms","50").parse()?;ensure!(seconds>0&&limit>0.0,"positive duration and threshold required");
    let legacy=arg("legacy","0")=="1";let inject=arg("inject-stall","0")=="1";
    let stamp=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_nanos();
    let root=Scratch(std::env::temp_dir().join(format!("podjs-job-soak-{}-{stamp}",std::process::id())));
    std::fs::create_dir(&root.0)?;
    let mut guest=create(&root,legacy)?;
    for event in effects(guest.0)?{ensure!(event==json!({"t":"soak.restored","inputs":0}),"initial guest state mismatch");}
    let start=Instant::now();let mut next_report=30;let(mut step,mut seq,mut acks,mut restarts)=(0u64,0u64,0u64,0u64);
    let mut pending=BTreeMap::<u64,bool>::new();let mut max_ms=0f64;let mut input_acks=0u64;
    let mut actions=[0u64;7];let chunk=serde_json::to_string(&(0..1024).collect::<Vec<_>>())?;
    let workload=if legacy{24000}else{256};let checksum:u64=(0..workload as u64).map(|v|v%1024).sum();
    println!("{}",json!({"phase":"start","seed":initial_seed,"seconds":seconds,"maxTurnMs":limit,"legacy":legacy,"injectStall":inject,"workload":workload}));
    while start.elapsed()<Duration::from_secs(seconds)||!pending.is_empty(){
        seed=seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let action=((seed>>32)%7) as usize;actions[action]+=1;step+=1;
        if start.elapsed()<Duration::from_secs(seconds)&&step%3==0 {
            seq+=1;let online=action!=4;pending.insert(seq,online);
            post(guest.0,json!({"t":"service.result","id":seq,"ok":online,"value":{"seq":seq,"rows":chunk,"hops":workload,"injectStall":inject&&online&&acks==0}}))?;
            measured("service",limit,&mut max_ms,||pod_runtime_pump_service_results(guest.0))?;
        }
        let lifecycle=if action==5{2}else{0};ok(pod_runtime_set_lifecycle(guest.0,lifecycle))?;
        if lifecycle!=0{measured("lifecycle-checkpoint",limit,&mut max_ms,||pod_runtime_pump_services(guest.0))?;}
        else{
            let input=PodInputFrame{struct_size:std::mem::size_of::<PodInputFrame>() as u32,buttons:(action+1) as u32,
                analog:0,touches:std::ptr::null(),touch_count:0,rotary_primary_millidegrees:0,rotary_secondary_millidegrees:0};
            measured("input-frame",limit,&mut max_ms,||pod_runtime_frame(guest.0,&input))?;
            input_acks+=1;
        }
        if pod_runtime_has_pending_jobs(guest.0){measured("continuation",limit,&mut max_ms,||pod_runtime_pump_jobs(guest.0))?;}
        for event in effects(guest.0)?{
            match event["t"].as_str(){
                Some("soak.input")=>ensure!(event["inputs"]==input_acks,"input lost/repeated"),
                Some("soak.ack")=>{
                    let id=event["seq"].as_u64().unwrap();let expected=pending.remove(&id).ok_or_else(||anyhow::anyhow!("duplicate/unexpected ACK {id}"))?;
                    ensure!(event["ok"]==expected,"IO outcome changed");
                    ensure!(event["checksum"]==if expected{checksum}else{0},"JSON data checksum changed");acks+=1;
                },
                Some("soak.restored")=>ensure!(event["inputs"]==input_acks,"checkpoint not preserved across realm restart"),
                _=>anyhow::bail!("unexpected guest effect"),
            }
        }
        ensure!(pending.len()<256,"completion starvation/backlog");
        if step%512==0&&pending.is_empty()&&!pod_runtime_has_pending_jobs(guest.0){
            ok(pod_runtime_set_lifecycle(guest.0,2))?;ok(pod_runtime_pump_services(guest.0))?;
            drop(guest);guest=create(&root,legacy)?;restarts+=1;
            for event in effects(guest.0)?{ensure!(event["t"]=="soak.restored"&&event["inputs"]==input_acks,"durable restoration mismatch");}
        }
        if start.elapsed().as_secs()>=next_report {
            println!("{}",json!({"phase":"progress","elapsedSec":start.elapsed().as_secs(),"steps":step,"acks":acks,"pending":pending.len(),"maxTurnMs":max_ms,"restarts":restarts}));next_report+=30;
        }
        ensure!(start.elapsed()<Duration::from_secs(seconds+30),"unfinished work/deadlock after soak");
        // Real host boundary, instead of a busy microtask loop. New input always
        // gets a turn even while the previous IO batch has pending reactions.
        std::thread::sleep(Duration::from_millis(1));
    }
    ensure!(acks==seq&&actions.iter().all(|v|*v>0)&&restarts>0,"incomplete soak coverage");
    println!("{}",json!({"phase":"result","status":"PASS","seed":initial_seed,"elapsedMs":start.elapsed().as_millis(),"steps":step,"actions":actions,"inputs":input_acks,"acks":acks,"maxTurnMs":max_ms,"restarts":restarts,"pending":pending.len(),"scope":"host QuickJS/KV, synthetic IO; no Android/GPU/device"}));
    Ok(())
}
