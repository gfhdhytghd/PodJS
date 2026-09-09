//! Strict portable file-channel payload validation. No IO or authorization.
use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::ffi::{CString, c_char};
use crate::sync_files::FileManifest;

#[derive(Deserialize, Serialize)]
#[serde(tag = "method", rename_all = "lowercase", deny_unknown_fields)]
enum Request {
    Offer { version: u32, manifest: FileManifest },
    Chunk { version: u32, transfer_id: String, index: usize, data_base64: String },
    Status { version: u32, transfer_id: String },
    Missing { version: u32, transfer_id: String },
    Finish { version: u32, transfer_id: String },
    Cancel { version: u32, transfer_id: String },
}
fn request(bytes: &[u8]) -> Result<Request> {
    ensure!(bytes.len() <= 98304, "file request size");
    let request: Request = serde_json::from_slice(bytes)?;
    let (version, id) = match &request {
        Request::Offer { version, manifest } => { manifest.validate()?; (*version, &manifest.transfer_id) }
        Request::Chunk { version, transfer_id, index, data_base64 } => {
            ensure!(*index < 256 && data_base64.len() <= 87384, "chunk bounds");
            let bytes = STANDARD.decode(data_base64)?;
            ensure!(bytes.len() <= 65536 && STANDARD.encode(bytes) == *data_base64, "noncanonical chunk"); (*version, transfer_id)
        }
        Request::Status { version, transfer_id } | Request::Missing { version, transfer_id } |
        Request::Finish { version, transfer_id } | Request::Cancel { version, transfer_id } => (*version, transfer_id),
    };
    ensure!(version == 1 && !id.is_empty() && id.len() <= 128 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b)), "invalid file identity/version");
    Ok(request)
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Reply { version: u32, #[serde(rename = "type")] kind: String, request_sha256: String, value: Value }
#[derive(Deserialize, Serialize)]
#[serde(untagged, deny_unknown_fields)]
enum Value { Missing { phase: String, missing: Vec<usize> }, Plain { phase: String } }
fn reply(original: &[u8], bytes: &[u8]) -> Result<Reply> {
    let request = request(original)?; ensure!(bytes.len() <= 4096, "file reply size");
    let reply: Reply = serde_json::from_slice(bytes)?;
    ensure!(reply.version == 1 && reply.kind == "reply" && reply.request_sha256 == format!("{:x}",Sha256::digest(original)), "reply request mismatch");
    let phase = match &reply.value {
        Value::Missing { phase, missing } => {
            ensure!(matches!(request, Request::Missing { .. }) && missing.len() <= 256 && missing.iter().all(|i| *i < 256) &&
                missing.windows(2).all(|pair| pair[0] < pair[1]), "missing list mismatch");
            ensure!(!matches!(phase.as_str(), "complete" | "cancelled") || missing.is_empty(), "terminal missing list"); phase
        }
        Value::Plain { phase } => { ensure!(!matches!(request, Request::Missing { .. }), "missing list required"); phase }
    };
    ensure!(matches!(phase.as_str(), "offered" | "accepting" | "accepted" | "complete" | "cancelling" | "cancelled"), "invalid reply phase");
    let valid = match request {
        Request::Finish { .. } => matches!(phase.as_str(), "complete" | "cancelled"),
        Request::Cancel { .. } => phase == "cancelled",
        Request::Chunk { .. } | Request::Missing { .. } => matches!(phase.as_str(), "accepted" | "complete" | "cancelled"),
        _ => true,
    };
    ensure!(valid, "reply phase incompatible"); Ok(reply)
}
fn output<T: Serialize>(value: Result<T>) -> *mut c_char {
    value.and_then(|value| Ok(CString::new(serde_json::to_string(&value)?)?.into_raw())).unwrap_or(std::ptr::null_mut())
}
/// # Safety
/// Readable request bytes. Returns allocated canonical JSON, NULL on rejection.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_request_validate(bytes: *const u8, length: usize) -> *mut c_char {
    if bytes.is_null() || length == 0 || length > 98304 { return std::ptr::null_mut(); }
    output(request(unsafe { std::slice::from_raw_parts(bytes,length) }))
}
/// # Safety
/// Readable original request/reply buffers. Digest binds exact original bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_reply_validate(original: *const u8, original_length: usize, bytes: *const u8, length: usize) -> *mut c_char {
    if original.is_null() || bytes.is_null() || original_length == 0 || original_length > 98304 || length == 0 || length > 4096 { return std::ptr::null_mut(); }
    output(reply(unsafe { std::slice::from_raw_parts(original,original_length) }, unsafe { std::slice::from_raw_parts(bytes,length) }))
}
/// # Safety
/// NULL or an allocated validator response, freed exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_file_wire_free(value: *mut c_char) { if !value.is_null() { drop(unsafe { CString::from_raw(value) }); } }
