//! Shared foreground/background KV. Each operation reloads under an OS file
//! lock, so separate handles/processes cannot overwrite a stale snapshot.
use std::{collections::BTreeMap, fs::{File, OpenOptions}, io::{self, Read, Write}, path::PathBuf};
use serde_json::{Value, value::{RawValue, to_raw_value}};
type Values = BTreeMap<String, Box<RawValue>>;
#[derive(serde::Deserialize)]
#[serde(tag="op", rename_all="lowercase", deny_unknown_fields)]
pub enum KvMutation { Set {key:String,value:Value}, Delete {key:String} }

#[cfg(unix)]
fn exclusive_lock(file:&File)->io::Result<()> {
    use std::os::fd::AsRawFd;
    loop {
        if unsafe {libc::flock(file.as_raw_fd(),libc::LOCK_EX)}==0 {return Ok(());}
        let error=io::Error::last_os_error();
        if error.kind()!=io::ErrorKind::Interrupted {return Err(error);}
    }
}
#[cfg(not(unix))]
fn exclusive_lock(file:&File)->io::Result<()> {file.lock()}

pub struct KvStore { memory:Values, cached_bytes:Option<Vec<u8>>, path:Option<PathBuf>, quota:usize, budget:Option<(std::time::Instant,crate::background::Cancellation)> }
impl KvStore {
    pub fn open(data_dir:Option<&str>) -> Self {
        Self {memory:BTreeMap::new(),cached_bytes:None,path:data_dir.map(|root|PathBuf::from(root).join("podjs-kv.json")),quota:4*1024*1024,budget:None}
    }
    fn check_budget(&self)->io::Result<()> {
        if self.budget.as_ref().is_some_and(|(deadline,cancel)|std::time::Instant::now()>=*deadline || cancel.is_cancelled()) {
            return Err(io::Error::new(io::ErrorKind::TimedOut,"KV operation cancelled or expired"));
        }
        Ok(())
    }
    fn lock(&self,file:&File)->io::Result<()> {
        if self.budget.is_none() {return exclusive_lock(file);}
        #[cfg(unix)] {
            use std::os::fd::AsRawFd;
            loop {
                self.check_budget()?;
                if unsafe {libc::flock(file.as_raw_fd(),libc::LOCK_EX|libc::LOCK_NB)}==0 {return Ok(());}
                let error=io::Error::last_os_error();
                if !matches!(error.kind(),io::ErrorKind::WouldBlock|io::ErrorKind::Interrupted) {return Err(error);}
                std::thread::sleep(std::time::Duration::from_millis(2));
            }
        }
        #[cfg(not(unix))] {Err(io::Error::new(io::ErrorKind::Unsupported,"Bounded KV locks unavailable"))}
    }
    fn operation<T>(&mut self, write:bool, action:impl FnOnce(&mut Values)->io::Result<T>) -> io::Result<T> {
        self.check_budget()?;
        let Some(path)=&self.path else {
            let mut next=self.memory.clone(); let result=action(&mut next)?;
            if serde_json::to_vec(&next)?.len()>self.quota {return Err(io::Error::other("KV quota exceeded"));}
            if write {self.memory=next;} return Ok(result);
        };
        let root=path.parent().expect("KV parent");std::fs::create_dir_all(root)?;
        let lock=OpenOptions::new().create(true).truncate(false).read(true).write(true).open(root.join("podjs-kv.lock"))?;
        self.lock(&lock)?;
        let (bytes,existed)=match File::open(path) {
            Ok(file)=>{
                let mut bytes=Vec::new();file.take((self.quota+1) as u64).read_to_end(&mut bytes)?;
                if bytes.len()>self.quota {return Err(io::Error::other("KV quota exceeded"));}
                (bytes,true)
            },
            Err(error) if error.kind()==io::ErrorKind::NotFound=>(b"{}".to_vec(),false),
            Err(error)=>return Err(error),
        };
        // Compare actual bytes while holding the same cross-process lock. No
        // mtime/inode shortcut: external same-length edits must be observed.
        // Unrelated values remain canonical raw JSON instead of repeatedly
        // allocating and serializing their entire object graphs on every key.
        let mut values=if self.cached_bytes.as_deref()==Some(bytes.as_slice()) {
            std::mem::take(&mut self.memory)
        } else {
            let decoded:BTreeMap<String,Value>=serde_json::from_slice(&bytes)?;
            decoded.into_iter().map(|(key,value)|Ok((key,to_raw_value(&value)?)))
                .collect::<Result<Values,serde_json::Error>>()?
        };
        self.cached_bytes=None; // Failed mutations cannot poison a later read.
        let result=action(&mut values)?;
        self.check_budget()?;
        let persisted=if write {
            let next=serde_json::to_vec(&values)?;
            if next.len()>self.quota {return Err(io::Error::other("KV quota exceeded"));}
            // The authoritative file was reloaded under the cross-process lock.
            // Repeated state checkpoints need no durable replacement when the
            // canonical bytes are already identical. Changed values still use
            // the same synchronous file+directory durability boundary.
            if !existed || next!=bytes {
                let tmp=root.join("podjs-kv.tmp");
                let mut file=File::create(&tmp)?;file.write_all(&next)?;file.sync_all()?;
                self.check_budget()?;
                std::fs::rename(tmp,path)?;
                File::open(root)?.sync_all()?;
            }
            next
        } else {bytes};
        self.memory=values;self.cached_bytes=Some(persisted);
        Ok(result) // File drop releases lock on every exit, including errors.
    }
    pub fn get(&mut self,key:&str)->io::Result<Option<Value>> {
        self.operation(false,|values|values.get(key).map(|raw|serde_json::from_str(raw.get()).map_err(io::Error::from)).transpose())
    }
    pub fn get_json(&mut self,key:&str)->io::Result<Option<String>> {
        self.operation(false,|values|Ok(values.get(key).map(|raw|raw.get().to_owned())))
    }
    pub fn keys(&mut self)->io::Result<Vec<String>> {self.operation(false,|values|Ok(values.keys().cloned().collect()))}
    pub fn set(&mut self,key:String,value:Value)->io::Result<()> {
        if key.is_empty() || key.len()>128 {return Err(io::Error::other("Invalid KV key"));}
        self.operation(true,|values|{values.insert(key,to_raw_value(&value)?);Ok(())})
    }
    /// Explicit synchronous SDK batch: validate all mutations before one commit.
    pub fn apply(&mut self,mutations:Vec<KvMutation>)->io::Result<()> {
        if mutations.len()>1024 {return Err(io::Error::other("Too many KV mutations"));}
        for mutation in &mutations {let key=match mutation {KvMutation::Set{key,..}|KvMutation::Delete{key}=>key};
            if key.is_empty()||key.len()>128 {return Err(io::Error::other("Invalid KV key"));}
        }
        self.operation(true,|values|{for mutation in mutations {match mutation {
            KvMutation::Set{key,value}=>{values.insert(key,to_raw_value(&value)?);},
            KvMutation::Delete{key}=>{values.remove(&key);},
        }}Ok(())})
    }
    pub fn delete(&mut self,key:&str)->io::Result<bool> {self.operation(true,|values|Ok(values.remove(key).is_some()))}
}

