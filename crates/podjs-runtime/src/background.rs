//! Headless QuickJS execution. No UiSurface, renderer, frame loop or unrestricted
//! host surfaces are mounted. OS scheduling and authorized async services belong
//! to host adapters; this module provides the bounded execution boundary.
use rquickjs::{Context, Ctx, Function, Object, Promise, Runtime, Value};
use serde::Serialize;
use std::{cell::RefCell, rc::Rc, collections::HashSet, sync::{mpsc::{Receiver, TryRecvError}, Arc, Mutex, OnceLock, atomic::{AtomicBool, Ordering}}, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};

/// Host-approved asynchronous operations. Dispatch must return immediately and
/// enforce its own IO limits; it must never execute guest-provided code. The
/// cancellation signal is set on every exit, including success with unawaited IO.
pub struct ServiceRequest {
    pub app_id: String, pub task_id: String, pub method: String, pub args_json: String,
    pub deadline: Instant, pub cancellation: Cancellation,
}
pub type ServiceReply = Result<String, String>;
pub struct Services {
    pub allowed_methods: HashSet<String>,
    pub dispatch: Arc<dyn Fn(ServiceRequest) -> Receiver<ServiceReply> + Send + Sync>,
}
struct Pending<'js> { response: Receiver<ServiceReply>, resolve: Function<'js>, reject: Function<'js> }
struct ClearPending<'js>(Rc<RefCell<Vec<Pending<'js>>>>);
impl Drop for ClearPending<'_> { fn drop(&mut self) { self.0.borrow_mut().clear(); } }
struct CancelServices(Cancellation);
fn attach_services<'js>(ctx: Ctx<'js>, job: &Object<'js>, waiting: Rc<RefCell<Vec<Pending<'js>>>>, services: Services, app: String, task: String, deadline: Instant, service_cancel: Cancellation) -> rquickjs::Result<()> {
    let cloud=services.allowed_methods.contains("cloud.host") || services.allowed_methods.contains("debug.cloudHost");
    let max_calls=if cloud {100_000} else {32};
    let max_bytes=if cloud {1024*1024} else {64*1024};
    let calls=Rc::new(RefCell::new(0usize));
    job.set("request",Function::new(ctx.clone(),move |ctx: Ctx<'js>, method: String, args: Value<'js>| {
        let (promise,resolve,reject)=Promise::new(&ctx)?;
        let failure=if !services.allowed_methods.contains(&method) { Some("permission_denied") }
            else if *calls.borrow()>=max_calls || waiting.borrow().len()>=16 { Some("service_quota_exceeded") }
            else if Instant::now()>=deadline || service_cancel.is_cancelled() { Some("cancelled") }
            else { None };
        if let Some(code)=failure { reject.call::<_,()>((code,))?; return Ok::<_,rquickjs::Error>(promise); }
        let encoded=ctx.json_stringify(args)?.ok_or(rquickjs::Error::Unknown)?.to_string()?;
        if encoded.len()>max_bytes { reject.call::<_,()>(("request_too_large",))?; return Ok(promise); }
        // JSON serialization can call guest toJSON and reenter request.
        if *calls.borrow()>=max_calls || waiting.borrow().len()>=16 {
            reject.call::<_,()>(("service_quota_exceeded",))?; return Ok(promise);
        }
        *calls.borrow_mut()+=1;
        let response=(services.dispatch)(ServiceRequest {app_id:app.clone(),task_id:task.clone(),method,args_json:encoded,deadline,cancellation:service_cancel.clone()});
        waiting.borrow_mut().push(Pending{response,resolve,reject});
        Ok(promise)
    })?)?;
    Ok(())
}
impl Drop for CancelServices { fn drop(&mut self) { self.0.cancel(); } }

#[derive(Clone, Default)]
pub struct Cancellation(Arc<AtomicBool>);
impl Cancellation {
    pub fn cancel(&self) { self.0.store(true,Ordering::Release); }
    pub fn is_cancelled(&self) -> bool { self.0.load(Ordering::Acquire) }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Status { Success, Retry, Failure }
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunResult { pub status: Status, pub code: &'static str, pub elapsed_ms: u64, #[serde(skip_serializing_if = "Option::is_none")] pub progress: Option<bool> }
pub struct Options<'a> { pub app_id: &'a str, pub task_id: &'a str, pub budget_ms: u64, pub memory_bytes: usize, pub payload_json: &'a str }
static ACTIVE: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
struct ActiveRun(String);
impl Drop for ActiveRun {
    fn drop(&mut self) { if let Ok(mut active) = ACTIVE.get().expect("initialized registry").lock() { active.remove(&self.0); } }
}
fn valid_id(id: &str) -> bool { !id.is_empty() && id.len() <= 128 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b)) }
fn stop_code(cancel: &Cancellation, deadline: Instant) -> Option<&'static str> {
    if cancel.is_cancelled() { Some("cancelled") } else if Instant::now() >= deadline { Some("deadline_exceeded") } else { None }
}

