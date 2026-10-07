//! Offline JS phase probe using the runtime's exact rquickjs dependency/features.
//! cargo run -p podjs-runtime --example service-bench --release -- <bundle.js>
use anyhow::{ensure, Context as _};
use rquickjs::{CatchResultExt, Context, Function, Object, Runtime};
use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let path = std::env::args()
        .nth(1)
        .context("usage: service-bench <bundle.js>")?;
    let source = std::fs::read(path)?;
    let rt = Runtime::new()?;
    let ctx = Context::full(&rt)?;
    let began = Instant::now();
    rt.set_interrupt_handler(Some(Box::new(move || began.elapsed().as_secs() > 120)));
    ctx.with(|ctx| -> anyhow::Result<()> {
        ctx.globals().set(
            "__benchNow",
            Function::new(ctx.clone(), move || began.elapsed().as_secs_f64() * 1000.0)?,
        )?;
        ctx.globals().set(
            "__benchPrint",
            Function::new(ctx.clone(), |line: String| println!("{line}"))?,
        )?;
        let pod = Object::new(ctx.clone())?;
        if std::env::var("PODJS_BENCH_PORTABLE").as_deref() != Ok("1") {
            podjs_runtime::byte_codecs::mount(ctx.clone(), &pod)?;
            podjs_runtime::html::mount(ctx.clone(), &pod)?;
            podjs_runtime::html_preview::mount(ctx.clone(), &pod)?;
            podjs_runtime::xml::mount(ctx.clone(), &pod)?;
            podjs_runtime::json_projection::mount(ctx.clone(), &pod)?;
            // Synthetic metric parity fixture, not an Android font measurement.
            pod.set(
                "wrapAtlasTextChunk",
                Function::new(
                    ctx.clone(),
                    |text: rquickjs::String, _slot: i32, width: f64, scale: f64, tracking: f64| {
                        let text = text.to_string().ok()?;
                        podjs_runtime::text_wrap::wrap(&text, width, scale, tracking, |s| {
                            s.encode_utf16().count() as f64 * 7.0
                        })
                    },
                )?,
            )?;
            pod.set(
                "decodeUtf8",
                Function::new(ctx.clone(), podjs_runtime::decode_utf8)?,
            )?;
        }
        // Optional private scratch KV directory, never a production app root.
        // This uses the same locked/fsync/rename store and wire values as mount_pod.
        if let Ok(root) = std::env::var("PODJS_BENCH_KV_DIR") {
            let store = std::rc::Rc::new(std::cell::RefCell::new(podjs_runtime::kv::KvStore::open(Some(&root))));
            let get = store.clone();
            pod.set("kvGet", Function::new(ctx.clone(), move |key:String| get.borrow_mut().get_json(&key).ok().flatten())?)?;
            pod.set("kvSet", Function::new(ctx.clone(), move |key:String,value:String| -> i32 {
                let Ok(value)=serde_json::from_str::<serde_json::Value>(&value) else {return 1;};
                if store.borrow_mut().set(key,value).is_ok(){0}else{1}
            })?)?;
        }
        ctx.globals().set("pod", pod)?;
        ctx.eval::<(), _>(source)
            .catch(&ctx)
            .map_err(|e| anyhow::anyhow!("{e:?}"))?;
        Ok(())
    })?;
    let mut turns = 0;
    let mut jobs = 0;
    let mut max_job_ms = 0.0_f64;
    loop {
        let job_began = Instant::now();
        if rt
            .execute_pending_job()
            .map_err(|e| anyhow::anyhow!("{e:?}"))?
        {
            jobs += 1;
            max_job_ms = max_job_ms.max(job_began.elapsed().as_secs_f64() * 1000.0);
            continue;
        }
        let pumped = ctx.with(|ctx| {
            ctx.eval::<bool, _>("typeof __benchPump==='function' && __benchPump()")
                .catch(&ctx)
                .map_err(|e| anyhow::anyhow!("{e:?}"))
        })?;
        if !pumped {
            break;
        }
        turns += 1;
    }
    println!(
        "{}",
        serde_json::json!({"phase":"host.summary","jobs":jobs,"turns":turns,"maxJobMs":max_job_ms})
    );
    ensure!(
        ctx.with(|ctx| ctx.globals().get::<_, bool>("__benchDone"))
            .unwrap_or(false),
        "benchmark did not complete"
    );
    Ok(())
}
