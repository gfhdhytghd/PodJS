//! One-shot host handle; poll/reply/cancel may race execute, close may not.
use crate::background::{self, Cancellation, Options};
use serde::Deserialize;
use std::{collections::{HashMap, VecDeque}, ffi::{CString, c_char}, sync::{mpsc::{self, Sender}, Arc, Mutex, atomic::{AtomicBool, Ordering}}};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config { app_id: String, task_id: String, source: String, budget_ms: u64, memory_bytes: usize, #[serde(default)] payload: serde_json::Value, #[serde(default)] allowed_methods: Vec<String>, kv_root:Option<String> }
#[derive(Default)]
struct Requests { next: u64, ready: VecDeque<(u64,String)>, replies: HashMap<u64,Sender<background::ServiceReply>>, finished: bool }
pub struct PodBackgroundRun {
    config: Config,
    cancellation: Cancellation,
    started: AtomicBool,
    response: Mutex<CString>,
    requests: Arc<Mutex<Requests>>,
    polled: Mutex<CString>,
}
/// # Safety
/// Bytes are readable host config JSON. Source must come from the separately
/// built, verified application background bundle, never a network request.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_background_open(bytes: *const u8, length: usize) -> *mut PodBackgroundRun {
    if bytes.is_null() || length == 0 || length > 2*1024*1024 { return std::ptr::null_mut(); }
    let config = serde_json::from_slice::<Config>(unsafe { std::slice::from_raw_parts(bytes,length) });
    match config {
        Ok(config) if config.allowed_methods.len()<=32 && config.allowed_methods.iter().all(|method| !method.is_empty() && method.len()<=128 && method.bytes().all(|c| c.is_ascii_alphanumeric() || b"._".contains(&c))) => Box::into_raw(Box::new(PodBackgroundRun { config, cancellation:Cancellation::default(), started:AtomicBool::new(false), response:Mutex::new(CString::default()), requests:Arc::new(Mutex::new(Requests::default())), polled:Mutex::new(CString::default()) })),
        Ok(_) => { crate::set_error("invalid background method allowlist"); std::ptr::null_mut() },
        Err(error) => { crate::set_error(error.to_string()); std::ptr::null_mut() }
    }
}
/// # Safety
/// Live handle executes once on an IO worker. Poll/reply/cancel may run concurrently.
/// Return value is borrowed JSON, valid until close. Close only after return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_background_execute(handle: *const PodBackgroundRun) -> *const c_char {
    let Some(handle) = (unsafe { handle.as_ref() }) else { return std::ptr::null(); };
    if handle.started.swap(true,Ordering::AcqRel) { crate::set_error("background handle is one-shot"); return std::ptr::null(); }
    let config = &handle.config;
    let payload_json = config.payload.to_string();
    let queue=handle.requests.clone();
    let kv_services=if let Some(root)=&config.kv_root {
        let methods=config.allowed_methods.iter().filter(|m| m.starts_with("kv.")).cloned().collect();
        match crate::kv::background_services(root.into(),methods) {
            Ok(services)=>Some(services),
            Err(_)=>{crate::set_error("invalid background KV root or grants");return std::ptr::null();}
        }
    } else {None};
    let services=if config.allowed_methods.is_empty() {None} else {Some(background::Services {
        allowed_methods:config.allowed_methods.iter().cloned().collect(),
        dispatch:Arc::new(move |request| {
            if request.method.starts_with("kv.") {if let Some(kv)=&kv_services {return (kv.dispatch)(request);}}
            let (send,receive)=mpsc::channel();
            if let Ok(mut queue)=queue.lock() {
                if !queue.finished && !request.cancellation.is_cancelled() {
                    queue.next+=1; let id=queue.next;
                    let args:serde_json::Value=serde_json::from_str(&request.args_json).expect("runtime serialized args");
                    let line=serde_json::json!({"id":id,"appId":request.app_id,"taskId":request.task_id,"method":request.method,"args":args,
                        "remainingMs":request.deadline.saturating_duration_since(std::time::Instant::now()).as_millis() as u64}).to_string();
                    queue.ready.push_back((id,line)); queue.replies.insert(id,send);
                }
            }
            receive
        }),
    })};
    let result = background::run_with_services(&config.source,Options { app_id:&config.app_id, task_id:&config.task_id, budget_ms:config.budget_ms, memory_bytes:config.memory_bytes, payload_json:&payload_json },handle.cancellation.clone(),services);
    if let Ok(mut queue)=handle.requests.lock() {queue.finished=true;queue.ready.clear();queue.replies.clear();}
    let Ok(mut response) = handle.response.lock() else { return std::ptr::null(); };
    *response = CString::new(serde_json::to_string(&result).expect("serializable run result")).expect("JSON escapes NUL");
    response.as_ptr()
}
/// # Safety
/// Live handle; can be called from a system cancellation callback during execute.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_background_cancel(handle: *const PodBackgroundRun) {
    if let Some(handle) = unsafe { handle.as_ref() } {
        handle.cancellation.cancel();
        if let Ok(mut queue)=handle.requests.lock() {queue.finished=true;queue.ready.clear();queue.replies.clear();}
    }
}
/// # Safety
/// Live handle. Serialize poll callers; returned UTF-8 JSON lasts until next poll
/// or close. Null means no request. Never close while any caller uses the handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_background_poll(handle: *const PodBackgroundRun) -> *const c_char {
    let Some(handle)=(unsafe {handle.as_ref()}) else {return std::ptr::null();};
    let Ok(mut queue)=handle.requests.lock() else {return std::ptr::null();};
    let Some((_,line))=queue.ready.pop_front() else {return std::ptr::null();};
    let Ok(mut polled)=handle.polled.lock() else {return std::ptr::null();};
    *polled=CString::new(line).expect("JSON escapes NUL"); polled.as_ptr()
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Reply {id:u64, ok:bool, #[serde(default)] value:serde_json::Value, code:Option<String>}
/// # Safety
/// Live handle and readable UTF-8 JSON bytes. Replies are one-shot and scoped to
/// this handle. Returns 0 accepted, -1 invalid, -2 unknown/finished request.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_background_reply(handle: *const PodBackgroundRun, bytes:*const u8, length:usize) -> i32 {
    let Some(handle)=(unsafe {handle.as_ref()}) else {return -1;};
    if bytes.is_null() || length==0 || length>if handle.config.allowed_methods.iter().any(|m| m=="cloud.host" || m=="debug.cloudHost") {1024*1024} else {70*1024} {return -1;}
    let Ok(reply)=serde_json::from_slice::<Reply>(unsafe {std::slice::from_raw_parts(bytes,length)}) else {return -1;};
    if reply.ok && reply.code.is_some() || !reply.ok && !reply.value.is_null() {return -1;}
    let result=if reply.ok {Ok(reply.value.to_string())} else {
        let Some(code)=reply.code.filter(|code| !code.is_empty() && code.len()<=128) else {return -1;}; Err(code)
    };
    let Ok(mut queue)=handle.requests.lock() else {return -2;};
    let Some(send)=queue.replies.remove(&reply.id) else {return -2;};
    queue.ready.retain(|(id,_)|*id!=reply.id);
    if send.send(result).is_ok() {0} else {-2}
}
/// # Safety
/// Owned handle, closed once after execute/cancel callers have stopped using it.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_background_close(handle: *mut PodBackgroundRun) {
    if !handle.is_null() { drop(unsafe { Box::from_raw(handle) }); }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn asynchronous_host_poll_and_reply_are_one_shot() { unsafe {
        let bytes=br#"{"app_id":"ffi-async","task_id":"task","source":"globalThis.backgroundHandler=async c=>(await c.request('kv.get',{key:'a'}))===17?'success':'failure'","budget_ms":1000,"memory_bytes":8388608,"allowed_methods":["kv.get"]}"#;
        let handle=pod_background_open(bytes.as_ptr(),bytes.len()); assert!(!handle.is_null());
        let address=handle as usize;
        let worker=std::thread::spawn(move || {
            let result=pod_background_execute(address as *const PodBackgroundRun);
            std::ffi::CStr::from_ptr(result).to_str().unwrap().to_owned()
        });
        let deadline=std::time::Instant::now()+std::time::Duration::from_secs(2);
        let request=loop {
            let line=pod_background_poll(handle);
            if !line.is_null() {break serde_json::from_str::<serde_json::Value>(std::ffi::CStr::from_ptr(line).to_str().unwrap()).unwrap();}
            assert!(std::time::Instant::now()<deadline);std::thread::yield_now();
        };
        assert_eq!(request["appId"],"ffi-async"); assert_eq!(request["method"],"kv.get");assert_eq!(request["args"]["key"],"a");
        let bad=serde_json::json!({"id":request["id"],"ok":true,"value":17,"code":"wrong"}).to_string();
        assert_eq!(pod_background_reply(handle,bad.as_ptr(),bad.len()),-1);
        let reply=serde_json::json!({"id":request["id"],"ok":true,"value":17}).to_string();
        assert_eq!(pod_background_reply(handle,reply.as_ptr(),reply.len()),0);
        assert_eq!(pod_background_reply(handle,reply.as_ptr(),reply.len()),-2);
        let result:serde_json::Value=serde_json::from_str(&worker.join().unwrap()).unwrap();
        assert_eq!(result["status"],"success");assert!(pod_background_poll(handle).is_null());
        pod_background_close(handle);
    }}
    #[test] fn cloud_host_replies_and_private_kv_share_one_bounded_guest() { unsafe {
        let root=std::env::temp_dir().join(format!("pod-cloud-kv-{}",std::process::id()));std::fs::create_dir_all(&root).unwrap();
        let mut kv=crate::kv::KvStore::open(root.to_str());kv.set("legacy".into(),serde_json::json!("progress")).unwrap();
        let source="globalThis.backgroundHandler=async c=>{const old=await c.request('kv.get',{key:'legacy'});if(!old.exists||old.value!=='progress')return 'failure';const value=await c.request('debug.cloudHost',{method:'file.read',args:{}});return value.length===100000?'success':'failure'}";
        let bytes=serde_json::json!({"app_id":"ffi-cloud","task_id":"task","source":source,"budget_ms":300000,"memory_bytes":268435456,"allowed_methods":["debug.cloudHost","kv.get"],"kv_root":root}).to_string();
        let handle=pod_background_open(bytes.as_ptr(),bytes.len());assert!(!handle.is_null());let address=handle as usize;
        let worker=std::thread::spawn(move || std::ffi::CStr::from_ptr(pod_background_execute(address as *const PodBackgroundRun)).to_str().unwrap().to_owned());
        let deadline=std::time::Instant::now()+std::time::Duration::from_secs(5);
        let request=loop{let line=pod_background_poll(handle);if !line.is_null(){break serde_json::from_str::<serde_json::Value>(std::ffi::CStr::from_ptr(line).to_str().unwrap()).unwrap();}assert!(std::time::Instant::now()<deadline);std::thread::yield_now();};
        assert_eq!(request["method"],"debug.cloudHost");
        let reply=serde_json::json!({"id":request["id"],"ok":true,"value":"x".repeat(100000)}).to_string();assert_eq!(pod_background_reply(handle,reply.as_ptr(),reply.len()),0);
        let result:serde_json::Value=serde_json::from_str(&worker.join().unwrap()).unwrap();assert_eq!(result["status"],"success");pod_background_close(handle);std::fs::remove_dir_all(root).unwrap();
    }}
    #[test] fn release_cloud_host_replies_and_private_kv_share_one_bounded_guest() { unsafe {
        let root=std::env::temp_dir().join(format!("pod-release-cloud-kv-{}",std::process::id()));std::fs::create_dir_all(&root).unwrap();
        let mut kv=crate::kv::KvStore::open(root.to_str());kv.set("legacy".into(),serde_json::json!("progress")).unwrap();
        let source="globalThis.backgroundHandler=async c=>{const old=await c.request('kv.get',{key:'legacy'});if(!old.exists||old.value!=='progress')return 'failure';const value=await c.request('cloud.host',{method:'file.read',args:{}});return value.length===100000?'success':'failure'}";
        let bytes=serde_json::json!({"app_id":"ffi-release-cloud","task_id":"task","source":source,"budget_ms":300000,"memory_bytes":268435456,"allowed_methods":["cloud.host","kv.get"],"kv_root":root}).to_string();
        let handle=pod_background_open(bytes.as_ptr(),bytes.len());assert!(!handle.is_null());let address=handle as usize;
        let worker=std::thread::spawn(move || std::ffi::CStr::from_ptr(pod_background_execute(address as *const PodBackgroundRun)).to_str().unwrap().to_owned());
        let deadline=std::time::Instant::now()+std::time::Duration::from_secs(5);
        let request=loop{let line=pod_background_poll(handle);if !line.is_null(){break serde_json::from_str::<serde_json::Value>(std::ffi::CStr::from_ptr(line).to_str().unwrap()).unwrap();}assert!(std::time::Instant::now()<deadline);std::thread::yield_now();};
        assert_eq!(request["method"],"cloud.host");
        let reply=serde_json::json!({"id":request["id"],"ok":true,"value":"x".repeat(100000)}).to_string();assert_eq!(pod_background_reply(handle,reply.as_ptr(),reply.len()),0);
        let result:serde_json::Value=serde_json::from_str(&worker.join().unwrap()).unwrap();assert_eq!(result["status"],"success");pod_background_close(handle);std::fs::remove_dir_all(root).unwrap();
    }}
    #[test] fn cancelled_handle_is_one_shot_and_returns_structured_result() { unsafe {
        let bytes = br#"{"app_id":"app","task_id":"task","source":"while(true){}","budget_ms":100,"memory_bytes":8388608}"#;
        let handle = pod_background_open(bytes.as_ptr(),bytes.len()); assert!(!handle.is_null());
        pod_background_cancel(handle);
        let response = std::ffi::CStr::from_ptr(pod_background_execute(handle)).to_str().unwrap();
        let result: serde_json::Value = serde_json::from_str(response).unwrap();
        assert_eq!(result["status"],"failure"); assert_eq!(result["code"],"cancelled");
        assert!(pod_background_execute(handle).is_null()); pod_background_close(handle);
    }}
}