/// Source must be the already verified, separately built background bundle.
/// It installs globalThis.backgroundHandler(context), returning a status string
/// or a promise resolving to one. Arbitrary unresolved promises fail, not spin.
pub fn run(source: &str, options: Options<'_>, cancel: Cancellation) -> RunResult {
    run_with_services(source,options,cancel,None)
}

pub fn run_with_services(source: &str, options: Options<'_>, cancel: Cancellation, services: Option<Services>) -> RunResult {
    let started = Instant::now();
    let finish = |status, code| RunResult { status, code, progress: None, elapsed_ms: started.elapsed().as_millis().min(u64::MAX as u128) as u64 };
    let cloud=services.as_ref().is_some_and(|s| s.allowed_methods.contains("cloud.host") || s.allowed_methods.contains("debug.cloudHost"));
    if !valid_id(options.app_id) || !valid_id(options.task_id) || !(1..=if cloud {300_000} else {30_000}).contains(&options.budget_ms)
        || !(4*1024*1024..=if cloud {256*1024*1024} else {32*1024*1024}).contains(&options.memory_bytes) || source.len() > 1024*1024 || options.payload_json.len() > 64*1024 {
        return finish(Status::Failure,"invalid_options");
    }
    let active = ACTIVE.get_or_init(|| Mutex::new(HashSet::new()));
    let Ok(mut running) = active.lock() else { return finish(Status::Failure,"registry_unavailable"); };
    if !running.insert(options.app_id.to_owned()) { return finish(Status::Retry,"already_running"); }
    drop(running); let _guard = ActiveRun(options.app_id.to_owned());
    let deadline = started + Duration::from_millis(options.budget_ms);
    if let Some(code) = stop_code(&cancel,deadline) { return finish(Status::Failure,code); }
    let Ok(runtime) = Runtime::new() else { return finish(Status::Failure,"runtime_unavailable"); };
    runtime.set_memory_limit(options.memory_bytes);
    runtime.set_max_stack_size(256*1024);
    let interrupt_cancel = cancel.clone();
    runtime.set_interrupt_handler(Some(Box::new(move || stop_code(&interrupt_cancel,deadline).is_some())));
    let Ok(context) = Context::full(&runtime) else { return finish(Status::Failure,"context_unavailable"); };
    let service_cancel = Cancellation::default();
    let _cancel_services = CancelServices(service_cancel.clone());
    let evaluated: Result<(Status,Option<bool>), &'static str> = context.with(|ctx| {
        let pending: Rc<RefCell<Vec<Pending<'_>>>> = Rc::new(RefCell::new(Vec::new()));
        let _clear_pending=ClearPending(pending.clone());
        // Remove all dynamic-code constructors before untrusted bundle evaluation.
        // No module loader is installed, so import cannot load filesystem/network code.
        ctx.eval::<(),_>(r#"
            for (const fn of [function(){}, async function(){}, function*(){}, async function*(){}]) {
                Object.defineProperty(Object.getPrototypeOf(fn), 'constructor', {value:undefined,writable:false,configurable:false});
            }
            Object.defineProperty(globalThis, 'eval', {value:undefined,writable:false,configurable:false});
            Object.defineProperty(globalThis, 'Function', {value:undefined,writable:false,configurable:false});
            Object.defineProperty(globalThis, 'Atomics', {value:undefined,writable:false,configurable:false});
            Object.defineProperty(globalThis, 'SharedArrayBuffer', {value:undefined,writable:false,configurable:false});
        "#).map_err(|_| "sandbox_setup_failed")?;
        // Pure bounded codecs only: never mount the UI/host `pod` object here.
        // Sharing these routines avoids service IPC for each small digest.
        let bytes = Object::new(ctx.clone()).map_err(|_| "context_unavailable")?;
        crate::byte_codecs::mount(ctx.clone(), &bytes).map_err(|_| "context_unavailable")?;
        ctx.globals().set("__podByteCodecs", bytes).map_err(|_| "context_unavailable")?;
        let job = Object::new(ctx.clone()).map_err(|_| "context_unavailable")?;
        job.set("appId",options.app_id).map_err(|_| "context_unavailable")?;
        job.set("taskId",options.task_id).map_err(|_| "context_unavailable")?;
        let payload = ctx.json_parse(options.payload_json).map_err(|_| "invalid_payload")?;
        job.set("payload",payload).map_err(|_| "context_unavailable")?;
        let unix_ms = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|_| "clock_unavailable")?.as_millis();
        job.set("deadlineMs",(unix_ms + deadline.saturating_duration_since(Instant::now()).as_millis()) as f64).map_err(|_| "context_unavailable")?;
        let signal = cancel.clone();
        job.set("isCancelled",Function::new(ctx.clone(),move || signal.is_cancelled()).map_err(|_| "context_unavailable")?).map_err(|_| "context_unavailable")?;
        if let Some(services)=services {
            attach_services(ctx.clone(),&job,pending.clone(),services,options.app_id.to_owned(),options.task_id.to_owned(),deadline,service_cancel)
                .map_err(|_| "context_unavailable")?;
        }
        // Freeze using the original built-in before guest code can replace it.
        let freeze: Function = ctx.eval("Object.freeze").map_err(|_| "sandbox_setup_failed")?;
        freeze.call::<_,()>((job.clone(),)).map_err(|_| "sandbox_setup_failed")?;
        ctx.eval::<(),_>(source).map_err(|_| "bundle_exception")?;
        let handler: Function = ctx.globals().get("backgroundHandler").map_err(|_| "handler_missing")?;
        let mut value: Value = handler.call((job,)).map_err(|_| "handler_exception")?;
        if let Some(promise) = value.as_promise() {
            loop {
                if let Some(code) = stop_code(&cancel,deadline) { return Err(code); }
                if let Some(result) = promise.result::<Value>() { value = result.map_err(|_| "handler_rejected")?; break; }
                let mut resolved=false;
                let mut waiting=pending.borrow_mut();
                let mut replies=Vec::new();
                let mut index=0;
                while index<waiting.len() {
                    let reply=match waiting[index].response.try_recv() {
                        Ok(reply)=>reply,
                        Err(TryRecvError::Empty)=>{index+=1;continue;},
                        Err(TryRecvError::Disconnected)=>Err("service_disconnected".into()),
                    };
                    let request=waiting.swap_remove(index); resolved=true;
                    replies.push((request,reply));
                }
                drop(waiting);
                for (request,reply) in replies {
                    match reply {
                        Ok(json) if json.len()<=if cloud {1024*1024} else {64*1024}=>match ctx.json_parse(json) {
                            Ok(value)=>request.resolve.call::<_,()>((value,)).map_err(|_| "service_reply_failed")?,
                            Err(_)=>{ let _=ctx.catch(); request.reject.call::<_,()>(("invalid_service_reply",)).map_err(|_| "service_reply_failed")?; },
                        },
                        Ok(_)=>request.reject.call::<_,()>(("reply_too_large",)).map_err(|_| "service_reply_failed")?,
                        Err(code)=>request.reject.call::<_,()>((if code.len()<=128 {code.as_str()} else {"service_failed"},)).map_err(|_| "service_reply_failed")?,
                    }
                }
                let inflight=!pending.borrow().is_empty();
                if !ctx.execute_pending_job() && !resolved {
                    if !inflight { return Err("unresolved_handler"); }
                    std::thread::sleep(Duration::from_millis(2).min(deadline.saturating_duration_since(Instant::now())));
                }
            }
        }
        if let Some(object)=value.as_object() {
            let status:String=object.get("status").map_err(|_| "invalid_result")?;
            let progress:bool=object.get("progress").map_err(|_| "invalid_result")?;
            let keys=object.keys::<String>().collect::<Result<Vec<_>,_>>().map_err(|_| "invalid_result")?;
            if status!="retry" || !progress || keys.len()!=2 || keys.iter().any(|k|k!="status"&&k!="progress") { return Err("invalid_result"); }
            return Ok((Status::Retry,Some(true)));
        }
        let result = value.as_string().ok_or("invalid_result")?.to_string().map_err(|_| "invalid_result")?;
        match result.as_str() { "busy" => return Err("yielded"), "success" => Ok((Status::Success,None)), "retry" => Ok((Status::Retry,None)), "failure" => Ok((Status::Failure,None)), _ => Err("invalid_result") }
    });
    if let Some(code) = stop_code(&cancel,deadline) { return finish(Status::Failure,code); }
    match evaluated { Ok((status,progress)) => {let mut result=finish(status,"completed");result.progress=progress;result}, Err("yielded") => finish(Status::Success,"yielded"), Err(code) => finish(Status::Failure,code) }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn options(app: &str) -> Options<'_> { Options { app_id:app,task_id:"refresh",budget_ms:100,memory_bytes:8*1024*1024,payload_json:"null" } }
    #[test] fn headless_byte_codecs_are_bounded_and_do_not_mount_host_or_ui() {
        let source=r#"globalThis.backgroundHandler=()=>{
          if(typeof pod!=='undefined'||typeof frame!=='undefined'||__podByteCodecs.request!==undefined)return 'failure';
          const hash=Array.from(__podByteCodecs.sha256Bytes(new Uint8Array([97,98,99]))).map(x=>x.toString(16).padStart(2,'0')).join('');
          if(hash!=='ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')return 'failure';
          if(__podByteCodecs.sha256Bytes(new Uint8Array(256*1024+1))!=null)return 'failure';
          if(__podByteCodecs.encodeBase64Chunk(new Uint8Array([0,255]))!=='AP8=')return 'failure';
          return 'success';};"#;
        assert_eq!(run(source,options("pure-codecs"),Cancellation::default()).status,Status::Success);
    }
    #[test] fn cloud_profile_is_explicit_and_preserves_default_budgets() {
        let source="globalThis.backgroundHandler=async c=>{for(let i=0;i<40;i++){const v=await c.request('cloud.host',{method:'file.read',args:{}});if(v.length!==100000)return 'failure'}try{await c.request('ui.draw',{});return 'failure'}catch(e){return e==='permission_denied'?'success':'failure'}}";
        let make=|| Services {allowed_methods:HashSet::from(["cloud.host".into()]),dispatch:Arc::new(|_| {
            let (send,receive)=std::sync::mpsc::channel();send.send(Ok(serde_json::to_string(&"x".repeat(100000)).unwrap())).unwrap();receive
        })};
        let config=Options {app_id:"cloud-profile",task_id:"cloud",budget_ms:300000,memory_bytes:256*1024*1024,payload_json:"null"};
        let denied=run(source,Options {..config},Cancellation::default());assert_eq!(denied.code,"invalid_options");
        let result=run_with_services(source,config,Cancellation::default(),Some(make()));assert_eq!(result.code,"completed");assert!(matches!(result.status,Status::Success));
    }
    #[test] fn approved_async_requests_complete_and_bind_host_identity() {
        let services=Services {allowed_methods:HashSet::from(["kv.get".into()]),dispatch:Arc::new(|request| {
            assert_eq!(request.app_id,"async"); assert_eq!(request.task_id,"refresh");
            assert_eq!(request.method,"kv.get"); assert_eq!(request.args_json,r#"{"key":"a"}"#);
            let (send,receive)=std::sync::mpsc::channel();
            std::thread::spawn(move || {std::thread::sleep(Duration::from_millis(5)); let _=send.send(Ok(r#"{"value":17}"#.into()));});
            receive
        })};
        let source="globalThis.backgroundHandler=async c=>{try{await c.request('ui.draw',{});return 'failure'}catch(e){if(e!=='permission_denied')throw e} const r=await c.request('kv.get',{key:'a'});return r.value===17?'success':'failure'}";
        assert_eq!(run_with_services(source,options("async"),Cancellation::default(),Some(services)).status,Status::Success);
    }
    #[test] fn pending_async_requests_are_cancelled_at_deadline() {
        let signals=Arc::new(Mutex::new(Vec::new())); let captured=signals.clone();
        let senders=Arc::new(Mutex::new(Vec::new()));
        let held_senders=senders.clone();
        let services=Services {allowed_methods:HashSet::from(["kv.get".into()]),dispatch:Arc::new(move |request| {
            captured.lock().unwrap().push(request.cancellation);
            let (send,receive)=std::sync::mpsc::channel(); held_senders.lock().unwrap().push(send); receive
        })};
        let result=run_with_services("globalThis.backgroundHandler=c=>c.request('kv.get',{})",options("async-timeout"),Cancellation::default(),Some(services));
        assert_eq!(result.code,"deadline_exceeded");
        assert!(signals.lock().unwrap()[0].is_cancelled());
    }
    #[test] fn async_replies_are_bounded_and_serialization_can_reenter() {
        let services=Services {allowed_methods:HashSet::from(["get".into()]),dispatch:Arc::new(|request| {
            let (send,receive)=std::sync::mpsc::channel();
            let reply=if request.args_json=="1" {"x".repeat(65537)} else if request.args_json=="2" {"not-json".into()} else {"17".into()};
            send.send(Ok(reply)).unwrap(); receive
        })};
        let source=r#"globalThis.backgroundHandler=async c=>{
            await c.request('get',{toJSON(){c.request('get',0);return 0}});
            for(const [n,code] of [[1,'reply_too_large'],[2,'invalid_service_reply']]) {
                try {await c.request('get',n);return 'failure'} catch(e) {if(e!==code)throw e}
            }
            return 'success';
        }"#;
        assert_eq!(run_with_services(source,options("async-reenter"),Cancellation::default(),Some(services)).status,Status::Success);
    }
    #[test] fn async_quota_and_unawaited_requests_are_bounded() {
        let signals=Arc::new(Mutex::new(Vec::new())); let captured=signals.clone();
        let senders=Arc::new(Mutex::new(Vec::new())); let held=senders.clone();
        let services=Services {allowed_methods:HashSet::from(["get".into()]),dispatch:Arc::new(move |request| {
            captured.lock().unwrap().push(request.cancellation);
            let (send,receive)=std::sync::mpsc::channel(); held.lock().unwrap().push(send); receive
        })};
        let source="globalThis.backgroundHandler=async c=>{for(let i=0;i<16;i++)c.request('get',{});try{await c.request('get',{});return 'failure'}catch(e){return e==='service_quota_exceeded'?'success':'failure'}}";
        assert_eq!(run_with_services(source,options("async-quota"),Cancellation::default(),Some(services)).status,Status::Success);
        assert_eq!(signals.lock().unwrap().len(),16);
        assert!(signals.lock().unwrap().iter().all(Cancellation::is_cancelled));
    }
    #[test] fn durable_window_retry_is_data_and_preserves_the_restricted_realm() {
        let source=r#"globalThis.backgroundHandler=async job=>{if(typeof pod!=='undefined'||typeof ui!=='undefined')throw Error('UI');await Promise.resolve();return {status:'retry',progress:true};}"#;
        let result=run(source,options("window-progress"),Cancellation::default());
        assert_eq!(result.code,"completed");assert_eq!(result.status,Status::Retry);assert_eq!(result.progress,Some(true));
        let wire=serde_json::to_value(result).unwrap();assert_eq!(wire["progress"],true);
        let old=run("globalThis.backgroundHandler=()=> 'retry'",options("window-old"),Cancellation::default());
        assert_eq!(old.progress,None);assert!(serde_json::to_value(old).unwrap().get("progress").is_none());
        for invalid in ["{status:'success',progress:true}","{status:'retry',progress:false}","{status:'retry',progress:1}","{status:'retry',progress:true,payload:'secret'}"] {
            assert_eq!(run(&format!("globalThis.backgroundHandler=()=>({invalid})"),options("window-invalid"),Cancellation::default()).code,"invalid_result");
        }
    }
    #[test] fn payload_is_data_not_executable_source() {
        let mut config = options("payload"); config.payload_json = r#"{"text":"'; throw Error('injection'); //","items":[1,true,null]}"#;
        let source = "globalThis.backgroundHandler=ctx => ctx.payload.items[0]===1 && ctx.payload.text.includes('injection') ? 'success' : 'failure'";
        assert_eq!(run(source,config,Cancellation::default()).status,Status::Success);
        let mut bad = options("payload"); bad.payload_json = "undefined";
        assert_eq!(run(source,bad,Cancellation::default()).code,"invalid_payload");
    }
    #[test] fn no_ui_or_dynamic_code_and_status_results() {
        let result = run(r#"globalThis.backgroundHandler = async job => {
            if (typeof ui !== 'undefined' || typeof fetch !== 'undefined' || typeof eval !== 'undefined' || typeof Function !== 'undefined' || typeof Atomics !== 'undefined' || typeof SharedArrayBuffer !== 'undefined') throw Error('unrestricted globals');
            if ((()=>{}).constructor || (async()=>{}).constructor || (function*(){}).constructor || (async function*(){}).constructor) throw Error('dynamic constructor');
            if (!Object.isFrozen(job) || job.isCancelled() || job.taskId !== 'refresh') throw Error('invalid job');
            await Promise.resolve(); return 'success';
        };"#,options("sandbox"),Cancellation::default());
        assert_eq!(result.status,Status::Success); assert_eq!(result.code,"completed");
        for status in ["retry","failure"] {
            let result = run(&format!("globalThis.backgroundHandler=()=>'{status}'"),options("statuses"),Cancellation::default());
            assert_eq!(result.code,"completed"); assert_eq!(result.status,if status == "retry" {Status::Retry} else {Status::Failure});
        }
    }
    #[test] fn infinite_execution_and_microtasks_are_bounded() {
        for source in ["while(true){}", "globalThis.backgroundHandler=()=>{while(true){}}", "globalThis.backgroundHandler=async()=>{while(true)await Promise.resolve()}"] {
            let result = run(source,options("deadline"),Cancellation::default());
            assert_eq!(result.code,"deadline_exceeded"); assert!(result.elapsed_ms < 2000);
        }
    }
    #[test] fn rejects_invalid_missing_and_unresolved_handlers() {
        for (source,code) in [("", "handler_missing"),("globalThis.backgroundHandler=()=>42","invalid_result"),("globalThis.backgroundHandler=()=>Promise.reject('no')","handler_rejected"),("globalThis.backgroundHandler=()=>new Promise(()=>{})","unresolved_handler")] {
            assert_eq!(run(source,options("invalid"),Cancellation::default()).code,code);
        }
    }
    #[test] fn pre_cancelled_tasks_never_evaluate() {
        let cancel = Cancellation::default(); cancel.cancel();
        assert_eq!(run("while(true){}",options("cancel"),cancel).code,"cancelled");
    }
    #[test] fn cancellation_interrupts_live_task_and_releases_single_instance_guard() {
        let cancellation = Cancellation::default(); let worker_cancel = cancellation.clone();
        let worker = std::thread::spawn(move || {
            let mut config = options("exclusive"); config.budget_ms = 2000;
            run("globalThis.backgroundHandler=()=>{while(true){}}",config,worker_cancel)
        });
        let wait_deadline = Instant::now() + Duration::from_secs(1);
        while !ACTIVE.get().is_some_and(|active| active.lock().unwrap().contains("exclusive")) {
            assert!(Instant::now() < wait_deadline); std::thread::yield_now();
        }
        assert_eq!(run("",options("exclusive"),Cancellation::default()).code,"already_running");
        cancellation.cancel(); assert_eq!(worker.join().unwrap().code,"cancelled");
        assert_eq!(run("globalThis.backgroundHandler=()=> 'success'",options("exclusive"),Cancellation::default()).status,Status::Success);
    }
    #[test] fn periodic_collision_never_dispatches_services_and_manual_progress_survives() {
        let (entered,wait)=std::sync::mpsc::channel();let (release,released)=std::sync::mpsc::channel();
        let receiver=std::sync::Arc::new(std::sync::Mutex::new(Some(released)));
        let services=Services{allowed_methods:HashSet::from(["kv.get".into()]),dispatch:Arc::new(move |_|{entered.send(()).unwrap();receiver.lock().unwrap().take().unwrap()})};
        let worker=std::thread::spawn(move||run_with_services("globalThis.backgroundHandler=async c=>{await c.request('kv.get',{});return {status:'retry',progress:true}}",Options{app_id:"concurrent-window",task_id:"manual",budget_ms:2000,memory_bytes:8*1024*1024,payload_json:"null"},Cancellation::default(),Some(services)));
        wait.recv_timeout(Duration::from_secs(1)).unwrap();
        let collision=run("globalThis.backgroundHandler=()=>{throw Error('must never evaluate')}",Options{app_id:"concurrent-window",task_id:"periodic",..options("unused")},Cancellation::default());
        assert_eq!(collision.code,"already_running");assert_eq!(collision.status,Status::Retry);
        release.send(Ok("null".into())).unwrap();let manual=worker.join().unwrap();assert_eq!(manual.code,"completed");assert_eq!(manual.progress,Some(true));
        let yielded=run("globalThis.backgroundHandler=()=> 'busy'",options("writer-yield"),Cancellation::default());assert_eq!(yielded.status,Status::Success);assert_eq!(yielded.code,"yielded");
    }
    #[test] fn allocation_budget_rejects_large_guest_heap() {
        let result = run("globalThis.backgroundHandler=()=>new ArrayBuffer(64*1024*1024)",options("heap"),Cancellation::default());
        assert_eq!(result.status,Status::Failure); assert_eq!(result.code,"handler_exception");
    }
}
