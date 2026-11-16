//! Host-only file receiver commands, usable from an IO worker without QuickJS.
use crate::sync_files::{FileManifest, FileReceiver, CHUNK_BYTES};
use base64::Engine;
use serde::Deserialize;
use serde_json::json;
use std::{ffi::{c_char, CStr, CString}, path::Path};
pub struct PodSyncFiles { receiver: FileReceiver, response: CString }
#[derive(Deserialize)]
#[serde(tag = "method", deny_unknown_fields)]
enum Command {
    #[serde(rename = "offer")] Offer { manifest: FileManifest },
    #[serde(rename = "missing")] Missing { transfer_id: String },
    #[serde(rename = "verified_complete")] VerifiedComplete { transfer_id: String },
    #[serde(rename = "chunk")] Chunk { transfer_id: String, index: usize, data_base64: String },
    #[serde(rename = "finish")] Finish { transfer_id: String },
    #[serde(rename = "cancel")] Cancel { transfer_id: String },
    #[serde(rename = "read_complete_chunk")] ReadCompleteChunk { transfer_id: String, index: usize },
}
impl PodSyncFiles {
    fn dispatch(&self, bytes: &[u8]) -> anyhow::Result<serde_json::Value> {
        anyhow::ensure!(bytes.len() <= 96 * 1024, "command too large");
        Ok(match serde_json::from_slice::<Command>(bytes)? {
            Command::Offer { manifest } => { self.receiver.offer(&manifest)?; json!({"transferId":manifest.transfer_id}) }
            Command::Missing { transfer_id } => json!({"missing":self.receiver.missing(&transfer_id)?}),
            Command::VerifiedComplete { transfer_id } => json!({"complete":self.receiver.verified_complete(&transfer_id)?}),
            Command::Chunk { transfer_id, index, data_base64 } => {
                anyhow::ensure!(data_base64.len() <= CHUNK_BYTES.div_ceil(3) * 4, "chunk too large");
                let bytes = base64::engine::general_purpose::STANDARD.decode(data_base64)?;
                self.receiver.receive_chunk(&transfer_id,index,&bytes)?; json!({"index":index,"stored":true})
            }
            Command::Finish { transfer_id } => json!({"path":self.receiver.finish(&transfer_id)?,"state":"complete"}),
            Command::Cancel { transfer_id } => { self.receiver.cancel(&transfer_id)?; json!({"state":"cancelled"}) }
            Command::ReadCompleteChunk { transfer_id, index } => {
                let bytes = self.receiver.read_complete_chunk(&transfer_id, index)?;
                json!({"index": index, "data_base64": base64::engine::general_purpose::STANDARD.encode(bytes)})
            }
        })
    }
}
/// # Safety
/// root is a valid NUL-terminated private per-app/per-peer directory path.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_files_open(root: *const c_char) -> *mut PodSyncFiles {
    if root.is_null() { return std::ptr::null_mut(); }
    let result = unsafe { CStr::from_ptr(root) }.to_str().map_err(anyhow::Error::from)
        .and_then(|root| FileReceiver::open(Path::new(root)));
    match result {
        Ok(receiver) => Box::into_raw(Box::new(PodSyncFiles { receiver, response:CString::default() })),
        Err(error) => { crate::set_error(error.to_string()); std::ptr::null_mut() }
    }
}
/// # Safety
/// handle is exclusively borrowed and live; bytes points to length readable bytes.
/// Host authenticates and authorizes peer before invoking. Response lasts until
/// next command or close. This interface is not exposed directly to guests.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_files_command(handle: *mut PodSyncFiles, bytes: *const u8, length: usize) -> *const c_char {
    let Some(handle) = (unsafe { handle.as_mut() }) else { return std::ptr::null(); };
    let result = if bytes.is_null() || length == 0 || length > 96 * 1024 { Err(anyhow::anyhow!("invalid command buffer")) }
        else { handle.dispatch(unsafe { std::slice::from_raw_parts(bytes,length) }) };
    let reply = match result { Ok(value) => json!({"ok":true,"value":value}), Err(error) => json!({"ok":false,"code":"file_transfer_error","message":error.to_string()}) };
    handle.response = CString::new(reply.to_string()).expect("JSON escapes NUL"); handle.response.as_ptr()
}
/// # Safety
/// handle must be null or an owned open handle, closed exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_files_close(handle: *mut PodSyncFiles) {
    if !handle.is_null() { drop(unsafe { Box::from_raw(handle) }); }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn ffi_rejects_null_handles() { unsafe {
        assert!(pod_sync_files_open(std::ptr::null()).is_null());
        assert!(pod_sync_files_command(std::ptr::null_mut(),std::ptr::null(),0).is_null());
        pod_sync_files_close(std::ptr::null_mut());
    }}
    #[test] fn binary_and_legacy_receivers_publish_identical_bytes() { unsafe {
        use sha2::{Digest,Sha256};
        for size in [0,1,65535,65536] {
            let bytes:Vec<u8>=(0..size).map(|i|(i%256) as u8).collect();
            let hash=format!("{:x}",Sha256::digest(&bytes));
            let manifest=json!({"transfer_id":"object","size":size,"sha256":hash,"chunk_hashes":if size==0{vec![]}else{vec![hash.clone()]},"mime":"application/octet-stream"});
            let root=std::env::temp_dir().join(format!("pod-byte-diff-{}-{size}",std::process::id()));
            let _=std::fs::remove_dir_all(&root);
            let mut outputs=vec![];
            for binary in [false,true] {
                let path=root.join(if binary{"raw"}else{"old"});let name=CString::new(path.to_str().unwrap()).unwrap();let h=pod_sync_files_open(name.as_ptr());assert!(!h.is_null());
                let offer=json!({"method":"offer","manifest":manifest}).to_string();
                let reply=pod_sync_files_command(h,offer.as_ptr(),offer.len());assert!(CStr::from_ptr(reply).to_str().unwrap().contains("true"));
                if size>0 {
                    let mut chunk=json!({"method":"chunk","transfer_id":"object","index":0});
                    let response=if binary {let c=chunk.to_string();pod_sync_files_command_bytes(h,c.as_ptr(),c.len(),bytes.as_ptr(),bytes.len())}else{chunk["data_base64"]=json!(base64::engine::general_purpose::STANDARD.encode(&bytes));let c=chunk.to_string();pod_sync_files_command(h,c.as_ptr(),c.len())};
                    let value:serde_json::Value=serde_json::from_str(CStr::from_ptr(response).to_str().unwrap()).unwrap();assert_eq!(value["ok"],true);
                }
                let finish=json!({"method":"finish","transfer_id":"object"}).to_string();let response=pod_sync_files_command(h,finish.as_ptr(),finish.len());
                let value:serde_json::Value=serde_json::from_str(CStr::from_ptr(response).to_str().unwrap()).unwrap();assert_eq!(value["ok"],true);outputs.push(std::fs::read(value["value"]["path"].as_str().unwrap()).unwrap());
                // Invalid binary commands cannot become a different operation.
                for metadata in [r#"{"method":"cancel","transfer_id":"object","index":0}"#,r#"{"method":"chunk","transfer_id":"object","index":0,"extra":true}"#] {
                    let response=pod_sync_files_command_bytes(h,metadata.as_ptr(),metadata.len(),bytes.as_ptr(),bytes.len());let value:serde_json::Value=serde_json::from_str(CStr::from_ptr(response).to_str().unwrap()).unwrap();assert_eq!(value["ok"],false);
                }
                pod_sync_files_close(h);
            }
            assert_eq!(outputs[0],bytes);assert_eq!(outputs[1],bytes);std::fs::remove_dir_all(root).unwrap();
        }
    }}
    #[test] fn parser_rejects_unknown_methods_and_fields() {
        assert!(serde_json::from_str::<Command>(r#"{"method":"execute"}"#).is_err());
        assert!(serde_json::from_str::<Command>(r#"{"method":"missing","transfer_id":"test","extra":true}"#).is_err());
    }
}

/// Binary companion to the existing JSON command. Metadata remains bounded;
/// payload never becomes a JSON string or a number array.
/// # Safety
/// handle is exclusively borrowed and buffers are readable for their lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_files_command_bytes(handle:*mut PodSyncFiles,command:*const u8,command_length:usize,data:*const u8,length:usize)->*const c_char {
 let Some(h)=(unsafe{handle.as_mut()}) else{return std::ptr::null()};
 #[derive(Deserialize)] #[serde(deny_unknown_fields)]
 struct Chunk {method:String,transfer_id:String,index:usize}
 let result=(||->anyhow::Result<serde_json::Value>{
  anyhow::ensure!(!command.is_null()&&command_length>0&&command_length<=1024&&!data.is_null()&&length<=CHUNK_BYTES,"invalid binary command");
  let c:Chunk=serde_json::from_slice(unsafe{std::slice::from_raw_parts(command,command_length)})?;
  anyhow::ensure!(c.method=="chunk","invalid binary method");
  h.receiver.receive_chunk(&c.transfer_id,c.index,unsafe{std::slice::from_raw_parts(data,length)})?;
  Ok(json!({"index":c.index,"stored":true}))
 })();
 let reply=match result{Ok(value)=>json!({"ok":true,"value":value}),Err(error)=>json!({"ok":false,"code":"file_transfer_error","message":error.to_string()})};
 h.response=CString::new(reply.to_string()).expect("JSON escapes NUL");h.response.as_ptr()
}
