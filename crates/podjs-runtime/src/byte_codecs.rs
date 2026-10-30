//! Bounded guest codecs. No retained guest buffers, I/O, or business dialects.
use base64::{
    alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig},
    Engine as _,
};
use sha2::{Digest, Sha256};
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

/// Pure bounded hashing; no service queue, retained inputs or file access.
pub fn sha256_bytes<'js>(ctx: Ctx<'js>, input: TypedArray<'js, u8>) -> rquickjs::Result<Option<TypedArray<'js, u8>>> {
    let Some(bytes) = input.as_bytes() else { return Ok(None); };
    if bytes.len() > 256 * 1024 { return Ok(None); }
    TypedArray::new(ctx, Sha256::digest(bytes).to_vec()).map(Some)
}

/// Incremental IEEE CRC32, bounded to one guest view and no retained buffers.
pub fn crc32_bytes(input: TypedArray<'_, u8>, initial: f64) -> Option<f64> {
    let bytes = input.as_bytes()?;
    if bytes.len() > 256 * 1024 || !initial.is_finite() || initial.fract() != 0.0
        || initial < 0.0 || initial > u32::MAX as f64 { return None; }
    let mut hash = crc32fast::Hasher::new_with_initial(initial as u32);
    hash.update(bytes);
    // rquickjs' u32 IntoJs uses an int32 value. IEEE CRC32 needs the unsigned
    // range, so publish an exactly representable JS Number instead.
    Some(hash.finalize() as f64)
}

/// Optional bounded gzip decompression. Oversized/invalid input uses the caller's fallback.
pub fn gunzip_bytes<'js>(ctx: Ctx<'js>, input: TypedArray<'js, u8>) -> rquickjs::Result<Option<TypedArray<'js, u8>>> {
    use std::io::Read;
    let Some(bytes)=input.as_bytes() else { return Ok(None); };
    if bytes.len()>256*1024 { return Ok(None); }
    let decoder=flate2::read::MultiGzDecoder::new(bytes);
    let mut output=Vec::new();
    if decoder.take(256*1024+1).read_to_end(&mut output).is_err() || output.len()>256*1024 { return Ok(None); }
    TypedArray::new(ctx, output).map(Some)
}

/// Android JSONObject quoted UTF-8 size, without allocating an escaped copy.
/// WTF-8 lone surrogates use Java's ASCII '?' replacement. No retained guest data.
pub fn android_json_quoted_byte_length(input: JsString<'_>) -> rquickjs::Result<Option<usize>> {
    let string = input.to_cstring()?;
    if string.len() > 256 * 1024 { return Ok(None); }
    let bytes = unsafe { std::slice::from_raw_parts(string.as_ptr().cast::<u8>(), string.len()) };
    let mut size = 2; let mut i = 0;
    while i < bytes.len() {
        let byte = bytes[i];
        if byte == 0xed && i + 2 < bytes.len() && (0xa0..=0xbf).contains(&bytes[i + 1]) {
            size += 1; i += 3; continue;
        }
        size += match byte { b'"' | b'\\' | b'/' | 8 | 12 | 10 | 13 | 9 => 2, 0..=31 => 6, _ => 1 };
        i += 1;
    }
    Ok(Some(size))
}

/// Strict bounded UTF-8 for headless guests. Invalid byte sequences retain
/// the caller's legacy fallback; no service, filesystem or UI access.
pub fn decode_utf8_bytes(input: TypedArray<'_, u8>) -> Option<String> {
    let bytes=input.as_bytes()?;
    if bytes.len()>256*1024 { return None; }
    std::str::from_utf8(bytes).ok().map(str::to_owned)
}

