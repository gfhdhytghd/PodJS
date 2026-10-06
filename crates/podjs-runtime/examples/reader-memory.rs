//! Linux ownership probe: production PodRuntime + QuickJS + retained Rust UI.
//! Android paragraph shaping, bitmap raster pixels, image decode and GPU are
//! explicitly simulated/absent. No device or production database is accessed.
use anyhow::{Context, Result, ensure};
use podjs_runtime::*;
use serde_json::{Value, json};
use std::{collections::BTreeMap, ffi::{CStr,CString}, fs::{self,File}, io::Write, path::PathBuf, os::fd::AsRawFd};

fn arg(name:&str, default:&str)->String {
    std::env::args().find_map(|v|v.strip_prefix(&format!("--{name}=")).map(str::to_owned)).unwrap_or_else(||default.into())
}
fn hash(bytes:&[u8])->String {let mut h=0xcbf29ce484222325u64;for b in bytes {h=(h^u64::from(*b)).wrapping_mul(0x100000001b3);}format!("{h:016x}")}
fn error()->String {unsafe{CStr::from_ptr(pod_runtime_last_error())}.to_string_lossy().into_owned()}
fn ok(code:i32)->Result<()> {ensure!(code==0,"runtime {code}: {}",error());Ok(())}
fn event(runtime:*mut PodRuntime,event:Value)->Result<()> {let s=CString::new(event.to_string())?;ok(pod_runtime_post_event(runtime,s.as_ptr()))}
// Deterministic, synthetic paragraph replies exercise production paragraph
// attachment/key lifetimes. This is not Android StaticLayout or typography.
fn paragraph(args:&Value)->Result<Value> {
    let text=args["text"].as_str().context("paragraph text")?;
    let width=args["widthPx"].as_f64().context("paragraph width")?;
    let size=args["fontSizePx"].as_f64().context("paragraph size")?;
    let height=args["lineHeightPx"].as_f64().unwrap_or(size*1.5).ceil();
    let mut lines=Vec::new();let(mut start,mut at,mut pixels)=(0,0,0.0);
    for c in text.chars() {
        let advance=if c.is_ascii(){size*0.5}else{size};
        if c!='\n'&&pixels>0.0&&pixels+advance>width {
            let top=lines.len() as f64*height;
            lines.push(json!({"start":start,"end":at,"visibleEnd":at,"topPx":top,"bottomPx":top+height,"baselinePx":top+height*0.8}));
            start=at;pixels=0.0;
        }
        at+=c.len_utf16();pixels+=advance;
        if c=='\n' {
            let top=lines.len() as f64*height;
            lines.push(json!({"start":start,"end":at,"visibleEnd":at-1,"topPx":top,"bottomPx":top+height,"baselinePx":top+height*0.8}));
            start=at;pixels=0.0;
        }
    }
    if start<at||lines.is_empty()||text.ends_with('\n') {
        let top=lines.len() as f64*height;
        lines.push(json!({"start":start,"end":at,"visibleEnd":at,"topPx":top,"bottomPx":top+height,"baselinePx":top+height*0.8}));
    }
    Ok(json!({"lines":lines}))
}
struct Mapping {base:*mut libc::c_void,len:usize}
impl Mapping {
    fn open(path:PathBuf)->Result<Self> {
        let file=File::open(path)?;let len=file.metadata()?.len() as usize;ensure!(len>0,"empty pak");
        let base=unsafe{libc::mmap(std::ptr::null_mut(),len,libc::PROT_READ,libc::MAP_PRIVATE,file.as_raw_fd(),0)};
        ensure!(base!=libc::MAP_FAILED,"mmap: {}",std::io::Error::last_os_error());Ok(Self{base,len})
    }
    fn bytes(&self)->&[u8] {unsafe{std::slice::from_raw_parts(self.base.cast(),self.len)}}
    fn resident_bytes(&self)->Option<usize> {
        let page=unsafe{libc::sysconf(libc::_SC_PAGESIZE)};if page<=0{return None;}
        let mut pages=vec![0u8;self.len.div_ceil(page as usize)];
        if unsafe{libc::mincore(self.base,self.len,pages.as_mut_ptr())}!=0{return None;}
        Some(pages.iter().filter(|v|**v&1!=0).count()*page as usize)
    }
}
impl Drop for Mapping {fn drop(&mut self){unsafe{libc::munmap(self.base,self.len);}}}
struct Owner {runtime:*mut PodRuntime,dir:PathBuf}
impl Drop for Owner {fn drop(&mut self){pod_runtime_destroy(self.runtime);let _=fs::remove_dir_all(&self.dir);}}
struct Probe {owner:Owner,mapping:Mapping,clock:f64,meta:Value,delays:BTreeMap<i64,f64>,services:BTreeMap<String,u64>,output:File}
impl Probe {
    fn reply(&self,id:i64,value:Value)->Result<()> {event(self.owner.runtime,json!({"t":"service.result","id":id,"ok":true,"value":value}))}
    fn fail(&self,id:i64)->Result<()> {event(self.owner.runtime,json!({"t":"service.result","id":id,"ok":false,"code":"probe_unavailable","message":"Not provided by offline probe"}))}
    fn rgba(&self,folder:&str,id:i64,width:u64,height:u64)->Result<String> {
        let name=format!("{folder}/{}-{id}.rgba",if folder=="text"{"line"}else{"decoded"});
        fs::write(self.owner.dir.join("files").join(&name),vec![0u8;(width*height*4) as usize])?;
        Ok(name)
    }
    fn drain(&mut self)->Result<()> {
        // A bounded service drain reaches a quiescent turn without ticking
        // the surface twice or retaining any sample data in the guest heap.
        for _ in 0..128 {
            let mut count=0;
            loop {
                let p=pod_runtime_poll_effect(self.owner.runtime);if p.is_null(){break;}
                let v:Value=serde_json::from_str(unsafe{CStr::from_ptr(p)}.to_str()?)?;
                let t=v["t"].as_str().unwrap_or("");
                if t=="bench.meta" {self.meta=v;continue;}
                if t!="service.request"{continue;}
                count+=1;
                let id=v["id"].as_i64().context("request id")?;
                let method=v["method"].as_str().context("method")?;
                *self.services.entry(method.into()).or_default()+=1;
                let a=&v["args"];
                match method {
                    "text.raster"=>{
                        let width=a["widthPx"].as_u64().context("width")?.next_power_of_two();
                        let height=a["heightPx"].as_u64().context("height")?.next_power_of_two();
                        ensure!(width<=512&&height<=512,"oversized raster fixture");
                        let text=a["text"].as_str().context("text")?;
                        let carets:Vec<f64>=(0..=text.encode_utf16().count()).map(|i|i as f64*14.0).collect();
                        let path=self.rgba("text",id,width,height)?;
                        self.reply(id,json!({"path":path,"width":width,"height":height,"carets":carets}))?;
                    }
                    "text.layout"=>self.reply(id,paragraph(a)?)?,
                    "text.layoutBatch"=>{
                        let values=a["paragraphs"].as_array().context("paragraph batch")?.iter().map(paragraph).collect::<Result<Vec<_>>>()?;
                        self.reply(id,json!({"results":values}))?;
                    }
                    "text.environment"=>{
                        let sizes:Vec<f64>=a["spValues"].as_array().into_iter().flatten().filter_map(Value::as_f64).map(|v|v*1.75).collect();
                        self.reply(id,json!({"density":1.75,"fontScale":1,"spValuesPx":sizes}))?;
                    }
                    "image.decode"=>{let path=self.rgba("images",id,256,128)?;self.reply(id,json!({"path":path,"width":256,"height":128,"sourceWidth":512,"sourceHeight":256}))?;}
                    "image.release"=>self.reply(id,json!({}))?,
                    "file.delete"=>{
                        let path=a["path"].as_str().unwrap_or("");
                        let owned=(path.starts_with("text/line-")||path.starts_with("images/decoded-"))&&!path.contains("..");
                        let deleted=owned&&fs::remove_file(self.owner.dir.join("files").join(path)).is_ok();
                        self.reply(id,json!({"deleted":deleted}))?;
                    }
                    "file.stat"=>self.reply(id,json!({"file":true,"size":512}))?,
                    "file.mkdir"=>self.reply(id,json!({"created":true}))?,
                    "runtime.delay"=>{self.delays.insert(id,self.clock+a["ms"].as_f64().unwrap_or(0.0));}
                    _=>self.fail(id)?,
                }
            }
            if count==0 {return Ok(());}
            ok(pod_runtime_pump_services(self.owner.runtime))?;
        }
        anyhow::bail!("service drain did not quiesce")
    }
    fn step(&mut self,phase:&str,extra:Value)->Result<()> {
        let mut e=json!({"t":"bench.step","phase":phase,"clockMs":self.clock});
        if let Some(fields)=extra.as_object(){for(k,v)in fields {e[k]=v.clone();}}
        event(self.owner.runtime,e)
    }
    fn frame(&mut self)->Result<()> {
        self.clock+=1000.0/60.0;
        self.step("tick",Value::Null)?;
        let due:Vec<_>=self.delays.iter().filter(|(_,t)|**t<=self.clock).map(|(id,_)|*id).collect();
        for id in due {self.delays.remove(&id);self.reply(id,Value::Null)?;}
        let frame=PodInputFrame{struct_size:std::mem::size_of::<PodInputFrame>() as u32,buttons:0,analog:0x8080,
            touches:std::ptr::null(),touch_count:0,rotary_primary_millidegrees:0,rotary_secondary_millidegrees:0};
        ok(pod_runtime_frame(self.owner.runtime,&frame))?;
        self.drain()
    }
    fn warm(&mut self,frames:usize)->Result<()> {for _ in 0..frames {self.frame()?;}Ok(())}
    fn sample(&mut self,phase:&str,round:usize)->Result<Value> {
        let mut sample=unsafe{&*self.owner.runtime}.memory_stats();
        sample["phase"]=json!(phase);sample["round"]=json!(round);sample["virtualMs"]=json!(self.clock);
        sample["services"]=json!(self.services);sample["linuxPakResidentBytes"]=json!(self.mapping.resident_bytes());
        let status=fs::read_to_string("/proc/self/status")?;
        sample["linuxRssBytes"]=json!(status.lines().find(|v|v.starts_with("VmRSS:")).and_then(|v|v.split_whitespace().nth(1)).and_then(|v|v.parse::<u64>().ok()).map(|kb|kb*1024));
        #[cfg(target_env="gnu")] {let alloc=unsafe{libc::mallinfo2()};sample["linuxAllocatorInUseBytes"]=json!(alloc.uordblks+alloc.hblkhd);}
        writeln!(self.output,"{sample}")?;self.output.flush()?;
        println!("MEM phase={phase} round={round} qjs={} nodes={} tex={} text={} rss={}",sample["quickjs"]["usedBytes"],sample["surface"]["nodes"],sample["surface"]["textureBytes"],sample["sdk"]["systemText"]["cached"],sample["linuxRssBytes"]);
        Ok(sample)
    }
}
fn main()->Result<()> {
    let source=fs::read_to_string(arg("bundle","/tmp/reader-memory.js"))?;
    let html=fs::read_to_string(arg("html",""))?;
    let timeline:Value=serde_json::from_slice(&fs::read(arg("timing",""))?)?;
    let package=PathBuf::from(arg("package",""));
    let mapped=Mapping::open(package.join("main.pak"))?;
    let mut manifest:Value=serde_json::from_slice(&fs::read(package.join("pod.manifest.json"))?)?;
    let bundle=format!("globalThis.__readerFixture={};\n{source}",json!({"html":html}));
    let mut caps=json!(["input.touch","data.kv","text.raster","text.environment","data.files.chunks","data.images","runtime.timer","host.lifecycle"]);
    let paragraphs=arg("paragraph-layout","1")=="1";
    if paragraphs {caps.as_array_mut().unwrap().push(json!("text.layout"));}
    manifest["bundleHash"]=json!(hash(bundle.as_bytes()));manifest["bytecode"]=Value::Null;manifest["capabilities"]=caps.clone();
    // Keep the real pak/integrity/fonts, while validating the probe bundle
    // and explicit simulated capabilities instead of the production UI entry.
    manifest["pocketjsRevision"]=json!(option_env!("PODJS_POCKETJS_REVISION").unwrap_or("0a90bf904d835210e52a11ed275a86d0040b5086"));
    let dir=std::env::temp_dir().join(format!("podjs-reader-memory-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_nanos()));
    for folder in ["files/text","files/images"]{fs::create_dir_all(dir.join(folder))?;}
    let root=CString::new(dir.to_str().context("temp path")?)?;let target=CString::new("android-watch")?;let capabilities=CString::new(caps.to_string())?;
    let config=PodRuntimeConfig{struct_size:std::mem::size_of::<PodRuntimeConfig>() as u32,target_id:target.as_ptr(),host_abi:3,raster_density:2,
        physical_width:466,physical_height:466,display_density:1.75,display_shape:0,safe_top:0.0,safe_right:0.0,safe_bottom:0.0,safe_left:0.0,
        data_dir:root.as_ptr(),capabilities_json:capabilities.as_ptr()};
    let runtime=pod_runtime_create(&config);ensure!(!runtime.is_null(),"{}",error());
    let owner=Owner{runtime,dir};
    // Mapping was declared before owner and is moved after owner in Probe:
    // runtime/font workers are always destroyed before munmap, including errors.
    let mapping=mapped.bytes();
    unsafe{ok(pod_runtime_load_pak_mapped(runtime,mapping.as_ptr(),mapping.len()))?;}
    let manifest=CString::new(manifest.to_string())?;ok(pod_runtime_validate_package(runtime,manifest.as_ptr()))?;
    ok(pod_runtime_eval_bundle(runtime,bundle.as_ptr(),bundle.len(),std::ptr::null()))?;
    let pak_bytes=mapping.len();
    let mut probe=Probe{owner,mapping:mapped,clock:0.0,meta:Value::Null,delays:BTreeMap::new(),services:BTreeMap::new(),output:File::create(arg("out","/tmp/reader-memory.jsonl"))?};
    probe.drain()?;probe.warm(80)?;
    ensure!(probe.meta["layoutHeight"].as_f64().is_some(),"reader fixture did not publish metadata");
    let height=probe.meta["layoutHeight"].as_f64().unwrap();
    writeln!(probe.output,"{}",json!({"host":"Linux production PodRuntime/QuickJS/Rust surface; synthetic service layout/raster/image pixels; style 0; no Java/Vulkan",
        "fixture":probe.meta,"htmlFnv":hash(html.as_bytes()),"timeline":timeline,"syntheticParagraphLayout":paragraphs,"pakBytes":pak_bytes}))?;
    probe.sample("reader-before",0)?;
    let rounds:usize=arg("rounds","20").parse()?;ensure!((1..=100).contains(&rounds),"rounds must be 1..100");
    let ticks=(timeline["elapsedSeconds"].as_f64().context("timeline duration")?*60.0).ceil() as usize;
    let gestures=timeline["gestures"].as_array().context("timeline gestures")?;
    for round in 1..=rounds {
        probe.step("scroll",json!({"offset":(height-2200.0).max(0.0)}))?;probe.warm(60)?;
        let mut current:Option<usize>=None;
        for tick in 0..ticks {
            let elapsed=tick as f64/60.0;
            // Match the existing reader-scroll bench's 250 ms swipe and the
            // captured start timestamps (end timestamps include host overhead).
            let next=gestures.iter().position(|g|{let start=g["startSeconds"].as_f64().unwrap();elapsed>=start&&elapsed<start+0.25});
            if next!=current {
                if let Some(i)=current {probe.step("end",json!({"velocity":if gestures[i]["direction"]=="up"{500}else{-500}}))?;}
                if next.is_some(){probe.step("begin",Value::Null)?;}
                current=next;
            }
            if let Some(i)=current {probe.step("drag",json!({"delta":if gestures[i]["direction"]=="up"{500.0/60.0}else{-500.0/60.0}}))?;}
            probe.frame()?;
            if tick%60==59 {probe.sample("scroll",round)?;}
        }
        if let Some(i)=current {probe.step("end",json!({"velocity":if gestures[i]["direction"]=="up"{500}else{-500}}))?;}
        probe.warm(10)?;probe.sample("round-end",round)?;
    }
    if arg("sweep","1")=="1" {
        for lap in 1..=5 {for direction in [false,true] {for i in 0..100 {
            let position=if direction{99-i}else{i};probe.step("scroll",json!({"offset":height*position as f64/99.0}))?;probe.warm(4)?;
        }}probe.sample("full-sweep",lap)?;}
    }
    probe.step("stop",Value::Null)?;probe.warm(80)?;let stopped=probe.sample("after-stop",0)?;
    probe.warm(3600)?;let idle=probe.sample("after-idle-60s",0)?;
    for sample in [&stopped,&idle] {
        ensure!(sample["sdk"]["systemText"]["cached"]==0&&sample["sdk"]["systemText"]["active"]==0&&sample["sdk"]["systemText"]["queueSlots"]==0,"text lifecycle did not drain");
        ensure!(sample["sdk"]["bench"]["errors"]==0,"reader reported fixture errors");
    }
    drop(probe);
    Ok(())
}
