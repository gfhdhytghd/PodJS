//! Streaming, bounded JSON projection for application-owned files.
use serde::de::{self, DeserializeSeed, IgnoredAny, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use serde_json::{Value, value::RawValue};
use std::cell::Cell;
use std::fmt;
use std::fs::File;
use std::io::{self, BufRead, BufReader, Read, Seek, Write};

const INPUT_LIMIT: usize = 8 * 1024 * 1024;
const OUTPUT_LIMIT: usize = 256 * 1024;
const KEY_LIMIT: usize = 1024;
const SPEC_LIMIT: usize = 16 * 1024;

#[derive(Default)]
struct Bounds {
    offset: Cell<usize>,
    capture_end: Cell<Option<usize>>,
    exceeded: Cell<bool>,
    spool_io: Cell<bool>,
}

impl Bounds {
    fn start_capture(&self, maximum: usize) {
        // The deserializer may already hold the first byte or peek at a delimiter.
        self.capture_end.set(Some(self.offset.get() + maximum + 2));
    }

    fn end_capture(&self) {
        self.capture_end.set(None);
    }

    fn push<E: de::Error>(&self, output: &mut String, text: &str) -> Result<(), E> {
        if text.len() > OUTPUT_LIMIT - output.len() {
            self.exceeded.set(true);
            return Err(E::custom("projection limit"));
        }
        output.push_str(text);
        Ok(())
    }
}

struct Metered<'a, R> {
    input: R,
    bounds: &'a Bounds,
    utf8: Utf8,
}

#[derive(Default)]
struct Utf8 {
    pending: u8,
    next: Option<(u8, u8)>,
}

impl Utf8 {
    fn validate(&mut self, bytes: &[u8]) -> bool {
        for &byte in bytes {
            if self.pending != 0 {
                let (lo, hi) = self.next.take().unwrap_or((0x80, 0xbf));
                if !(lo..=hi).contains(&byte) {
                    return false;
                }
                self.pending -= 1;
                continue;
            }
            let (pending, lo, hi) = match byte {
                0..=0x7f => continue,
                0xc2..=0xdf => (1, 0x80, 0xbf),
                0xe0 => (2, 0xa0, 0xbf),
                0xed => (2, 0x80, 0x9f),
                0xe1..=0xef => (2, 0x80, 0xbf),
                0xf0 => (3, 0x90, 0xbf),
                0xf1..=0xf3 => (3, 0x80, 0xbf),
                0xf4 => (3, 0x80, 0x8f),
                _ => return false,
            };
            self.pending = pending;
            self.next = Some((lo, hi));
        }
        true
    }
}

impl<R: Read> Read for Metered<'_, R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let at = self.bounds.offset.get();
        let n = self.input.read(buf)?;
        if n > INPUT_LIMIT.saturating_sub(at)
            || self
                .bounds
                .capture_end
                .get()
                .is_some_and(|end| n > end.saturating_sub(at))
        {
            self.bounds.exceeded.set(true);
            return Err(io::Error::other("projection limit"));
        }
        self.bounds.offset.set(at + n);
        if !self.utf8.validate(&buf[..n]) || n == 0 && self.utf8.pending != 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid UTF-8"));
        }
        Ok(n)
    }
}

struct Select<'a> {
    spec: &'a Value,
    out: &'a mut String,
    bounds: &'a Bounds,
    spool: Option<&'a mut RowSpool>,
    row_array: bool,
}

struct RowSpool {
    field: String,
    file: File,
    bytes: usize,
}

impl RowSpool {
    fn reset(&mut self) -> io::Result<()> {
        self.file.set_len(0)?;
        self.file.rewind()?;
        self.bytes = 0;
        Ok(())
    }

    fn line<E: de::Error>(&mut self, row: &mut String, bounds: &Bounds) -> Result<(), E> {
        // Actual CR/LF in a valid JSON value can only be whitespace.
        // Escaped line breaks in strings are left intact.
        row.retain(|c| c != '\r' && c != '\n');
        let size = row.len() + 1;
        if size > INPUT_LIMIT - self.bytes {
            bounds.exceeded.set(true);
            return Err(E::custom("projection limit"));
        }
        self.file
            .write_all(row.as_bytes())
            .and_then(|()| self.file.write_all(b"\n"))
            .map_err(|error| {
                bounds.spool_io.set(true);
                E::custom(error)
            })?;
        self.bytes += size;
        Ok(())
    }
}

