use podjs_runtime::background::{self, Cancellation, Options, Services};
use std::{
    collections::HashSet,
    io::Write,
    process::Command,
    sync::{mpsc, Arc, Mutex},
};
#[test]
fn production_sdk_crc_utf8_and_gzip_use_bounded_rust_in_headless_namespace() {
    let sdk = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/framework/tests/native-cpu.fixture.ts");
    let output = Command::new("bun")
        .args(["build", "--target=browser", "--format=iife"])
        .arg(&sdk)
        .output()
        .expect("Bun fixture compiler");
    assert!(
        output.status.success(),
        "fixture build failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let source = String::from_utf8(output.stdout).unwrap();
    let bytes: Vec<u8> = (0..32 * 1024 * 1024 + 17)
        .map(|i| ((i * 31 + i % 251) & 255) as u8)
        .collect();
    let expected = crc32fast::hash(&bytes);
    let mut gzip = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    gzip.write_all(&vec![65; 131072]).unwrap();
    let gzip = gzip.finish().unwrap();
    let setup = format!(
        "globalThis.__expectedCrc={expected};globalThis.__gzipFixture=new Uint8Array({});",
        serde_json::to_string(&gzip).unwrap()
    );
    let metrics = Arc::new(Mutex::new(Vec::new()));
    let seen = metrics.clone();
    let services = Services {
        allowed_methods: HashSet::from(["cloud.host".to_owned(), "probe.metric".to_owned()]),
        dispatch: Arc::new(move |request| {
            assert_eq!(
                request.method, "probe.metric",
                "unexpected IO during codec computation"
            );
            seen.lock()
                .unwrap()
                .push(serde_json::from_str::<serde_json::Value>(&request.args_json).unwrap());
            let (tx, rx) = mpsc::channel();
            tx.send(Ok("null".into())).unwrap();
            rx
        }),
    };
    let began = std::time::Instant::now();
    let result = background::run_with_services(
        &(setup + &source),
        Options {
            app_id: "cpu-codec-fixture",
            task_id: "cpu",
            budget_ms: 300000,
            memory_bytes: 128 * 1024 * 1024,
            payload_json: "null",
        },
        Cancellation::default(),
        Some(services),
    );
    assert_eq!(
        result.status,
        background::Status::Success,
        "{}",
        result.code
    );
    let rows = metrics.lock().unwrap();
    eprintln!(
        "HEADLESS_CPU_NATIVE_ROWS {}",
        serde_json::to_string(&*rows).unwrap()
    );
    assert_eq!(rows.len(), 1);
    let row = &rows[0];
    assert_eq!(row["checksum"].as_u64().unwrap(), expected as u64);
    assert!(row["calls"].as_u64().unwrap() >= 129);
    assert_eq!(row["maximum"], 262144);
    assert!(
        row["yields"].as_u64().unwrap() < 100,
        "native CRC still yields per 4 KiB"
    );
    assert!(
        row["elapsedMillis"].as_u64().unwrap() < 5000,
        "native CRC exceeded 5s desktop budget"
    );
    eprintln!(
        "HEADLESS_CPU_NATIVE {} sandbox_wall_ms={}",
        row,
        began.elapsed().as_millis()
    );
}

#[test]
fn numeric_cpu_trace_is_available_before_synchronous_work_and_cannot_publish_guest_text() {
    let rows = Arc::new(Mutex::new(Vec::new()));
    let seen = rows.clone();
    let services = Services {
        allowed_methods: HashSet::from(["cloud.host".to_owned()]),
        dispatch: Arc::new(move |request| {
            assert_eq!(request.method, "cloud.host");
            seen.lock()
                .unwrap()
                .push(serde_json::from_str::<serde_json::Value>(&request.args_json).unwrap());
            let (_tx, rx) = mpsc::channel();
            rx
        }),
    };
    let source = r#"globalThis.backgroundHandler=()=>{
   if(typeof __podCpuTrace!=='function'||typeof pod!=='undefined')return 'failure';
   if(!__podCpuTrace(9,1,2,0,0,7))return 'failure';
   __podCpuTrace(9,1,2,1,1,7);
   for(const bad of [-1,64,NaN,Infinity,0.5])if(__podCpuTrace(bad,0,0,0,0,7))return 'failure';
   if(__podCpuTrace(1,3,0,0,0,7)||__podCpuTrace(1,0,6,0,0,7)||__podCpuTrace(1,0,0,300001,0,7)||__podCpuTrace(1,0,0,0,4,7))return 'failure';
   const end=Date.now()+25;while(Date.now()<end){}
   if(!__podCpuTrace(9,1,2,25,2,7))return 'failure';return 'success';
 };"#;
    let result = background::run_with_services(
        source,
        Options {
            app_id: "cpu-trace-fixture",
            task_id: "cpu",
            budget_ms: 1000,
            memory_bytes: 8 * 1024 * 1024,
            payload_json: "null",
        },
        Cancellation::default(),
        Some(services),
    );
    assert_eq!(
        result.status,
        background::Status::Success,
        "{}",
        result.code
    );
    let rows = rows.lock().unwrap();
    assert_eq!(rows.len(), 2);
    for row in rows.iter() {
        assert_eq!(row["method"], "log.append");
        assert_eq!(row["args"]["path"], "app_log.txt");
        let text = row["args"]["text"].as_str().unwrap();
        let at = text.find('{').unwrap();
        let fields: serde_json::Value = serde_json::from_str(text[at..].trim()).unwrap();
        assert_eq!(fields["cpuStageId"], 9);
        assert_eq!(fields["sizeClass"], 2);
        assert_eq!(fields.as_object().unwrap().len(), 6);
        assert!(!text.contains("fixture"));
    }
    let no_grant = background::run(
        "globalThis.backgroundHandler=()=>typeof __podCpuTrace==='undefined'?'success':'failure';",
        Options {
            app_id: "cpu-trace-no-grant",
            task_id: "cpu",
            budget_ms: 1000,
            memory_bytes: 8 * 1024 * 1024,
            payload_json: "null",
        },
        Cancellation::default(),
    );
    assert_eq!(no_grant.status, background::Status::Success);
}

