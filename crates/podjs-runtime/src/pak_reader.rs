//! Bounded, synchronous adapter for immutable host package sources.

use std::ffi::c_void;
use std::io::{self, ErrorKind};

pub const READ_CHUNK: usize = 64 * 1024;
pub type PodPakReadAt = unsafe extern "C" fn(*mut c_void, u64, *mut u8, usize) -> i64;

pub struct PakReader {
    pub length: usize,
    pub read_at: PodPakReadAt,
    pub context: *mut c_void,
}

impl PakReader {
    pub fn read_exact_at(&mut self, offset: usize, mut bytes: &mut [u8]) -> io::Result<()> {
        if offset.checked_add(bytes.len()).is_none_or(|end| end > self.length) {
            return Err(io::Error::new(ErrorKind::InvalidInput, "pak read range is out of bounds"));
        }
        let mut position = offset;
        while !bytes.is_empty() {
            let requested = bytes.len().min(READ_CHUNK);
            let count = unsafe {
                (self.read_at)(self.context, position as u64, bytes.as_mut_ptr(), requested)
            };
            let failure = match count {
                0 => Some((ErrorKind::UnexpectedEof, "unexpected end of pak")),
                n if n < 0 => Some((ErrorKind::Other, "pak source read failed")),
                n if n as u64 > requested as u64 => Some((ErrorKind::InvalidData, "pak source returned too many bytes")),
                _ => None,
            };
            if let Some((kind, message)) = failure {
                return Err(io::Error::new(kind, format!("{message} at offset {position}, requested {requested}")));
            }
            position += count as usize;
            bytes = &mut bytes[count as usize..];
        }
        Ok(())
    }

    pub fn checksum(&mut self) -> io::Result<u64> {
        let mut chunk = vec![0; self.length.min(READ_CHUNK)];
        let mut hash = super::FNV_OFFSET;
        let mut offset = 0;
        while offset < self.length {
            let count = chunk.len().min(self.length - offset);
            self.read_exact_at(offset, &mut chunk[..count])?;
            hash = super::hash_extend(hash, &chunk[..count]);
            offset += count;
        }
        Ok(hash)
    }
}

#[cfg(test)]
#[path = "pak_reader_tests.rs"]
mod tests;