impl<'de> DeserializeSeed<'de> for Select<'_> {
    type Value = ();

    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        self.bounds.start_capture(OUTPUT_LIMIT - self.out.len());
        if self.spec.is_null() {
            let raw = Box::<RawValue>::deserialize(d);
            self.bounds.end_capture();
            return self.bounds.push(self.out, raw?.get());
        }
        let bounds = self.bounds;
        let result = d.deserialize_any(self);
        bounds.end_capture();
        result
    }
}

impl Select<'_> {
    fn push<E: de::Error>(&mut self, text: &str) -> Result<(), E> {
        self.bounds.push(self.out, text)
    }
}

impl<'de> Visitor<'de> for Select<'_> {
    type Value = ();

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a JSON value")
    }

    fn visit_map<M: MapAccess<'de>>(mut self, mut map: M) -> Result<(), M::Error> {
        self.bounds.end_capture();
        let Some(spec) = self.spec.as_object() else {
            while map.next_entry::<IgnoredAny, IgnoredAny>()?.is_some() {}
            return self.push("null");
        };
        self.push("{")?;
        let mut first = true;
        loop {
            self.bounds.start_capture(KEY_LIMIT);
            let key = map.next_key::<String>();
            self.bounds.end_capture();
            let Some(key) = key? else { break };
            if let Some(child) = spec.get(&key) {
                if !first {
                    self.push(",")?;
                }
                first = false;
                let escaped = serde_json::to_string(&key).map_err(de::Error::custom)?;
                self.push(&escaped)?;
                self.push(":")?;
                let row_target = self.spool.as_deref_mut().filter(|spool| spool.field == key);
                let row_array = row_target.is_some();
                let mut child = Select {
                    spec: child,
                    out: self.out,
                    bounds: self.bounds,
                    spool: row_target,
                    row_array,
                };
                if let Some(spool) = child.spool.as_mut() {
                    spool.reset().map_err(|error| {
                        self.bounds.spool_io.set(true);
                        de::Error::custom(error)
                    })?;
                }
                map.next_value_seed(child)?;
            } else {
                map.next_value::<IgnoredAny>()?;
            }
        }
        self.push("}")
    }

    fn visit_seq<S: SeqAccess<'de>>(mut self, mut seq: S) -> Result<(), S::Error> {
        self.bounds.end_capture();
        let Some(child) = self.spec.as_array().and_then(|a| a.first()) else {
            while seq.next_element::<IgnoredAny>()?.is_some() {}
            return self.push("null");
        };
        if self.row_array {
            let spool = self
                .spool
                .as_deref_mut()
                .ok_or_else(|| de::Error::custom("projection rows"))?;
            let mut row = String::new();
            while seq
                .next_element_seed(Select {
                    spec: child,
                    out: &mut row,
                    bounds: self.bounds,
                    spool: None,
                    row_array: false,
                })?
                .is_some()
            {
                spool.line(&mut row, self.bounds)?;
                row.clear();
            }
            return self.push("[]");
        }
        self.push("[")?;
        let mut first = true;
        loop {
            let start = self.out.len();
            if !first {
                self.push(",")?;
            }
            if seq
                .next_element_seed(Select {
                    spec: child,
                    out: self.out,
                    bounds: self.bounds,
                    spool: None,
                    row_array: false,
                })?
                .is_none()
            {
                self.out.truncate(start);
                break;
            }
            first = false;
        }
        self.push("]")
    }

    fn visit_bool<E: de::Error>(mut self, value: bool) -> Result<(), E> {
        self.push(if value { "true" } else { "false" })
    }

    fn visit_unit<E: de::Error>(mut self) -> Result<(), E> {
        self.push("null")
    }
    fn visit_i64<E: de::Error>(mut self, value: i64) -> Result<(), E> {
        self.push(&value.to_string())
    }
    fn visit_u64<E: de::Error>(mut self, value: u64) -> Result<(), E> {
        self.push(&value.to_string())
    }
    fn visit_f64<E: de::Error>(mut self, value: f64) -> Result<(), E> {
        self.push(&value.to_string())
    }
    fn visit_str<E: de::Error>(mut self, value: &str) -> Result<(), E> {
        self.push(&serde_json::to_string(value).map_err(E::custom)?)
    }
}

fn valid_spec(value: &Value, depth: usize) -> bool {
    depth <= 32
        && match value {
            Value::Null => true,
            Value::Array(items) => items.len() == 1 && valid_spec(&items[0], depth + 1),
            Value::Object(fields) => fields
                .iter()
                .all(|(key, child)| key.len() <= KEY_LIMIT && valid_spec(child, depth + 1)),
            _ => false,
        }
}