#[test]
fn interrupt_sampler_identifies_a_blocking_phase_even_when_its_start_was_rate_limited() {
    let rows = Arc::new(Mutex::new(Vec::new()));
    let seen = rows.clone();
    let services = Services {
        allowed_methods: HashSet::from(["cloud.host".to_owned()]),
        dispatch: Arc::new(move |request| {
            seen.lock()
                .unwrap()
                .push(serde_json::from_str::<serde_json::Value>(&request.args_json).unwrap());
            let (_tx, rx) = mpsc::channel();
            rx
        }),
    };
    let source = r#"globalThis.backgroundHandler=()=>{
  __podCpuTrace(9,1,1,0,0,1);__podCpuTrace(9,1,1,1,2,1);
  // A second call starts inside the 5s event throttle, then blocks synchronously.
  __podCpuTrace(9,1,3,0,0,2);const until=Date.now()+5200;while(Date.now()<until){}
  __podCpuTrace(9,1,3,5200,2,2);return 'success';
 };"#;
    let result = background::run_with_services(
        source,
        Options {
            app_id: "cpu-sync-sampler-fixture",
            task_id: "cpu",
            budget_ms: 30000,
            memory_bytes: 8 * 1024 * 1024,
            payload_json: "null",
        },
        Cancellation::default(),
        Some(services),
    );
    assert_eq!(
        result.status,
        background::Status::Success,
        "{}",
        result.code
    );
    let rows = rows.lock().unwrap();
    let phases: Vec<serde_json::Value> = rows
        .iter()
        .map(|row| {
            let text = row["args"]["text"].as_str().unwrap();
            serde_json::from_str(text[text.find('{').unwrap()..].trim()).unwrap()
        })
        .collect();
    assert!(
        phases.iter().any(|r| r["cpuStageId"] == 9
            && r["sizeClass"] == 3
            && r["outcome"] == "progress"
            && r["elapsedMillis"].as_u64().unwrap() >= 5000),
        "synchronous phase not sampled: {phases:?}"
    );
    assert!(phases.iter().all(|r| r.as_object().unwrap().len() == 6));
}