pub fn mount<'js>(ctx: Ctx<'js>, pod: &Object<'js>) -> rquickjs::Result<()> {
    pod.set("decodeUtf8", Function::new(ctx.clone(), decode_utf8_bytes)?)?;
    pod.set("crc32Bytes", Function::new(ctx.clone(), crc32_bytes)?)?;
    pod.set("gunzipBytes", Function::new(ctx.clone(), gunzip_bytes)?)?;
    pod.set("androidJsonQuotedByteLength", Function::new(ctx.clone(), android_json_quoted_byte_length)?)?;
    pod.set("sha256Bytes", Function::new(ctx.clone(), sha256_bytes)?)?;
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
    fn headless_utf8_is_strict_bounded_and_preserves_views_bom_nul() {
        let rt=Runtime::new().unwrap();let context=Context::full(&rt).unwrap();context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("__podByteCodecs",pod).unwrap();
            let ok:bool=ctx.eval(r#"typeof pod==='undefined' && __podByteCodecs.decodeUtf8(new Uint8Array([9,239,187,191,65,0,66,9]).subarray(1,7))==='\ufeffA\0B' && __podByteCodecs.decodeUtf8(new Uint8Array([255]))==null && __podByteCodecs.decodeUtf8(new Uint8Array([240,159,153]))==null && __podByteCodecs.decodeUtf8(new Uint8Array(256*1024)).length===256*1024 && __podByteCodecs.decodeUtf8(new Uint8Array(256*1024+1))==null"#).unwrap();assert!(ok);
        });
    }
    #[test]
    fn crc32_bridge_preserves_views_incremental_seed_and_bounds() {
        let rt=Runtime::new().unwrap();let context=Context::full(&rt).unwrap();
        context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();
            let ok:bool=ctx.eval(r#"
              const b=new Uint8Array([0,49,50,51,52,53,54,55,56,57,0]).subarray(1,10);
              const first=pod.crc32Bytes(b.subarray(0,4),0);
              pod.crc32Bytes(b,0)===0xcbf43926 && pod.crc32Bytes(b.subarray(4),first)===0xcbf43926 &&
              pod.crc32Bytes(new Uint8Array(),0xffffffff)===0xffffffff &&
              pod.crc32Bytes(new Uint8Array(256*1024),0)!=null && pod.crc32Bytes(new Uint8Array(256*1024+1),0)==null &&
              [-1,1.5,NaN,Infinity,4294967296].every(seed=>pod.crc32Bytes(b,seed)==null);
            "#).unwrap();assert!(ok);
        });
    }
    #[test]
    fn crc32_large_guest_stream_has_a_bounded_native_time_budget() {
        let rt=Runtime::new().unwrap();let context=Context::full(&rt).unwrap();
        let bytes:Vec<u8>=(0..32*1024*1024).map(|n|(n*31) as u8).collect();
        let expected=crc32fast::hash(&bytes);
        context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();
            ctx.globals().set("input",TypedArray::new(ctx.clone(),bytes).unwrap()).unwrap();
            let started=std::time::Instant::now();
            let got:u32=ctx.eval("let crc=0;for(let at=0;at<input.length;at+=256*1024)crc=pod.crc32Bytes(input.subarray(at,at+256*1024),crc);crc").unwrap();
            let elapsed=started.elapsed();assert_eq!(got,expected);
            assert!(elapsed<std::time::Duration::from_secs(5),"32 MiB guest CRC native budget exceeded: {elapsed:?}");
            eprintln!("32 MiB guest CRC /128 bounded native calls: {elapsed:?}");
            let old_started=std::time::Instant::now();
            let old:u32=ctx.eval("(function oldBitwiseCrc(bytes){let crc=0xffffffff;for(const b of bytes){crc^=b;for(let i=0;i<8;i++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}return (crc^0xffffffff)>>>0;})(input.subarray(0,1024*1024))").unwrap();
            assert_eq!(old,crc32fast::hash(&(0..1024*1024).map(|n|(n*31) as u8).collect::<Vec<_>>()));
            eprintln!("old bitwise CRC, 1 MiB real guest: {:?}",old_started.elapsed());
        });
    }
    #[test]
    fn gunzip_bridge_validates_crc_views_members_and_output_bound() {
        use std::io::Write;
        fn zip(bytes:&[u8])->Vec<u8>{let mut writer=flate2::write::GzEncoder::new(Vec::new(),flate2::Compression::default());writer.write_all(bytes).unwrap();writer.finish().unwrap()}
        let rt=Runtime::new().unwrap();let context=Context::full(&rt).unwrap();
        context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();
            let data=zip("中文🙂".as_bytes());let mut view=vec![9];view.extend_from_slice(&data);view.push(8);ctx.globals().set("input",TypedArray::new(ctx.clone(),view).unwrap()).unwrap();
            let actual:Vec<u8>=ctx.eval("Array.from(pod.gunzipBytes(input.subarray(1,input.length-1)))").unwrap();assert_eq!(actual,"中文🙂".as_bytes());
            let mut joined=zip(b"one");joined.extend(zip(b"two"));ctx.globals().set("joined",TypedArray::new(ctx.clone(),joined).unwrap()).unwrap();assert_eq!(ctx.eval::<Vec<u8>,_>("Array.from(pod.gunzipBytes(joined))").unwrap(),b"onetwo");
            let mut corrupt=data;let n=corrupt.len();corrupt[n-8]^=1;ctx.globals().set("bad",TypedArray::new(ctx.clone(),corrupt).unwrap()).unwrap();assert!(ctx.eval::<bool,_>("pod.gunzipBytes(bad)==null").unwrap());
            for size in [256*1024,256*1024+1]{ctx.globals().set("large",TypedArray::new(ctx.clone(),zip(&vec![b'a';size])).unwrap()).unwrap();let ok=ctx.eval::<bool,_>(if size==256*1024 {"pod.gunzipBytes(large).length===256*1024"}else{"pod.gunzipBytes(large)==null"}).unwrap();assert!(ok);}
            assert!(ctx.eval::<bool,_>("pod.gunzipBytes(new Uint8Array(256*1024+1))==null").unwrap());
        });
    }
    #[test]
    fn quoted_byte_lengths_match_utf16_reference_and_bounds() {
        let rt=Runtime::new().unwrap();let context=Context::full(&rt).unwrap();
        context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();
            let ok:bool=ctx.eval(r#"
            function reference(s){let n=2;for(let i=0;i<s.length;i++){const c=s.charCodeAt(i);if([34,92,47,8,12,10,13,9].includes(c))n+=2;else if(c<32)n+=6;else if(c>=0xd800&&c<=0xdbff){const l=s.charCodeAt(i+1);if(l>=0xdc00&&l<=0xdfff){n+=4;i++;}else n++;}else if(c>=0xdc00&&c<=0xdfff)n++;else n+=c<128?1:c<2048?2:3;}return n;}
            let state=39;const samples=['','/','中文🙂','\u0000\u0001\b\f\n\r\t','\ud800','\udc00','\ud800🙂','x\ud800x\udc00','abc/中🙂'.repeat(1000)];
            for(let i=0;i<200;i++){let s='';for(let j=0;j<100;j++){state=(Math.imul(state,1664525)+1013904223)>>>0;s+=String.fromCharCode(state&65535);}samples.push(s);}
            samples.every(s=>pod.androidJsonQuotedByteLength(s)===reference(s))&&pod.androidJsonQuotedByteLength('a'.repeat(256*1024))===256*1024+2&&pod.androidJsonQuotedByteLength('a'.repeat(256*1024+1))==null;
            "#).unwrap();assert!(ok);
        });
    }
    #[test]
    fn sha256_bridge_matches_standard_vectors_views_and_limits() {
        let rt = Runtime::new().unwrap(); let context = Context::full(&rt).unwrap();
        context.with(|ctx| {
            let pod=Object::new(ctx.clone()).unwrap();mount(ctx.clone(),&pod).unwrap();ctx.globals().set("pod",pod).unwrap();
            let actual: String=ctx.eval(r#"const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
                hex(pod.sha256Bytes(new Uint8Array([9,97,98,99,8]).subarray(1,4)))"#).unwrap();
            assert_eq!(actual,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            let empty:String=ctx.eval("hex(pod.sha256Bytes(new Uint8Array()))").unwrap();
            assert_eq!(empty,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            let bounded:bool=ctx.eval("pod.sha256Bytes(new Uint8Array(256*1024)).length===32 && pod.sha256Bytes(new Uint8Array(256*1024+1))==null").unwrap();assert!(bounded);
        });
    }
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