fn skip_whitespace<R: BufRead>(reader: &mut R, bounds: &Bounds) -> io::Result<()> {
    loop {
        let chunk = reader.fill_buf()?;
        let n = chunk
            .iter()
            .take_while(|b| matches!(b, b' ' | b'\r' | b'\n' | b'\t'))
            .count();
        let done = n < chunk.len() || chunk.is_empty();
        let offset = bounds.offset.get() + n;
        if offset > INPUT_LIMIT {
            bounds.exceeded.set(true);
            return Err(io::Error::other("projection limit"));
        }
        bounds.offset.set(offset);
        reader.consume(n);
        if done {
            return Ok(());
        }
    }
}

/// The result is a projected JSON object or a closed classification. Small
/// non-JSON literals are matched against caller-owned tokens, never echoed.
pub fn project_file(input: impl Read, selection: &[u8], tokens: &[u8]) -> String {
    project(input, selection, tokens, None)
}

fn project(
    input: impl Read,
    selection: &[u8],
    tokens: &[u8],
    spool: Option<&mut RowSpool>,
) -> String {
    let Some(spec) = (selection.len() <= SPEC_LIMIT)
        .then(|| serde_json::from_slice::<Value>(selection).ok())
        .flatten()
        .filter(|spec| spec.is_object() && valid_spec(spec, 0))
    else {
        return "invalid_selection".into();
    };
    if spool
        .as_ref()
        .is_some_and(|spool| !spec.get(&spool.field).is_some_and(Value::is_array))
    {
        return "invalid_selection".into();
    }
    let Some(tokens) = (tokens.len() <= KEY_LIMIT)
        .then(|| serde_json::from_slice::<Vec<String>>(tokens).ok())
        .flatten()
        .filter(|tokens| {
            tokens.len() <= 8
                && tokens
                    .iter()
                    .all(|token| !token.is_empty() && token.len() <= 32)
        })
    else {
        return "invalid_selection".into();
    };
    let mut reader = BufReader::with_capacity(16 * 1024, input);
    let bounds = Bounds::default();
    if skip_whitespace(&mut reader, &bounds).is_err() {
        return if bounds.exceeded.get() { "limit" } else { "io" }.into();
    }
    match reader.fill_buf() {
        Ok([]) => return "empty".into(),
        Ok([b'{', ..]) => (),
        Ok(_) => {
            let no_padding = bounds.offset.get() == 0;
            let mut prefix = Vec::with_capacity(64);
            if reader.take(64).read_to_end(&mut prefix).is_err() {
                return "io".into();
            }
            if no_padding {
                if let Some(index) = tokens.iter().position(|token| token.as_bytes() == prefix) {
                    return format!("token:{index}");
                }
            }
            return if html_prefix(&prefix) {
                "html"
            } else {
                "invalid"
            }
            .into();
        }
        Err(_) => return "io".into(),
    }
    let mut out = String::new();
    let mut decoder = serde_json::Deserializer::from_reader(Metered {
        input: reader,
        bounds: &bounds,
        utf8: Utf8::default(),
    });
    let result = Select {
        spec: &spec,
        out: &mut out,
        bounds: &bounds,
        spool,
        row_array: false,
    }
    .deserialize(&mut decoder)
    .and_then(|()| decoder.end());
    match result {
        Ok(()) => out,
        Err(_) if bounds.exceeded.get() => "limit".into(),
        Err(err)
            if bounds.spool_io.get()
                || err.is_io() && err.io_error_kind() != Some(io::ErrorKind::InvalidData) =>
        {
            "io".into()
        }
        Err(_) => "invalid".into(),
    }
}

fn html_prefix(prefix: &[u8]) -> bool {
    fn whitespace(byte: u8) -> bool {
        matches!(byte, b' ' | b'\t' | b'\r' | b'\n' | 0x0c)
    }
    fn tag(input: &[u8], name: &[u8]) -> bool {
        input
            .get(..name.len())
            .is_some_and(|start| start.eq_ignore_ascii_case(name))
            && input
                .get(name.len())
                .is_none_or(|b| !(b.is_ascii_alphanumeric() || *b == b'_'))
    }
    if tag(prefix, b"<html") {
        return true;
    }
    let Some(rest) = prefix
        .get(9..)
        .filter(|_| prefix[..9].eq_ignore_ascii_case(b"<!doctype"))
    else {
        return false;
    };
    rest.first().is_some_and(|b| whitespace(*b)) && tag(rest.trim_ascii_start(), b"html")
}