/// The root and method grants are host-owned; guest arguments never select a
/// path or app. Disk syscalls themselves are not preemptible. Deadline checks
/// bound lock waiting and prevent starting a commit after cancellation.
pub fn background_services(root:PathBuf, allowed:std::collections::HashSet<String>)->io::Result<crate::background::Services> {
    if !root.is_absolute() || allowed.iter().any(|method|!matches!(method.as_str(),"kv.get"|"kv.set"|"kv.delete"|"kv.keys")) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput,"Invalid background KV grants"));
    }
    Ok(crate::background::Services {allowed_methods:allowed,dispatch:std::sync::Arc::new(move |request| {
        let (send,receive)=std::sync::mpsc::channel();let root=root.clone();
        std::thread::spawn(move || {
            let result=(||->io::Result<String> {
                let mut store=KvStore::open(root.to_str());
                if store.path.is_none() {return Err(io::Error::other("Invalid KV root encoding"));}
                store.budget=Some((request.deadline,request.cancellation));
                let args:Value=serde_json::from_str(&request.args_json)?;
                let key=||args.get("key").and_then(Value::as_str).filter(|s| !s.is_empty() && s.len()<=128)
                    .ok_or_else(||io::Error::new(io::ErrorKind::InvalidInput,"Invalid KV key"));
                let value=match request.method.as_str() {
                    "kv.get"=>match store.get(key()?)? {Some(value)=>serde_json::json!({"exists":true,"value":value}),None=>serde_json::json!({"exists":false})},
                    "kv.keys"=>serde_json::json!(store.keys()?),
                    "kv.set"=>{store.set(key()?.into(),args.get("value").ok_or_else(||io::Error::new(io::ErrorKind::InvalidInput,"Missing KV value"))?.clone())?;Value::Null},
                    "kv.delete"=>Value::Bool(store.delete(key()?)?),
                    _=>return Err(io::Error::new(io::ErrorKind::PermissionDenied,"Unapproved KV method")),
                };
                Ok(value.to_string())
            })().map_err(|error|match error.kind() {io::ErrorKind::TimedOut=>"cancelled",io::ErrorKind::InvalidInput=>"invalid_argument",_=>"storage_error"}.to_owned());
            let _=send.send(result);
        });
        receive
    })})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] #[cfg(unix)] fn identical_checkpoint_keeps_inode_and_observes_other_handles() {
        use std::os::unix::fs::MetadataExt;
        let root=std::env::temp_dir().join(format!("podjs-kv-noop-{}",std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let path=root.join("podjs-kv.json");
        let mut first=KvStore::open(root.to_str());
        let value=serde_json::json!({"title":"汉字😀","values":[null,1,true]});
        first.set("checkpoint".into(),value.clone()).unwrap();
        let bytes=std::fs::read(&path).unwrap();let inode=std::fs::metadata(&path).unwrap().ino();
        for _ in 0..50 {first.set("checkpoint".into(),value.clone()).unwrap();}
        assert_eq!(std::fs::read(&path).unwrap(),bytes);
        assert_eq!(std::fs::metadata(&path).unwrap().ino(),inode);
        let mut second=KvStore::open(root.to_str());
        second.set("other".into(),Value::from(17)).unwrap();
        first.set("checkpoint".into(),value.clone()).unwrap();
        assert_eq!(first.get("other").unwrap(),Some(Value::from(17)));
        second.set("checkpoint".into(),Value::Null).unwrap();
        first.set("checkpoint".into(),value.clone()).unwrap();
        assert_eq!(second.get("checkpoint").unwrap(),Some(value));
        assert!(first.delete("checkpoint").unwrap());
        let inode=std::fs::metadata(&path).unwrap().ino();
        assert!(!first.delete("checkpoint").unwrap());
        assert_eq!(std::fs::metadata(&path).unwrap().ino(),inode);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn background_handler_shares_foreground_data() {
        let root=std::env::temp_dir().join(format!("podjs-kv-bg-{}",std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let mut front=KvStore::open(root.to_str());front.set("front".into(),Value::from(17)).unwrap();
        let services=background_services(root.clone(),["kv.get","kv.set","kv.delete","kv.keys"].into_iter().map(str::to_owned).collect()).unwrap();
        let source=r#"globalThis.backgroundHandler=async c=>{
            const r=await c.request('kv.get',{key:'front'});if(!r.exists||r.value!==17)return 'failure';
            await c.request('kv.set',{key:'back',value:{text:'汉字😀'}});
            await c.request('kv.delete',{key:'front'});
            return (await c.request('kv.keys',{})).includes('back')?'success':'failure';
        }"#;
        let result=crate::background::run_with_services(source,crate::background::Options {app_id:"kv-bg",task_id:"task",budget_ms:1000,memory_bytes:8*1024*1024,payload_json:"null"},crate::background::Cancellation::default(),Some(services));
        assert_eq!(result.status,crate::background::Status::Success);
        assert_eq!(front.get("back").unwrap().unwrap()["text"],"汉字😀");
        assert_eq!(front.get("front").unwrap(),None);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] #[cfg(unix)] fn bounded_lock_times_out_without_mutation() {
        let root=std::env::temp_dir().join(format!("podjs-kv-lock-{}",std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let held=OpenOptions::new().create(true).truncate(false).write(true).open(root.join("podjs-kv.lock")).unwrap();
        exclusive_lock(&held).unwrap();
        let mut store=KvStore::open(root.to_str());
        store.budget=Some((std::time::Instant::now()+std::time::Duration::from_millis(20),crate::background::Cancellation::default()));
        assert_eq!(store.set("never".into(),Value::Null).unwrap_err().kind(),io::ErrorKind::TimedOut);
        assert!(!root.join("podjs-kv.json").exists());
        drop(held);std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn canonical_disk_bytes_and_key_results_match_value_reference() {
        let root=std::env::temp_dir().join(format!("podjs-kv-diff-{}-{:?}",std::process::id(),std::thread::current().id()));
        std::fs::create_dir_all(&root).unwrap();
        // Deliberately noncanonical initial JSON, Unicode, numeric spellings,
        // duplicate keys and a large unrelated value as on the watch.
        let seed=format!(r#"{{"z":{{"z":1e0,"a":-0.0}},"text":"汉字😀\n","same":1,"same":2,"large":"{}"}}"#, "x".repeat(830_000));
        std::fs::write(root.join("podjs-kv.json"),seed.as_bytes()).unwrap();
        let mut oracle:BTreeMap<String,Value>=serde_json::from_str(&seed).unwrap();
        let mut store=KvStore::open(root.to_str());
        for _ in 0..3 {
            for (key,value) in &oracle {
                assert_eq!(store.get(key).unwrap().as_ref(),Some(value));
                assert_eq!(store.get_json(key).unwrap(),Some(serde_json::to_string(value).unwrap()));
            }
        }
        for (key,value) in [("reader",serde_json::json!({"z":[null,true,1.5],"a":"网页😀"})),("text",Value::Null),("reader",Value::from("new"))] {
            oracle.insert(key.into(),value.clone());store.set(key.into(),value).unwrap();
            assert_eq!(std::fs::read(root.join("podjs-kv.json")).unwrap(),serde_json::to_vec(&oracle).unwrap());
            let mut reopened=KvStore::open(root.to_str());
            for (key,value) in &oracle {assert_eq!(reopened.get(key).unwrap().as_ref(),Some(value));}
        }
        assert!(store.delete("reader").unwrap());oracle.remove("reader");
        assert_eq!(std::fs::read(root.join("podjs-kv.json")).unwrap(),serde_json::to_vec(&oracle).unwrap());
        // Equal-length external replacements invalidate the byte cache.
        std::fs::write(root.join("podjs-kv.json"),br#"{"external":11}"#).unwrap();
        assert_eq!(store.get("external").unwrap(),Some(Value::from(11)));
        std::fs::write(root.join("podjs-kv.json"),br#"{"external":22}"#).unwrap();
        assert_eq!(store.get("external").unwrap(),Some(Value::from(22)));
        assert_eq!(store.get("large").unwrap(),None);
        // A failed quota write neither replaces disk nor leaks into the cache.
        store.quota=32;let before=std::fs::read(root.join("podjs-kv.json")).unwrap();
        assert!(store.set("oversized".into(),Value::from("x".repeat(100))).is_err());
        assert_eq!(std::fs::read(root.join("podjs-kv.json")).unwrap(),before);
        assert_eq!(store.get("oversized").unwrap(),None);
        assert_eq!(store.get("external").unwrap(),Some(Value::from(22)));
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn batch_commits_once_and_rejects_without_poisoning_disk() {
        let root=std::env::temp_dir().join(format!("podjs-kv-batch-{}",std::process::id()));
        std::fs::create_dir_all(&root).unwrap();let mut store=KvStore::open(root.to_str());
        let before=br#"{"other":"unchanged","value":0}"#;std::fs::write(root.join("podjs-kv.json"),before).unwrap();
        store.apply(vec![KvMutation::Set{key:"value".into(),value:serde_json::json!({"nested":[-0.0,"汉😀"]})},KvMutation::Set{key:"temporary".into(),value:Value::Null},KvMutation::Delete{key:"temporary".into()}]).unwrap();
        let value=store.get("value").unwrap().unwrap();assert_eq!(value,serde_json::json!({"nested":[-0.0,"汉😀"]}));assert_eq!(store.get("other").unwrap(),Some(Value::from("unchanged")));
        let committed=std::fs::read(root.join("podjs-kv.json")).unwrap();
        assert!(store.apply(vec![KvMutation::Set{key:"valid".into(),value:Value::Null},KvMutation::Delete{key:"".into()}]).is_err());
        store.quota=committed.len();assert!(store.apply(vec![KvMutation::Delete{key:"value".into()},KvMutation::Set{key:"large".into(),value:Value::from("x".repeat(512))}]).is_err());
        assert_eq!(std::fs::read(root.join("podjs-kv.json")).unwrap(),committed);assert_eq!(store.get("value").unwrap(),Some(value));
        assert!(serde_json::from_str::<Vec<KvMutation>>(r#"[{"op":"set","key":"x","value":1,"extra":true}]"#).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn independently_opened_handles_merge_and_observe_changes() {
        let root=std::env::temp_dir().join(format!("podjs-kv-{}-{:?}",std::process::id(),std::thread::current().id()));
        std::fs::create_dir_all(&root).unwrap();
        let workers:Vec<_>=(0..4).map(|worker| {
            let root=root.clone();std::thread::spawn(move || {
                let mut store=KvStore::open(root.to_str());
                for index in 0..20 {store.set(format!("{worker}:{index}"),Value::from(index)).unwrap();}
            })
        }).collect();
        for worker in workers {worker.join().unwrap();}
        let mut first=KvStore::open(root.to_str());let mut second=KvStore::open(root.to_str());
        assert_eq!(first.keys().unwrap().len(),80);
        second.set("changed".into(),Value::from(17)).unwrap();assert_eq!(first.get("changed").unwrap(),Some(Value::from(17)));
        first.delete("changed").unwrap();assert_eq!(second.get("changed").unwrap(),None);
        std::fs::write(root.join("podjs-kv.json"),b"broken").unwrap();
        assert!(first.set("no-overwrite".into(),Value::Null).is_err());
        assert_eq!(std::fs::read(root.join("podjs-kv.json")).unwrap(),b"broken");
        std::fs::remove_dir_all(root).unwrap();
    }
}
