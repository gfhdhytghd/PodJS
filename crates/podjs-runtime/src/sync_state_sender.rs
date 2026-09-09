//! Durable sender cycle. Pending payload bytes are retained until matching ACK.
use super::*;

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(super) struct Outgoing {
    peer: String,
    acknowledged: u64,
    sent_hash: Option<String>,
    cycle: Option<String>,
    position: usize,
    pending: Option<Pending>,
    last_id: Option<String>,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Pending { message_id: String, from: u64, to: u64, payload: String, digest: String }
fn hash(value: &str) -> String { format!("{:x}", Sha256::digest(value.as_bytes())) }
fn valid_hash(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub(super) fn acknowledgement(stored: &Stored, peer: &str) -> Result<Value> {
    identity(peer)?; ensure!(peer != stored.local_device_id, "self state peer");
    let mut entries = stored.state.entries.clone(); entries.sort_by(|a,b| a.key.cmp(&b.key));
    let digest = hash(&serde_json::to_string(&entries)?);
    let synchronized = stored.outgoing.iter().find(|out| out.peer == peer).is_some_and(|out|
        out.pending.is_none() && out.cycle.is_none() && out.sent_hash.as_ref() == Some(&digest));
    Ok(json!({"synchronized":synchronized,"receivedCursor":stored.state.cursors.get(peer).copied().unwrap_or(0)}))
}
pub(super) fn validate(all: &[Outgoing], local: &str) -> Result<()> {
    ensure!(all.len() <= 128, "sender peer quota");
    let mut peers = std::collections::BTreeSet::new();
    for out in all {
        identity(&out.peer)?;
        ensure!(out.peer != local && peers.insert(&out.peer) && out.acknowledged <= MAX_COUNTER, "invalid sender peer");
        ensure!(out.sent_hash.as_deref().is_none_or(valid_hash), "invalid sent hash");
        if let Some(id) = &out.last_id { identity(id)?; }
        if let Some(cycle) = &out.cycle {
            let mut entries: Vec<Entry> = serde_json::from_str(cycle)?;
            ensure!(entries.len() <= 10000 && out.position <= entries.len(), "invalid cycle position");
            let mut keys = std::collections::BTreeSet::new();
            for entry in &mut entries { validate_entry(entry)?; ensure!(keys.insert(&entry.key), "duplicate cycle key"); }
            if let Some(pending) = &out.pending {
                identity(&pending.message_id)?;
                ensure!(pending.from == out.acknowledged && pending.from < MAX_COUNTER && pending.to == pending.from + 1 &&
                    pending.payload.len() <= 256 * 1024 && hash(&pending.payload) == pending.digest &&
                    out.last_id.as_ref() != Some(&pending.message_id), "invalid pending metadata");
                let mut batch: Batch = serde_json::from_str(&pending.payload)?;
                ensure!(batch.version == 1 && batch.from == pending.from && batch.to == pending.to && batch.entries.len() <= 512 &&
                    (batch.entries.len() > 0 || entries.is_empty()) && batch.entries.len() <= entries.len() - out.position, "invalid pending range");
                for entry in &mut batch.entries { validate_entry(entry)?; }
                ensure!(serde_json::to_value(&batch.entries)? == serde_json::to_value(&entries[out.position..out.position + batch.entries.len()])?, "pending differs from cycle");
            } else { ensure!(out.position < entries.len(), "exhausted unfinished cycle"); }
        } else { ensure!(out.position == 0 && out.pending.is_none(), "sender without cycle"); }
    }
    Ok(())
}
pub(super) fn prepare(stored: &mut Stored, peer: String, message_id: String) -> Result<(Value, bool)> {
    identity(&peer)?; identity(&message_id)?;
    ensure!(peer != stored.local_device_id, "self state peer");
    let index = if let Some(index) = stored.outgoing.iter().position(|out| out.peer == peer) { index } else {
        ensure!(stored.outgoing.len() < 128, "sender peer quota");
        stored.outgoing.push(Outgoing { peer, acknowledged: 0, sent_hash: None, cycle: None, position: 0, pending: None, last_id: None });
        stored.outgoing.len() - 1
    };
    let out = &mut stored.outgoing[index];
    if let Some(pending) = &out.pending { return Ok((json!({"batch":pending}), false)); }
    if out.cycle.is_none() {
        let mut entries = stored.state.entries.clone(); entries.sort_by(|a,b| a.key.cmp(&b.key));
        let cycle = serde_json::to_string(&entries)?;
        if out.sent_hash.as_ref() == Some(&hash(&cycle)) { return Ok((json!({"batch":null}), false)); }
        out.cycle = Some(cycle); out.position = 0;
    }
    ensure!(out.last_id.as_ref() != Some(&message_id) && out.acknowledged < MAX_COUNTER, "sender ID reused or cursor exhausted");
    let entries: Vec<Entry> = serde_json::from_str(out.cycle.as_ref().unwrap())?;
    let mut selected = vec![]; let mut size = 0;
    for entry in entries.iter().skip(out.position).take(512) {
        let extra = serde_json::to_vec(entry)?.len() + 1;
        if size + extra > 256 * 1024 - 256 { break; }
        size += extra; selected.push(entry);
    }
    ensure!(!selected.is_empty() || entries.is_empty(), "entry cannot fit batch");
    let from = out.acknowledged; let to = from + 1;
    let payload = json!({"version":1,"from":from,"to":to,"entries":selected}).to_string();
    let pending = Pending { message_id, from, to, digest: hash(&payload), payload };
    let result = json!({"batch":pending}); out.pending = Some(pending);
    Ok((result, true))
}
pub(super) fn acknowledge(stored: &mut Stored, peer: &str, message_id: &str, cursor: u64, digest: &str) -> Result<bool> {
    identity(peer)?; identity(message_id)?;
    ensure!(peer != stored.local_device_id && cursor <= MAX_COUNTER && valid_hash(digest), "invalid ACK");
    let Some(out) = stored.outgoing.iter_mut().find(|out| out.peer == peer) else { return Ok(false); };
    let Some(pending) = &out.pending else { return Ok(false); };
    if pending.message_id != message_id || pending.to != cursor || pending.digest != digest { return Ok(false); }
    let batch: Batch = serde_json::from_str(&pending.payload)?;
    let cycle = out.cycle.as_ref().ok_or_else(|| anyhow::anyhow!("missing cycle"))?;
    let entries: Vec<Entry> = serde_json::from_str(cycle)?;
    out.position += batch.entries.len(); out.acknowledged = cursor;
    out.last_id = Some(message_id.into()); out.pending = None;
    if out.position == entries.len() { out.sent_hash = Some(hash(cycle)); out.cycle = None; out.position = 0; }
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paginated_cycle_survives_reopen_and_retains_edits_for_next_cycle() {
        let mut stored = load(None, "app", "watch").unwrap();
        stored.schema = 2;
        for i in 0..513 {
            stored.state.entries.push(Entry { key: format!("k{i:04}"), value: json!(true), counter: 1, device_id: "watch".into(), deleted: false });
        }
        stored.state.clock = 1;
        let (first, changed) = prepare(&mut stored, "phone".into(), "first".into()).unwrap();
        assert!(changed);
        let batch: Batch = serde_json::from_str(first["batch"]["payload"].as_str().unwrap()).unwrap();
        assert_eq!(batch.entries.len(), 512);
        stored.state.entries[0].value = json!(false); stored.state.entries[0].counter = 2; stored.state.clock = 2;
        let raw = serde_json::to_vec(&stored).unwrap();
        let mut reopened = load(Some(&raw), "app", "watch").unwrap();
        let (retry, changed) = prepare(&mut reopened, "phone".into(), "ignored".into()).unwrap();
        assert!(!changed); assert_eq!(retry, first);
        assert!(acknowledge(&mut reopened, "phone", "first", 1, first["batch"]["digest"].as_str().unwrap()).unwrap());
        assert!(prepare(&mut reopened, "phone".into(), "first".into()).is_err());
        let (second, _) = prepare(&mut reopened, "phone".into(), "second".into()).unwrap();
        let batch: Batch = serde_json::from_str(second["batch"]["payload"].as_str().unwrap()).unwrap();
        assert_eq!(batch.entries.len(), 1); assert_eq!(batch.from, 1);
        assert!(acknowledge(&mut reopened, "phone", "second", 2, second["batch"]["digest"].as_str().unwrap()).unwrap());
        let (third, _) = prepare(&mut reopened, "phone".into(), "third".into()).unwrap();
        let batch: Batch = serde_json::from_str(third["batch"]["payload"].as_str().unwrap()).unwrap();
        assert_eq!(batch.entries[0].value, json!(false)); assert_eq!(batch.from, 2);
    }
    #[test]
    fn tampered_pending_cannot_pass_load_even_with_recomputed_digest() {
        let mut stored = load(None, "app", "watch").unwrap(); stored.schema = 2;
        prepare(&mut stored, "phone".into(), "one".into()).unwrap();
        let pending = stored.outgoing[0].pending.as_mut().unwrap();
        pending.payload = json!({"version":1,"from":0,"to":1,"entries":[{
            "key":"injected","value":true,"counter":1,"deviceId":"watch","deleted":false
        }]}).to_string();
        pending.digest = hash(&pending.payload);
        assert!(load(Some(&serde_json::to_vec(&stored).unwrap()), "app", "watch").is_err());
    }
}
