use anyhow::{Context as _, ensure};
use podjs_runtime::{QUICKJS_BYTECODE_FORMAT, QUICKJS_BYTECODE_VERSION};
use rquickjs::{Context, Runtime, qjs as raw};
use std::ffi::CString;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args_os().skip(1);
    let source_path = args.next().context("usage: compile-bytecode <main.js> <main.qbc>")?;
    let output_path = args.next().context("usage: compile-bytecode <main.js> <main.qbc>")?;
    ensure!(args.next().is_none(), "usage: compile-bytecode <main.js> <main.qbc>");
    ensure!(cfg!(target_endian = "little"), "PodJS QuickJS bytecode currently requires a little-endian build host");

    let source = std::fs::read(&source_path).context("read JavaScript bundle")?;
    let source_c = CString::new(source.clone()).context("JavaScript bundle contains NUL")?;
    let runtime = Runtime::new().context("create QuickJS runtime")?;
    let context = Context::full(&runtime).context("create QuickJS context")?;
    let bytecode = context.with(|ctx| -> anyhow::Result<Vec<u8>> {
        let compiled = unsafe { raw::JS_Eval(
            ctx.as_raw().as_ptr(), source_c.as_ptr(), source.len() as _, c"app:///main.js".as_ptr(),
            (raw::JS_EVAL_TYPE_GLOBAL | raw::JS_EVAL_FLAG_STRICT | raw::JS_EVAL_FLAG_COMPILE_ONLY) as i32,
        ) };
        if unsafe { raw::JS_IsException(compiled) } {
            anyhow::bail!("QuickJS compile failed: {:?}", ctx.catch());
        }
        let mut length = 0;
        let bytes = unsafe { raw::JS_WriteObject(
            ctx.as_raw().as_ptr(), &mut length, compiled, raw::JS_WRITE_OBJ_BYTECODE as i32,
        ) };
        unsafe { raw::JS_FreeValue(ctx.as_raw().as_ptr(), compiled); }
        ensure!(!bytes.is_null() && length > 5, "QuickJS bytecode serialization failed");
        let output = unsafe { std::slice::from_raw_parts(bytes, length as usize) }.to_vec();
        unsafe { raw::js_free(ctx.as_raw().as_ptr(), bytes.cast()); }
        ensure!(output[0] == QUICKJS_BYTECODE_VERSION as u8,
            "QuickJS bytecode version drift: emitted {}, expected {}", output[0], QUICKJS_BYTECODE_VERSION);
        Ok(output)
    })?;
    std::fs::write(&output_path, &bytecode).context("write QuickJS bytecode")?;
    println!("PodJS bytecode: {} bytes, format={QUICKJS_BYTECODE_FORMAT}", bytecode.len());
    Ok(())
}
