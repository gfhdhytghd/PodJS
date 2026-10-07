//! Bounded guest codecs. No retained guest buffers, I/O, or business dialects.
use base64::{
    alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig},
    Engine as _,
};
use rquickjs::{Ctx, Function, Object, String as JsString, TypedArray};

pub const MAX_UTF8_SOURCE_BYTES: usize = 16384 * 3;
pub const MAX_BASE64_CHARS: usize = 16384;
pub const MAX_BASE64_BYTES: usize = 12288;

pub fn encode_utf8<'js>(
    ctx: Ctx<'js>,
    input: JsString<'js>,
    replacement: String,
) -> rquickjs::Result<Option<TypedArray<'js, u8>>> {
    if !matches!(replacement.as_str(), "strict" | "question" | "replacement") {
        return Ok(None);
    }
    // QuickJS exports WTF-8 for lone surrogates; Rust String conversion would
    // reject those before we could preserve the caller's replacement policy.
    let string = input.to_cstring()?;
    if string.len() > MAX_UTF8_SOURCE_BYTES {
        return Ok(None);
    }
    // SAFETY: CString owns this pointer until the end of the function. Read its
    // bytes rather than as_str(), because WTF-8 is not a valid Rust str.
    let bytes = unsafe { std::slice::from_raw_parts(string.as_ptr().cast::<u8>(), string.len()) };
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == 0xed && i + 2 < bytes.len() && (0xa0..=0xbf).contains(&bytes[i + 1]) {
            match replacement.as_str() {
                "strict" => return Ok(None),
                "question" => out.push(b'?'),
                _ => out.extend_from_slice(&[0xef, 0xbf, 0xbd]),
            }
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    TypedArray::new(ctx, out).map(Some)
}

pub fn encode_base64(input: TypedArray<'_, u8>) -> Option<String> {
    let bytes = input.as_bytes()?;
    if bytes.len() > MAX_BASE64_BYTES {
        return None;
    }
    Some(base64::engine::general_purpose::STANDARD.encode(bytes))
}

pub fn decode_base64<'js>(
    ctx: Ctx<'js>,
    input: String,
) -> rquickjs::Result<Option<TypedArray<'js, u8>>> {
    if input.len() > MAX_BASE64_CHARS {
        return Ok(None);
    }
    // Basic Base64 permits omitted padding and nonzero unused trailing bits,
    // but never whitespace, partial padding or a trailing one-symbol group.
    let raw = input.trim_end_matches('=');
    let padding = input.len() - raw.len();
    let tail = raw.len() % 4;
    if tail == 1 || (padding != 0 && !matches!((tail, padding), (2, 2) | (3, 1))) {
        return Ok(None);
    }
    let engine = GeneralPurpose::new(
        &alphabet::STANDARD,
        GeneralPurposeConfig::new()
            .with_decode_padding_mode(DecodePaddingMode::Indifferent)
            .with_decode_allow_trailing_bits(true),
    );
    match engine.decode(input) {
        Ok(bytes) => TypedArray::new(ctx, bytes).map(Some),
        Err(_) => Ok(None),
    }
}

pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>) -> rquickjs::Result<()> {
    pod.set("encodeUtf8Chunk", Function::new(ctx.clone(), encode_utf8)?)?;
    pod.set(
        "encodeBase64Chunk",
        Function::new(ctx.clone(), encode_base64)?,
    )?;
    pod.set("decodeBase64Chunk", Function::new(ctx, decode_base64)?)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rquickjs::{CatchResultExt, Context, Runtime};
    #[test]
    fn real_guest_bridge_preserves_surrogates_padding_views_and_bounds() {
        let rt = Runtime::new().unwrap();
        let context = Context::full(&rt).unwrap();
        context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();
            let result: bool=ctx.eval(r#"
              const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
              const bytes=(s,p)=>Array.from(pod.encodeUtf8Chunk(s,p));
              if(!same(bytes('中🙂\0','strict'),[228,184,173,240,159,153,130,0]))throw Error('unicode');
              if(!same(bytes('\ud800x\udc00','question'),[63,120,63]))throw Error('question');
              if(!same(bytes('\ud800','replacement'),[239,191,189]))throw Error('replacement');
              if(pod.encodeUtf8Chunk('\ud800','strict')!=null)throw Error('strict');
              if(pod.encodeUtf8Chunk('中'.repeat(16385),'strict')!=null)throw Error('utf8 bound');
              const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
              for(let i=0;i<64;i++)for(let j=0;j<64;j++)for(const suffix of ['', '==']){
                const got=pod.decodeBase64Chunk(alphabet[i]+alphabet[j]+suffix);
                if(!same(Array.from(got),[(i<<2)|(j>>4)]))throw Error('trailing bits');
              }
              for(const input of ['A','A=','A===','AA=','AAA==','AAAA=','AAAA==',' AA==','AA==\n','AA===','AA==AAAA','☃','-w==','_w=='])if(pod.decodeBase64Chunk(input)!=null)throw Error('invalid '+input);
              if(pod.encodeBase64Chunk(new Uint8Array(12289))!=null)throw Error('encode bound');
              if(pod.decodeBase64Chunk('A'.repeat(16388))!=null)throw Error('decode bound');
              if(pod.encodeBase64Chunk(new Uint8Array([9,0,255,1,8]).subarray(1,4))!=='AP8B')throw Error('offset');
              if(pod.encodeBase64Chunk(new Uint8Array())!=='')throw Error('empty');
              true;
            "#).catch(&ctx).unwrap();assert!(result);
        });
    }
}
