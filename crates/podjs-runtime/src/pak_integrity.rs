use std::time::Instant;

use anyhow::{Context, Result, anyhow, bail};
use pocket_ui_surface::PakEntryIntegrity;
use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

pub const FORMAT: &str = "sha256-per-entry-v1";

const HEADER_SIZE: usize = pocketjs_core::spec::pak::HEADER_SIZE;
const ENTRY_SIZE: usize = pocketjs_core::spec::pak::ENTRY_SIZE;
const ALIGN: usize = pocketjs_core::spec::pak::ALIGN;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    format: String,
    directory_bytes: usize,
    directory_sha256: String,
    entries: Vec<Entry>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Entry {
    key: String,
    offset: usize,
    bytes: usize,
    sha256: String,
}

#[derive(Debug)]
pub struct Validated {
    pub entries: Vec<PakEntryIntegrity>,
    pub directory_parse_micros: u64,
    pub directory_hash_micros: u64,
}

fn u16_at(bytes: &[u8], offset: usize) -> Result<u16> {
    let value = bytes
        .get(offset..offset.checked_add(2).context("u16 offset overflow")?)
        .context("truncated u16")?;
    Ok(u16::from_le_bytes(
        value.try_into().expect("two-byte slice"),
    ))
}

fn u32_at(bytes: &[u8], offset: usize) -> Result<u32> {
    let value = bytes
        .get(offset..offset.checked_add(4).context("u32 offset overflow")?)
        .context("truncated u32")?;
    Ok(u32::from_le_bytes(
        value.try_into().expect("four-byte slice"),
    ))
}

fn align(value: usize) -> Result<usize> {
    value
        .checked_add(ALIGN - 1)
        .map(|value| value & !(ALIGN - 1))
        .context("aligned offset overflow")
}

fn digest(value: &str) -> Result<[u8; 32]> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        bail!("SHA-256 must be 64 lowercase hexadecimal characters");
    }
    let mut result = [0u8; 32];
    for (index, byte) in result.iter_mut().enumerate() {
        let nibble = |value: u8| -> u8 {
            match value {
                b'0'..=b'9' => value - b'0',
                b'a'..=b'f' => value - b'a' + 10,
                _ => unreachable!("validated hexadecimal"),
            }
        };
        let offset = index * 2;
        *byte = (nibble(value.as_bytes()[offset]) << 4) | nibble(value.as_bytes()[offset + 1]);
    }
    Ok(result)
}

