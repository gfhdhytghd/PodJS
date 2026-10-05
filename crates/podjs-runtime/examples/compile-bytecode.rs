use anyhow::{Context as _, ensure};
use podjs_runtime::{QUICKJS_BYTECODE_FORMAT, QUICKJS_BYTECODE_VERSION};
use rquickjs::{Context, Runtime, qjs as raw};
use std::ffi::CString;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args_os().skip(1);
    let source_path = args.next().context("usage: compile-bytecode <main.js> <main.qbc>")?;
    let output_path = args.next().context("usage: compile-bytecode <main.js> <main.qbc>")?;
    let mode = args.next();
    let strip_source = mode.as_deref() == Some(std::ffi::OsStr::new("--strip-source"));
    ensure!(mode.is_none() || strip_source, "optional mode must be --strip-source");
    ensure!(args.next().is_none(), "usage: compile-bytecode <main.js> <main.qbc> [--strip-source]");
    ensure!(cfg!(target_endian = "little"), "PodJS QuickJS bytecode currently requires a little-endian build host");

    let source = std::fs::read(&source_path).context("read JavaScript bundle")?;
    let bytecode = compile(&source, strip_source)?;
    std::fs::write(&output_path, &bytecode).context("write QuickJS bytecode")?;
    println!("PodJS bytecode: {} bytes, format={QUICKJS_BYTECODE_FORMAT}, source={}, debug=true", bytecode.len(), !strip_source);
    Ok(())
}

// Only source strings are omitted. Filename, pc-to-line/column maps, names,
// locals and closure metadata are retained. Function.toString then uses the
// engine's native-code form; projects must explicitly opt in.
fn compile(source: &[u8], strip_source: bool) -> anyhow::Result<Vec<u8>> {
    let source_c = CString::new(source).context("JavaScript bundle contains NUL")?;
    let runtime = Runtime::new().context("create QuickJS runtime")?;
    let context = Context::full(&runtime).context("create QuickJS context")?;
    context.with(|ctx| -> anyhow::Result<Vec<u8>> {
        let compiled = unsafe { raw::JS_Eval(
            ctx.as_raw().as_ptr(), source_c.as_ptr(), source.len() as _, c"app:///main.js".as_ptr(),
            (raw::JS_EVAL_TYPE_GLOBAL | raw::JS_EVAL_FLAG_STRICT | raw::JS_EVAL_FLAG_COMPILE_ONLY) as i32,
        ) };
        if unsafe { raw::JS_IsException(compiled) } {
            anyhow::bail!("QuickJS compile failed: {:?}", ctx.catch());
        }
        let mut length = 0;
        let bytes = unsafe { raw::JS_WriteObject(
            ctx.as_raw().as_ptr(), &mut length, compiled, (raw::JS_WRITE_OBJ_BYTECODE | if strip_source { raw::JS_WRITE_OBJ_STRIP_SOURCE } else { 0 }) as i32,
        ) };
        unsafe { raw::JS_FreeValue(ctx.as_raw().as_ptr(), compiled); }
        ensure!(!bytes.is_null() && length > 5, "QuickJS bytecode serialization failed");
        let output = unsafe { std::slice::from_raw_parts(bytes, length as usize) }.to_vec();
        unsafe { raw::js_free(ctx.as_raw().as_ptr(), bytes.cast()); }
        ensure!(output[0] == QUICKJS_BYTECODE_VERSION as u8,
            "QuickJS bytecode version drift: emitted {}, expected {}", output[0], QUICKJS_BYTECODE_VERSION);
        Ok(output)
    })

}

#[cfg(test)]
mod tests {
    use super::*;

    fn evaluate(bytes: &[u8]) -> (String, String, String) {
        let runtime = Runtime::new().unwrap();
        let context = Context::full(&runtime).unwrap();
        context.with(|ctx| {
            let compiled = unsafe { raw::JS_ReadObject(ctx.as_raw().as_ptr(), bytes.as_ptr(), bytes.len() as _, raw::JS_READ_OBJ_BYTECODE as i32) };
            assert!(!unsafe { raw::JS_IsException(compiled) }, "{:?}", ctx.catch());
            let result = unsafe { raw::JS_EvalFunction(ctx.as_raw().as_ptr(), compiled) };
            assert!(!unsafe { raw::JS_IsException(result) }, "{:?}", ctx.catch());
            unsafe { raw::JS_FreeValue(ctx.as_raw().as_ptr(), result); }
            (ctx.globals().get("answer").unwrap(), ctx.globals().get("stack").unwrap(), ctx.globals().get("text").unwrap())
        })
    }

    #[test]
    fn stripped_source_preserves_execution_eval_closures_and_error_locations() {
        let source = br#"function outer(a) {
    return function inner(b) { return eval('a + b'); };
}
function fail() { throw new Error('probe'); }
globalThis.answer = String(outer(20)(22));
try { fail(); } catch(e) { globalThis.stack = e.stack; }
globalThis.text = outer.toString();"#;
        let full = compile(source, false).unwrap();
        let compact = compile(source, true).unwrap();
        assert!(compact.len() < full.len());
        let full_result = evaluate(&full);
        let compact_result = evaluate(&compact);
        assert_eq!(compact_result.0, "42");
        assert_eq!(full_result.0, compact_result.0);
        assert_eq!(full_result.1, compact_result.1);
        assert!(compact_result.1.contains("app:///main.js:4:"), "{}", compact_result.1);
        assert!(full_result.2.contains("return function inner"));
        assert!(compact_result.2.contains("[native code]"));
    }
}