/// # Safety
/// The fd is transferred to Rust, and both slices are readable for the specified lengths.
#[cfg(unix)]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_file_project_json(
    fd: std::os::fd::RawFd,
    spec: *const u8,
    spec_len: usize,
    tokens: *const u8,
    tokens_len: usize,
) -> *mut std::ffi::c_char {
    use std::os::fd::FromRawFd;
    let file = unsafe { std::fs::File::from_raw_fd(fd) };
    let result =
        if spec.is_null() || spec_len > SPEC_LIMIT || tokens.is_null() || tokens_len > KEY_LIMIT {
            "invalid_selection".into()
        } else {
            project_file(
                file,
                unsafe { std::slice::from_raw_parts(spec, spec_len) },
                unsafe { std::slice::from_raw_parts(tokens, tokens_len) },
            )
        };
    std::ffi::CString::new(result)
        .map(std::ffi::CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// Both fds are transferred to Rust. All arguments point to readable slices.
#[cfg(unix)]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_file_project_json_rows(
    fd: std::os::fd::RawFd,
    out_fd: std::os::fd::RawFd,
    spec: *const u8,
    spec_len: usize,
    tokens: *const u8,
    tokens_len: usize,
    field: *const u8,
    field_len: usize,
) -> *mut std::ffi::c_char {
    use std::os::fd::FromRawFd;
    let file = unsafe { File::from_raw_fd(fd) };
    let out = unsafe { File::from_raw_fd(out_fd) };
    let result = if spec.is_null()
        || spec_len > SPEC_LIMIT
        || tokens.is_null()
        || tokens_len > KEY_LIMIT
        || field.is_null()
        || field_len > KEY_LIMIT
    {
        "invalid_selection".into()
    } else if let Ok(field) =
        std::str::from_utf8(unsafe { std::slice::from_raw_parts(field, field_len) })
    {
        let mut spool = RowSpool {
            field: field.to_owned(),
            file: out,
            bytes: 0,
        };
        project(
            file,
            unsafe { std::slice::from_raw_parts(spec, spec_len) },
            unsafe { std::slice::from_raw_parts(tokens, tokens_len) },
            Some(&mut spool),
        )
    } else {
        "invalid_selection".into()
    };
    std::ffi::CString::new(result)
        .map(std::ffi::CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// Value is null or returned by pod_file_project_json, exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_file_project_json_free(value: *mut std::ffi::c_char) {
    if !value.is_null() {
        drop(unsafe { std::ffi::CString::from_raw(value) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(raw: &[u8], spec: &[u8]) -> String {
        project_file(raw, spec, br#"["blocked"]"#)
    }

    #[test]
    fn projects_nested_fields_without_owning_unselected_payload() {
        let raw = format!(
            r#"{{"discard":"{}","n":-0,"rows":[{{"a":"中🙂","b":123}},{{"a":"\ud800"}}],"dup":1,"dup":2}}"#,
            "x".repeat(2_100_000)
        );
        let got = run(
            raw.as_bytes(),
            br#"{"rows":[{"a":null}],"n":null,"dup":null}"#,
        );
        assert_eq!(
            got,
            r#"{"n":-0,"rows":[{"a":"中🙂"},{"a":"\ud800"}],"dup":1,"dup":2}"#
        );
    }

    #[test]
    fn refuses_unbounded_selection_and_corrupt_input() {
        let long = "x".repeat(OUTPUT_LIMIT);
        let selected = format!(r#"{{"x":"{long}"}}"#);
        assert_eq!(run(selected.as_bytes(), br#"{"x":null}"#), "limit");
        assert_eq!(run(br#"{"a":[1,2}"#, b"{}"), "invalid");
        assert_eq!(run(b"{\"a\":\"\xff\"}", b"{}"), "invalid");
        assert_eq!(run(b"{} null", b"{}"), "invalid");
        assert_eq!(run(b"{}", br#"{"a":[]}"#), "invalid_selection");
    }

    #[test]
    fn returns_only_closed_non_json_classes() {
        assert_eq!(run(b" \r\n", b"{}"), "empty");
        assert_eq!(run(b"blocked", b"{}"), "token:0");
        assert_eq!(run(b"blocked ", b"{}"), "invalid");
        assert_eq!(run(b" \n<!DoCtYpE HTML><p>hidden", b"{}"), "html");
        assert_eq!(run(b"<htmlx>", b"{}"), "invalid");
        assert_eq!(
            run(
                br#"{"obj":[],"rows":{},"nullable":null,"scalar":"old"}"#,
                br#"{"obj":{},"rows":[{}],"nullable":{},"scalar":{}}"#
            ),
            r#"{"obj":null,"rows":null,"nullable":null,"scalar":"old"}"#
        );
    }
}