fn key_hash(key: &str) -> u32 {
    let mut hash = 0x811c_9dc5u32;
    for unit in key.encode_utf16() {
        hash ^= u32::from(unit);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// Authenticate and strictly decode the canonical v1 directory without
/// touching entry payloads. Payload SHA-256 values are returned to the
/// surface, which checks each entry before its first use.
pub fn validate(pak: &'static [u8], value: &Value) -> Result<Validated> {
    let began = Instant::now();
    let manifest: Manifest =
        serde_json::from_value(value.clone()).context("invalid pakIntegrity manifest")?;
    if manifest.format != FORMAT {
        bail!("unsupported pakIntegrity format");
    }
    if pak.len() < HEADER_SIZE {
        bail!("truncated pak header");
    }
    if u32_at(pak, 0)? != pocketjs_core::spec::pak::MAGIC
        || u16_at(pak, 4)? != pocketjs_core::spec::pak::VERSION
    {
        bail!("pak magic or version mismatch");
    }
    if u16_at(pak, 6)? != 0 || u32_at(pak, 28)? != 0 {
        bail!("reserved pak header bytes are nonzero");
    }
    let count = u32_at(pak, 8)? as usize;
    let directory_offset = u32_at(pak, 12)? as usize;
    let names_offset = u32_at(pak, 16)? as usize;
    let directory_bytes = u32_at(pak, 20)? as usize;
    let declared_bytes = u32_at(pak, 24)? as usize;
    if declared_bytes != pak.len() {
        bail!("declared pak length mismatch");
    }
    if directory_offset != HEADER_SIZE {
        bail!("noncanonical directory offset");
    }
    let expected_names_offset = directory_offset
        .checked_add(
            count
                .checked_mul(ENTRY_SIZE)
                .context("directory size overflow")?,
        )
        .context("names offset overflow")?;
    if names_offset != expected_names_offset {
        bail!("noncanonical names offset");
    }
    if directory_bytes != manifest.directory_bytes
        || directory_bytes != align(directory_bytes)?
        || directory_bytes < names_offset
        || directory_bytes > pak.len()
    {
        bail!("invalid directory byte range");
    }
    if manifest.entries.len() != count {
        bail!("manifest entry count mismatch");
    }

    let expected_directory_sha256 =
        digest(&manifest.directory_sha256).context("invalid directory SHA-256")?;
    let hash_began = Instant::now();
    let actual_directory_sha256 = Sha256::digest(&pak[..directory_bytes]);
    let directory_hash_micros = hash_began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    if actual_directory_sha256[..] != expected_directory_sha256 {
        bail!("directory SHA-256 mismatch");
    }

    let mut entries = Vec::with_capacity(count);
    let mut name_cursor = 0usize;
    let mut blob_cursor = directory_bytes;
    let mut previous_key: Option<String> = None;
    for (index, declared) in manifest.entries.into_iter().enumerate() {
        let record = directory_offset
            .checked_add(
                index
                    .checked_mul(ENTRY_SIZE)
                    .context("record offset overflow")?,
            )
            .context("record offset overflow")?;
        let record_end = record
            .checked_add(ENTRY_SIZE)
            .context("record end overflow")?;
        if record_end > names_offset {
            bail!("truncated directory record");
        }
        let stored_key_hash = u32_at(pak, record)?;
        let offset = u32_at(pak, record + 4)? as usize;
        let bytes = u32_at(pak, record + 8)? as usize;
        let name_offset = u32_at(pak, record + 12)? as usize;
        let name_bytes = u16_at(pak, record + 16)? as usize;
        if pak[record + 19] != 0 || u32_at(pak, record + 20)? != 0 {
            bail!("reserved entry bytes are nonzero");
        }
        if name_offset != name_cursor {
            bail!("noncanonical name ordering");
        }
        let name_start = names_offset
            .checked_add(name_offset)
            .context("name offset overflow")?;
        let name_end = name_start
            .checked_add(name_bytes)
            .context("name range overflow")?;
        if name_end > directory_bytes {
            bail!("name range is out of bounds");
        }
        let key =
            std::str::from_utf8(&pak[name_start..name_end]).context("entry name is not UTF-8")?;
        if stored_key_hash != key_hash(key) {
            bail!("entry key hash mismatch");
        }
        if key.is_empty()
            || previous_key
                .as_deref()
                .is_some_and(|previous| key <= previous)
        {
            bail!("entry keys are empty, duplicated, or unsorted");
        }
        if declared.key != key || declared.offset != offset || declared.bytes != bytes {
            bail!("manifest entry metadata mismatch at {key}");
        }
        if offset != blob_cursor || offset != align(offset)? {
            bail!("noncanonical blob offset at {key}");
        }
        let end = offset.checked_add(bytes).context("blob range overflow")?;
        if end > pak.len() {
            bail!("blob range is out of bounds at {key}");
        }
        let padded_end = align(end)?;
        if padded_end > pak.len() {
            bail!("blob padding is truncated at {key}");
        }
        if pak[end..padded_end].iter().any(|byte| *byte != 0) {
            bail!("blob padding is nonzero at {key}");
        }
        entries.push(PakEntryIntegrity {
            key: key.to_owned(),
            offset,
            bytes,
            sha256: digest(&declared.sha256)
                .with_context(|| format!("invalid entry SHA-256 at {key}"))?,
        });
        previous_key = Some(key.to_owned());
        name_cursor = name_cursor
            .checked_add(name_bytes)
            .context("name cursor overflow")?;
        blob_cursor = padded_end;
    }
    let names_end = names_offset
        .checked_add(name_cursor)
        .context("names end overflow")?;
    if names_end > directory_bytes {
        bail!("names overlap blob data");
    }
    if pak[names_end..directory_bytes]
        .iter()
        .any(|byte| *byte != 0)
    {
        bail!("directory padding is nonzero");
    }
    if blob_cursor != pak.len() {
        return Err(anyhow!("unclaimed trailing pak bytes"));
    }
    Ok(Validated {
        entries,
        directory_parse_micros: began.elapsed().as_micros().min(u128::from(u64::MAX)) as u64,
        directory_hash_micros,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn put_u16(bytes: &mut [u8], offset: usize, value: u16) {
        bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
    }

    fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
        bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }

    fn hex(bytes: &[u8]) -> String {
        format!("{:x}", Sha256::digest(bytes))
    }

    fn fixture() -> (Vec<u8>, Value) {
        let entries = [
            ("ui:font.1", b"font".as_slice()),
            ("ui:img.hero", b"image!".as_slice()),
        ];
        let names_bytes = entries.iter().map(|(key, _)| key.len()).sum::<usize>();
        let names_offset = HEADER_SIZE + entries.len() * ENTRY_SIZE;
        let directory_bytes = align(names_offset + names_bytes).unwrap();
        let mut total = directory_bytes;
        for (_, blob) in &entries {
            total = align(total + blob.len()).unwrap();
        }
        let mut pak = vec![0u8; total];
        put_u32(&mut pak, 0, pocketjs_core::spec::pak::MAGIC);
        put_u16(&mut pak, 4, pocketjs_core::spec::pak::VERSION);
        put_u32(&mut pak, 8, entries.len() as u32);
        put_u32(&mut pak, 12, HEADER_SIZE as u32);
        put_u32(&mut pak, 16, names_offset as u32);
        put_u32(&mut pak, 20, directory_bytes as u32);
        put_u32(&mut pak, 24, total as u32);
        let mut name_cursor = 0usize;
        let mut blob_cursor = directory_bytes;
        let mut manifest_entries = Vec::new();
        for (index, (key, blob)) in entries.iter().enumerate() {
            let record = HEADER_SIZE + index * ENTRY_SIZE;
            put_u32(&mut pak, record, key_hash(key));
            put_u32(&mut pak, record + 4, blob_cursor as u32);
            put_u32(&mut pak, record + 8, blob.len() as u32);
            put_u32(&mut pak, record + 12, name_cursor as u32);
            put_u16(&mut pak, record + 16, key.len() as u16);
            pak[names_offset + name_cursor..names_offset + name_cursor + key.len()]
                .copy_from_slice(key.as_bytes());
            pak[blob_cursor..blob_cursor + blob.len()].copy_from_slice(blob);
            manifest_entries.push(json!({
                "key": key,
                "offset": blob_cursor,
                "bytes": blob.len(),
                "sha256": hex(blob),
            }));
            name_cursor += key.len();
            blob_cursor = align(blob_cursor + blob.len()).unwrap();
        }
        let manifest = json!({
            "format": FORMAT,
            "directoryBytes": directory_bytes,
            "directorySha256": hex(&pak[..directory_bytes]),
            "entries": manifest_entries,
        });
        (pak, manifest)
    }

    fn leaked(bytes: Vec<u8>) -> &'static [u8] {
        Box::leak(bytes.into_boxed_slice())
    }

    #[test]
    fn validates_complete_canonical_directory_without_hashing_payloads() {
        let (mut pak, manifest) = fixture();
        let payload = manifest["entries"][1]["offset"].as_u64().unwrap() as usize;
        pak[payload] ^= 0xff;
        let validated = validate(leaked(pak), &manifest).expect("payload hashing is lazy");
        assert_eq!(validated.entries.len(), 2);
        assert_eq!(validated.entries[1].key, "ui:img.hero");
    }

    #[test]
    fn rejects_directory_tamper_truncation_and_manifest_omission() {
        let (mut directory_tamper, manifest) = fixture();
        directory_tamper[HEADER_SIZE + 1] ^= 1;
        assert!(
            validate(leaked(directory_tamper), &manifest)
                .unwrap_err()
                .to_string()
                .contains("directory SHA-256")
        );

        let (mut truncated, manifest) = fixture();
        truncated.pop();
        assert!(
            validate(leaked(truncated), &manifest)
                .unwrap_err()
                .to_string()
                .contains("declared pak length")
        );

        let (pak, mut omitted) = fixture();
        omitted["entries"].as_array_mut().unwrap().pop();
        assert!(
            validate(leaked(pak), &omitted)
                .unwrap_err()
                .to_string()
                .contains("entry count")
        );
    }

    #[test]
    fn rejects_out_of_bounds_entry_and_unhashed_padding_tamper() {
        let (mut out_of_bounds, mut manifest) = fixture();
        let record = HEADER_SIZE;
        put_u32(&mut out_of_bounds, record + 8, u32::MAX);
        manifest["entries"][0]["bytes"] = json!(u32::MAX);
        let directory_bytes = manifest["directoryBytes"].as_u64().unwrap() as usize;
        manifest["directorySha256"] = json!(hex(&out_of_bounds[..directory_bytes]));
        assert!(
            validate(leaked(out_of_bounds), &manifest)
                .unwrap_err()
                .to_string()
                .contains("out of bounds")
        );

        let (mut padding, manifest) = fixture();
        let first_offset = manifest["entries"][0]["offset"].as_u64().unwrap() as usize;
        let first_bytes = manifest["entries"][0]["bytes"].as_u64().unwrap() as usize;
        padding[first_offset + first_bytes] = 1;
        assert!(
            validate(leaked(padding), &manifest)
                .unwrap_err()
                .to_string()
                .contains("padding is nonzero")
        );
    }
}
