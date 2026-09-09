//! Pure state transformation for serialized host CAS stores. No IO or guest
//! authorization is performed here. Commit the returned snapshot before ACK.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, ffi::{CString, c_char}};

const MAX_COUNTER: u64 = 9_007_199_254_740_991;
const MAX_SNAPSHOT: usize = 4 * 1024 * 1024;
#[path = "sync_state_sender.rs"]
mod sender;
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Entry { key: String, value: Value, counter: u64, device_id: String, deleted: bool }
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct State { version: u32, clock: u64, entries: Vec<Entry>, cursors: BTreeMap<String, u64> }
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Receipt { peer: String, from: u64, to: u64, message_id: String, digest: String }
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Stored { schema: u32, app_id: String, local_device_id: String, state: State, incoming: Vec<Receipt>,
    #[serde(default)] outgoing: Vec<sender::Outgoing> }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Batch { version: u32, from: u64, to: u64, entries: Vec<Entry> }
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Request { app_id: String, local_device_id: String, operation: Operation }
#[derive(Deserialize)]
#[serde(tag = "method", deny_unknown_fields)]
enum Operation {
    #[serde(rename = "get")] Get { key: String },
    #[serde(rename = "set")] Set { key: String, value_json: String },
    #[serde(rename = "delete")] Delete { key: String },
    #[serde(rename = "snapshot")] Snapshot,
    #[serde(rename = "receive")] Receive { peer: String, message_id: String, payload_json: String },
    #[serde(rename = "prepare")] Prepare { peer: String, message_id: String },
    #[serde(rename = "acknowledge")] Acknowledge { peer: String, message_id: String, cursor: u64, digest: String },
    #[serde(rename = "acknowledgement")] Acknowledgement { peer: String },
}
fn identity(value: &str) -> Result<()> {
    ensure!(!value.is_empty() && value.len() <= 128 && value.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b)), "invalid state identity"); Ok(())
}
fn normalize(value: &mut Value, depth: usize) -> Result<()> {
    ensure!(depth <= 32, "state nesting limit");
    match value {
        Value::Number(number) => {
            let n = number.as_f64().ok_or_else(|| anyhow::anyhow!("invalid JSON number"))?;
            ensure!(n.is_finite(), "invalid JSON number");
            *number = serde_json::Number::from_f64(if n == 0.0 { 0.0 } else { n }).unwrap();
        }
        Value::Array(values) => for value in values { normalize(value, depth + 1)?; },
        Value::Object(values) => for value in values.values_mut() { normalize(value, depth + 1)?; },
        _ => {}
    }
    Ok(())
}
fn validate_entry(entry: &mut Entry) -> Result<()> {
    identity(&entry.key)?; identity(&entry.device_id)?;
    ensure!(entry.counter <= MAX_COUNTER && (!entry.deleted || entry.value.is_null()), "invalid state revision");
    normalize(&mut entry.value, 0)?;
    ensure!(serde_json::to_string(&entry.value)?.encode_utf16().count() <= 65536, "state value too large"); Ok(())
}
fn entry_result(entry: &Entry) -> Result<Value> {
    Ok(json!({"key":entry.key,"valueJSON":serde_json::to_string(&entry.value)?,"counter":entry.counter,
        "deviceId":entry.device_id,"deleted":entry.deleted}))
}
fn merge(state: &mut State, entries: Vec<Entry>) -> Result<()> {
    let mut table: BTreeMap<String, Entry> = state.entries.iter().cloned().map(|e| (e.key.clone(), e)).collect();
    for mut entry in entries {
        validate_entry(&mut entry)?;
        let replace = if let Some(old) = table.get(&entry.key) {
            let order = (entry.counter, &entry.device_id).cmp(&(old.counter, &old.device_id));
            ensure!(!order.is_eq() || (entry.deleted == old.deleted && entry.value == old.value), "conflicting state revision");
            order.is_gt()
        } else { true };
        state.clock = state.clock.max(entry.counter);
        if replace { table.insert(entry.key.clone(), entry); }
    }
    ensure!(table.len() <= 10000, "state entry quota"); state.entries = table.into_values().collect(); Ok(())
}
fn load(raw: Option<&[u8]>, app: &str, local: &str) -> Result<Stored> {
    let Some(raw) = raw else { return Ok(Stored { schema: 1, app_id: app.into(), local_device_id: local.into(),
        state: State { version: 1, clock: 0, entries: vec![], cursors: BTreeMap::new() }, incoming: vec![], outgoing: vec![] }); };
    ensure!(raw.len() <= MAX_SNAPSHOT, "state snapshot too large");
    let mut stored: Stored = serde_json::from_slice(raw)?;
    ensure!((stored.schema == 1 || stored.schema == 2) && stored.app_id == app && stored.local_device_id == local && stored.state.version == 1 &&
        stored.state.clock <= MAX_COUNTER && stored.state.entries.len() <= 10000 && stored.incoming.len() <= 128, "invalid state snapshot");
    let mut keys = std::collections::BTreeSet::new();
    for entry in &mut stored.state.entries {
        validate_entry(entry)?; ensure!(entry.counter <= stored.state.clock && keys.insert(entry.key.clone()), "invalid snapshot entry");
    }
    let mut peers = std::collections::BTreeSet::new();
    for receipt in &stored.incoming {
        identity(&receipt.peer)?; identity(&receipt.message_id)?;
        ensure!(receipt.from < MAX_COUNTER && receipt.to == receipt.from + 1 && receipt.digest.len() == 64 &&
            receipt.digest.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) &&
            stored.state.cursors.get(&receipt.peer) == Some(&receipt.to) && peers.insert(receipt.peer.clone()), "invalid state receipt");
    }
    ensure!(stored.state.cursors.len() == stored.incoming.len(), "invalid state cursors");
    ensure!(stored.schema == 2 || stored.outgoing.is_empty(), "legacy sender fields");
    sender::validate(&stored.outgoing, local)?;
    Ok(stored)
}
fn transform(raw: Option<&[u8]>, request: Request) -> Result<Value> {
    identity(&request.app_id)?; identity(&request.local_device_id)?;
    let mut stored = load(raw, &request.app_id, &request.local_device_id)?;
    stored.state.entries.sort_by(|a, b| a.key.cmp(&b.key));
    let old_entries = serde_json::to_vec(&stored.state.entries)?;
    let mut changed = false;
    let result = match request.operation {
        Operation::Get { key } => {
            identity(&key)?;
            let entry = stored.state.entries.iter().find(|e| e.key == key && !e.deleted);
            json!({"found":entry.is_some(),"valueJSON":entry.map(|e| serde_json::to_string(&e.value)).transpose()?})
        }
        Operation::Snapshot => json!({"json":serde_json::to_string(&stored.state)?}),
        Operation::Acknowledgement { peer } => sender::acknowledgement(&stored, &peer)?,
        Operation::Prepare { peer, message_id } => {
            let (result, modified) = sender::prepare(&mut stored, peer, message_id)?;
            changed = modified; result
        }
        Operation::Acknowledge { peer, message_id, cursor, digest } => {
            changed = sender::acknowledge(&mut stored, &peer, &message_id, cursor, &digest)?;
            json!({"matched":changed})
        }
        operation @ (Operation::Set { .. } | Operation::Delete { .. }) => {
            let (key, value, deleted) = match operation {
                Operation::Set { key, value_json } => (key, serde_json::from_str(&value_json)?, false),
                Operation::Delete { key } => (key, Value::Null, true), _ => unreachable!()
            };
            ensure!(stored.state.clock < MAX_COUNTER, "state clock exhausted");
            let mut entry = Entry { key, value, deleted, counter: stored.state.clock + 1, device_id: request.local_device_id };
            validate_entry(&mut entry)?; merge(&mut stored.state, vec![entry.clone()])?; changed = true; entry_result(&entry)?
        }
        Operation::Receive { peer, message_id, payload_json } => {
            identity(&peer)?; identity(&message_id)?;
            ensure!(peer != request.local_device_id && payload_json.len() <= 256 * 1024, "invalid state peer or batch size");
            let batch: Batch = serde_json::from_str(&payload_json)?;
            ensure!(batch.version == 1 && batch.from < MAX_COUNTER && batch.to == batch.from + 1 && batch.entries.len() <= 512, "invalid state batch");
            let digest = format!("{:x}", Sha256::digest(payload_json.as_bytes()));
            let current = stored.state.cursors.get(&peer).copied().unwrap_or(0);
            let previous = stored.incoming.iter().position(|r| r.peer == peer);
            let duplicate = batch.to <= current;
            if duplicate {
                let old = previous.map(|i| &stored.incoming[i]).ok_or_else(|| anyhow::anyhow!("missing state receipt"))?;
                ensure!(batch.to == current && old.from == batch.from && old.to == batch.to && old.message_id == message_id && old.digest == digest, "conflicting state replay");
            } else {
                ensure!(batch.from == current && previous.is_none_or(|i| stored.incoming[i].message_id != message_id), "state sequence gap or reused ID");
                ensure!(previous.is_some() || stored.incoming.len() < 128, "state receipt quota");
                merge(&mut stored.state, batch.entries)?; stored.state.cursors.insert(peer.clone(), batch.to);
                let receipt = Receipt { peer, from: batch.from, to: batch.to, message_id, digest: digest.clone() };
                if let Some(index) = previous { stored.incoming[index] = receipt; } else { stored.incoming.push(receipt); }
                changed = true;
            }
            json!({"cursor":batch.to,"digest":digest,"duplicate":duplicate})
        }
    };
    if changed { stored.schema = 2; }
    let snapshot = serde_json::to_string(&stored)?; ensure!(snapshot.len() <= MAX_SNAPSHOT, "state snapshot quota");
    let state_json = if old_entries != serde_json::to_vec(&stored.state.entries)? {
        Some(serde_json::to_string(&stored.state)?)
    } else { None };
    Ok(json!({"snapshot":snapshot,"changed":changed,"stateJSON":state_json,"result":result}))
}
/// # Safety
/// Readable host buffers; NULL snapshot with length zero means absent. Host must
/// authenticate receive requests and atomically CAS before returning receipts.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_state_calculate(snapshot: *const u8, snapshot_len: usize, request: *const u8, request_len: usize) -> *mut c_char {
    let result = (|| -> Result<Value> {
        ensure!(snapshot_len <= MAX_SNAPSHOT && (!snapshot.is_null() || snapshot_len == 0) &&
            !request.is_null() && request_len > 0 && request_len <= 1024 * 1024, "invalid state buffers");
        let raw = if snapshot.is_null() { None } else { Some(unsafe { std::slice::from_raw_parts(snapshot, snapshot_len) }) };
        transform(raw, serde_json::from_slice(unsafe { std::slice::from_raw_parts(request, request_len) })?)
    })();
    let reply = match result { Ok(value) => json!({"ok":true,"value":value}), Err(_) => json!({"ok":false,"code":"sync_state_error"}) };
    CString::new(reply.to_string()).expect("JSON escapes NUL").into_raw()
}
/// # Safety
/// NULL or exactly one response returned by calculate, freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_state_response_free(value: *mut c_char) {
    if !value.is_null() { drop(unsafe { CString::from_raw(value) }); }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(raw: Option<&[u8]>, operation: Value) -> Result<Value> {
        transform(raw, serde_json::from_value(json!({
            "appId":"app", "localDeviceId":"watch", "operation":operation
        }))?)
    }
    fn initial() -> String {
        run(None, json!({"method":"set","key":"key","value_json":"null"}))
            .unwrap()["snapshot"].as_str().unwrap().into()
    }
    #[test]
    fn corrupt_snapshots_never_reset_or_accept_reads() {
        for field in ["schema", "appId", "localDeviceId"] {
            let mut stored: Value = serde_json::from_str(&initial()).unwrap();
            stored[field] = json!("wrong");
            assert!(run(Some(stored.to_string().as_bytes()), json!({"method":"get","key":"key"})).is_err());
        }
        let mut stored: Value = serde_json::from_str(&initial()).unwrap();
        stored["state"]["clock"] = json!(0);
        assert!(run(Some(stored.to_string().as_bytes()), json!({"method":"snapshot"})).is_err());
        assert!(run(Some(b""), json!({"method":"snapshot"})).is_err());
        assert!(run(Some(b"null"), json!({"method":"snapshot"})).is_err());
    }
    #[test]
    fn exhausted_clock_and_oversized_values_fail_without_proposal() {
        let mut stored: Value = serde_json::from_str(&initial()).unwrap();
        stored["state"]["clock"] = json!(MAX_COUNTER);
        let raw = stored.to_string();
        assert!(run(Some(raw.as_bytes()), json!({"method":"delete","key":"key"})).is_err());
        assert!(run(Some(raw.as_bytes()), json!({"method":"get","key":"key"})).is_ok());
        for value in [json!("x".repeat(65535)).to_string(), format!("{}0{}", "[".repeat(33), "]".repeat(33))] {
            assert!(run(None, json!({"method":"set","key":"key","value_json":value})).is_err());
        }
    }
    #[test]
    fn receive_sequence_and_receipt_corruption_fail_closed() {
        let payload = json!({"version":1,"from":0,"to":1,"entries":[]}).to_string();
        let op = json!({"method":"receive","peer":"phone","message_id":"one","payload_json":payload});
        let first = run(None, op.clone()).unwrap();
        let raw = first["snapshot"].as_str().unwrap();
        assert_eq!(run(Some(raw.as_bytes()), op.clone()).unwrap()["changed"], false);
        let mut stored: Value = serde_json::from_str(raw).unwrap();
        stored["state"]["cursors"]["phone"] = json!(2);
        assert!(run(Some(stored.to_string().as_bytes()), op).is_err());
        for (from, to, id) in [(2,3,"two"), (1,2,"one"), (0,2,"two")] {
            assert!(run(Some(raw.as_bytes()), json!({"method":"receive","peer":"phone","message_id":id,
                "payload_json":json!({"version":1,"from":from,"to":to,"entries":[]}).to_string()})).is_err());
        }
    }
    #[test]
    fn ffi_rejects_invalid_bounds_before_dereferencing_and_frees_replies() {
        unsafe {
            for (snapshot, snapshot_len, request, request_len) in [
                (std::ptr::null(), 1, b"{}".as_ptr(), 2),
                (std::ptr::null(), 0, std::ptr::null(), 0),
                (std::ptr::null(), 0, b"{}".as_ptr(), 1024 * 1024 + 1),
                (b"x".as_ptr(), MAX_SNAPSHOT + 1, b"{}".as_ptr(), 2),
            ] {
                let reply = pod_sync_state_calculate(snapshot, snapshot_len, request, request_len);
                let value: Value = serde_json::from_slice(std::ffi::CStr::from_ptr(reply).to_bytes()).unwrap();
                assert_eq!(value, json!({"ok":false,"code":"sync_state_error"}));
                pod_sync_state_response_free(reply);
            }
            pod_sync_state_response_free(std::ptr::null_mut());
        }
    }
}
