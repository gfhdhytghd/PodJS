//! Offline mounted entry probe with real QuickJS, UiSurface and mapped fonts.
//! IO replies are explicit JS fixtures. Never reads a device or formal store.
use anyhow::{Context as _, ensure};
use pocket_mod::Guest;
use pocket_ui_surface::UiSurface;
use rquickjs::{CatchResultExt, Function, Object};
use std::time::{Duration, Instant};
fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let source =
        std::fs::read_to_string(args.next().context("reader-entry <bundle.js> <main.pak>")?)?;
    let pak = std::fs::read(args.next().context("main.pak required")?)?;
    // Leaked immutable storage outlives the surface and its async font worker;
    // the standalone process exits after one probe.
    let pak: &'static [u8] = Box::leak(pak.into_boxed_slice());
    let guest = Guest::new()?;
    let surface = UiSurface::new_with_density((233.0, 233.0), 2);
    unsafe {
        surface.feed_pak_mapped(pak);
    }
    surface.mount(&guest)?;
    let began = Instant::now();
    guest.with(|ctx| -> anyhow::Result<()> {
        ctx.globals().set(
            "__benchNow",
            Function::new(ctx.clone(), move || began.elapsed().as_secs_f64() * 1000.0)?,
        )?;
        ctx.globals().set(
            "__benchPrint",
            Function::new(ctx.clone(), |line: String| println!("{line}"))?,
        )?;
        ctx.globals().set(
            "__benchSleep",
            Function::new(ctx.clone(), |ms: f64| {
                if ms.is_finite() && ms > 0.0 && ms <= 1000.0 {
                    std::thread::sleep(Duration::from_secs_f64(ms / 1000.0));
                }
            })?,
        )?;
        let pod = Object::new(ctx.clone())?;
        podjs_runtime::byte_codecs::mount(ctx.clone(), &pod)?;
        podjs_runtime::html::mount(ctx.clone(), &pod)?;
        podjs_runtime::text_wrap::mount(ctx.clone(), &pod, surface.clone())?;
        ctx.globals().set("pod", pod)?;
        Ok(())
    })?;
    let eval = Instant::now();
    guest.eval("entry", &source)?;
    let eval_ms = eval.elapsed().as_secs_f64() * 1000.0;
    let mut max_turn = 0.0_f64;
    let mut turns = 0;
    let mut cpu_total = 0.0;
    let mut cpu_before_body = 0.0;
    let mut body_seen = false;
    loop {
        ensure!(began.elapsed().as_secs() < 90, "entry probe exceeded 90s");
        let start = Instant::now();
        guest.drain_jobs();
        let cpu = start.elapsed().as_secs_f64() * 1000.0;
        max_turn = max_turn.max(cpu);
        cpu_total += cpu;
        if !body_seen {
            cpu_before_body += cpu;
        }
        body_seen = guest
            .with(|ctx| ctx.globals().get::<_, bool>("__entryHasBody"))
            .unwrap_or(false);
        let pumped = guest.with(|ctx| {
            ctx.eval::<bool, _>("__benchPump()")
                .catch(&ctx)
                .map_err(|e| anyhow::anyhow!("{e:?}"))
        })?;
        if !pumped {
            break;
        }
        turns += 1;
        surface.tick();
    }
    println!(
        "{}",
        serde_json::json!({"phase":"host.summary","evalMs":eval_ms,"turns":turns,"maxMicrotaskTurnMs":max_turn,"microtaskCpuMs":cpu_total,"microtaskCpuBeforeBodyMs":cpu_before_body})
    );
    ensure!(
        guest
            .with(|ctx| ctx.globals().get::<_, bool>("__benchDone"))
            .unwrap_or(false),
        "probe did not complete"
    );
    Ok(())
}
