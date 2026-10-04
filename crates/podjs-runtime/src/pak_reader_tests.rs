use super::*;
use crate::*;

struct Source {
    bytes: Vec<u8>,
    max_read: usize,
    calls: usize,
    max_requested: usize,
    returned: usize,
    fail_call: usize,
    failure: i64,
}

impl Source {
    fn new(bytes: Vec<u8>) -> Self {
        Self { bytes, max_read: usize::MAX, calls: 0, max_requested: 0, returned: 0,
            fail_call: usize::MAX, failure: -1 }
    }
    fn reader(&mut self) -> PakReader {
        PakReader { length: self.bytes.len(), read_at, context: self as *mut Self as *mut c_void }
    }
    fn load(&mut self, runtime: *mut PodRuntime) -> i32 {
        pod_runtime_load_pak_reader(runtime, self.bytes.len(), Some(read_at), self as *mut Self as *mut c_void)
    }
}

unsafe extern "C" fn read_at(context: *mut c_void, offset: u64, out: *mut u8, length: usize) -> i64 {
    let source = unsafe { &mut *(context as *mut Source) };
    source.calls += 1;
    source.max_requested = source.max_requested.max(length);
    if source.calls == source.fail_call { return source.failure; }
    let Some(bytes) = source.bytes.get(offset as usize..) else { return -1; };
    let count = length.min(source.max_read).min(bytes.len());
    unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, count); }
    source.returned += count;
    count as i64
}

fn runtime() -> *mut PodRuntime {
    let target = CString::new("android-watch").unwrap();
    let caps = CString::new("[]").unwrap();
    let config = PodRuntimeConfig {
        struct_size: std::mem::size_of::<PodRuntimeConfig>() as u32,
        target_id: target.as_ptr(), host_abi: ABI_VERSION, raster_density: 1,
        physical_width: 240, physical_height: 240, display_density: 1.0, display_shape: 0,
        safe_top: 0.0, safe_right: 0.0, safe_bottom: 0.0, safe_left: 0.0,
        data_dir: std::ptr::null(), capabilities_json: caps.as_ptr(),
    };
    let result = pod_runtime_create(&config);
    assert!(!result.is_null());
    result
}

fn fixture() -> Vec<u8> {
    let entries: [(&[u8], &[u8]); 2] = [
        (b"ui:img.pixel", &[1, 0, 1, 0, 3, 0, 0, 0, 17, 31, 47, 255]),
        (b"custom-tile", b"lazy blob"),
    ];
    let names = 32 + entries.len() * 24;
    let mut pak = vec![0; names];
    pak[..4].copy_from_slice(&spec::pak::MAGIC.to_le_bytes());
    pak[4..6].copy_from_slice(&spec::pak::VERSION.to_le_bytes());
    pak[8..12].copy_from_slice(&(entries.len() as u32).to_le_bytes());
    pak[12..16].copy_from_slice(&32u32.to_le_bytes());
    pak[16..20].copy_from_slice(&(names as u32).to_le_bytes());
    for (index, (key, _)) in entries.iter().enumerate() {
        let entry = 32 + index * 24;
        let offset = (pak.len() - names) as u32;
        pak[entry + 12..entry + 16].copy_from_slice(&offset.to_le_bytes());
        pak[entry + 16..entry + 18].copy_from_slice(&(key.len() as u16).to_le_bytes());
        pak.extend_from_slice(key);
    }
    for (index, (_, blob)) in entries.iter().enumerate() {
        let entry = 32 + index * 24;
        let offset = pak.len() as u32;
        pak[entry + 4..entry + 8].copy_from_slice(&offset.to_le_bytes());
        pak[entry + 8..entry + 12].copy_from_slice(&(blob.len() as u32).to_le_bytes());
        pak.extend_from_slice(blob);
    }
    pak.extend_from_slice(b"ignored trailing bytes still belong to the checksum");
    pak
}

#[test]
fn checksum_retries_positive_short_reads_and_bounds_every_request() {
    let mut known = Source::new(b"foobar".to_vec());
    known.max_read = 1;
    assert_eq!(known.reader().checksum().unwrap(), 0x85944171f73967e8);
    let bytes: Vec<u8> = (0..READ_CHUNK * 3 + 19).map(|i| (i * 17) as u8).collect();
    let expected = hash_bytes(&bytes);
    let mut source = Source::new(bytes);
    source.max_read = 997;
    assert_eq!(source.reader().checksum().unwrap(), expected);
    assert_eq!(source.returned, source.bytes.len());
    assert_eq!(source.max_requested, READ_CHUNK);
}

#[test]
fn eof_negative_and_oversized_results_fail_without_retry_loops() {
    for (failure, kind) in [(0, ErrorKind::UnexpectedEof), (-1, ErrorKind::Other), (33, ErrorKind::InvalidData)] {
        let mut source = Source::new(vec![0; 32]);
        source.fail_call = 1;
        source.failure = failure;
        assert_eq!(source.reader().checksum().unwrap_err().kind(), kind);
        assert_eq!(source.calls, 1);
    }
}

#[test]
fn invalid_ranges_never_reach_the_callback() {
    let mut source = Source::new(vec![0; 32]);
    let mut reader = source.reader();
    for offset in [32, usize::MAX] {
        assert_eq!(reader.read_exact_at(offset, &mut [0; 1]).unwrap_err().kind(), ErrorKind::InvalidInput);
    }
    reader.read_exact_at(32, &mut []).unwrap();
    assert_eq!(source.calls, 0);
}

fn resources(runtime: *mut PodRuntime) -> Value {
    unsafe { &*runtime }.surface.with_ui(|ui| {
        let fonts: Vec<Value> = (0..=255u8).filter_map(|slot| ui.font_atlas(slot).map(|font| json!([
            slot, font.cell_w, font.cell_h, font.baseline, font.line_height,
            font.raster_density, font.flags, font.glyph_count, font.bitmap.len(), hash_bytes(&font.bitmap)
        ]))).collect();
        let textures: Vec<Value> = (0..ui.texture_slot_count()).filter_map(|slot| {
            ui.texture_at(slot as u32).map(|(handle, texture)| json!([
                handle, texture.w, texture.h, texture.psm, hash_bytes(texture.pixels)
            ]))
        }).collect();
        json!({"fonts": fonts, "textures": textures})
    })
}

#[test]
fn ffi_preserves_bad_pack_tolerance_and_imports_the_same_resources() {
    for bytes in [b"pak".to_vec(), fixture()] {
        let borrowed = runtime();
        let streamed = runtime();
        assert_eq!(pod_runtime_load_pak(borrowed, bytes.as_ptr(), bytes.len()), OK);
        let mut source = Source::new(bytes);
        source.max_read = 7;
        assert_eq!(source.load(streamed), OK);
        assert_eq!(unsafe { (*borrowed).pak_hash }, unsafe { (*streamed).pak_hash });
        assert_eq!(resources(borrowed), resources(streamed));
        if source.bytes.len() > 3 {
            assert_eq!(resources(streamed)["textures"].as_array().unwrap().len(), 1);
        }
        pod_runtime_destroy(borrowed);
        pod_runtime_destroy(streamed);
    }
}

#[test]
fn ffi_read_failure_revokes_previous_validation_and_checksum() {
    // Checksum, header, directory, name and blob failures are all fatal. None
    // may leave a previously validated package available for bundle execution.
    for fail_call in 1..=5 {
        let runtime = runtime();
        let mut source = Source::new(fixture());
        assert_eq!(source.load(runtime), OK);
        unsafe { (*runtime).package_validated = true; (*runtime).expected_bundle_hash = Some("old bundle".into()); }
        source.calls = 0;
        source.fail_call = fail_call;
        assert_eq!(source.load(runtime), ERR_STATE);
        assert_eq!(unsafe { (*runtime).pak_hash }, 0);
        assert!(!unsafe { (*runtime).package_validated });
        assert!(unsafe { &*runtime }.expected_bundle_hash.is_none());
        let message = unsafe { CStr::from_ptr(pod_runtime_last_error()) }.to_string_lossy();
        assert!(message.contains("pak source read failed at offset"), "{message}");
        assert_eq!(pod_runtime_validate_package(runtime, std::ptr::null()), ERR_STATE);
        pod_runtime_destroy(runtime);
    }
}

#[test]
fn ffi_rejects_missing_callback_and_mounted_runtime() {
    let runtime = runtime();
    assert_eq!(pod_runtime_load_pak_reader(runtime, 3, None, std::ptr::null_mut()), ERR_ARGUMENT);
    assert_eq!(pod_runtime_load_pak_reader(runtime, 0, Some(read_at), std::ptr::null_mut()), ERR_STATE);
    assert_eq!(pod_runtime_load_pak_reader(runtime, usize::MAX, Some(read_at), std::ptr::null_mut()), ERR_ARGUMENT);
    unsafe { (*runtime).mounted = true; }
    assert_eq!(Source::new(b"pak".to_vec()).load(runtime), ERR_STATE);
    pod_runtime_destroy(runtime);
}

#[test]
#[ignore = "set PODJS_TEST_PAK to an actual compiled pack"]
fn actual_pack_ffi_checksum_fonts_and_textures_match_borrowed_loader() {
    use std::io::{Read, Seek, SeekFrom};
    struct FileSource { file: std::fs::File, max_requested: usize }
    unsafe extern "C" fn file_read(context: *mut c_void, offset: u64, out: *mut u8, length: usize) -> i64 {
        let source = unsafe { &mut *(context as *mut FileSource) };
        source.max_requested = source.max_requested.max(length);
        let bytes = unsafe { std::slice::from_raw_parts_mut(out, length) };
        source.file.seek(SeekFrom::Start(offset)).and_then(|_| source.file.read(bytes)).map(|n| n as i64).unwrap_or(-1)
    }
    let path = std::env::var("PODJS_TEST_PAK").expect("PODJS_TEST_PAK");
    let bytes = std::fs::read(&path).unwrap();
    let borrowed = runtime();
    assert_eq!(pod_runtime_load_pak(borrowed, bytes.as_ptr(), bytes.len()), OK);
    let expected_hash = unsafe { (*borrowed).pak_hash };
    let expected = resources(borrowed);
    pod_runtime_destroy(borrowed);
    let length = bytes.len();
    drop(bytes);
    let streamed = runtime();
    let mut source = FileSource { file: std::fs::File::open(path).unwrap(), max_requested: 0 };
    assert_eq!(pod_runtime_load_pak_reader(streamed, length, Some(file_read), &mut source as *mut _ as *mut c_void), OK);
    assert_eq!(unsafe { (*streamed).pak_hash }, expected_hash);
    assert_eq!(resources(streamed), expected);
    assert!(source.max_requested <= READ_CHUNK);
    eprintln!("actual pack: {length} bytes; checksum={expected_hash:016x}; fonts={}; textures={}; max read={}",
        expected["fonts"].as_array().unwrap().len(), expected["textures"].as_array().unwrap().len(), source.max_requested);
    pod_runtime_destroy(streamed);
}
