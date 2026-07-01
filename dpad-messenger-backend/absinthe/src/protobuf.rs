//! A deliberately tiny protobuf reader/writer.
//!
//! The OABS "dumb file" is a protobuf message (with a 5-byte `OABS\0` magic
//! prefix). We only need two wire types — varint (0) and length-delimited (2) —
//! so a full protobuf dependency + codegen would be overkill and would hide the
//! exact byte layout we reverse-engineered. This module is verified byte-exact
//! against a real captured MacOSConfig in `tests/oabs.rs`.

use crate::error::{AbsintheError, Result};

/// A single decoded protobuf field: `(field_number, value)`.
pub enum Field {
    Varint(u64),
    Bytes(Vec<u8>),
}

pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    pub fn is_empty(&self) -> bool {
        self.pos >= self.buf.len()
    }

    fn read_varint(&mut self) -> Result<u64> {
        let mut shift = 0u32;
        let mut result = 0u64;
        loop {
            let byte = *self
                .buf
                .get(self.pos)
                .ok_or_else(|| AbsintheError::BadHardwareConfig("truncated varint".into()))?;
            self.pos += 1;
            result |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                break;
            }
            shift += 7;
            if shift >= 64 {
                return Err(AbsintheError::BadHardwareConfig("varint too long".into()));
            }
        }
        Ok(result)
    }

    /// Read the next `(field_number, field)` pair, or `None` at end of buffer.
    pub fn next_field(&mut self) -> Result<Option<(u32, Field)>> {
        if self.is_empty() {
            return Ok(None);
        }
        let tag = self.read_varint()?;
        let field_number = (tag >> 3) as u32;
        let wire_type = (tag & 0x7) as u8;
        match wire_type {
            0 => Ok(Some((field_number, Field::Varint(self.read_varint()?)))),
            2 => {
                let len = self.read_varint()? as usize;
                let end = self
                    .pos
                    .checked_add(len)
                    .filter(|e| *e <= self.buf.len())
                    .ok_or_else(|| {
                        AbsintheError::BadHardwareConfig("length-delimited overruns buffer".into())
                    })?;
                let bytes = self.buf[self.pos..end].to_vec();
                self.pos = end;
                Ok(Some((field_number, Field::Bytes(bytes))))
            }
            other => Err(AbsintheError::BadHardwareConfig(format!(
                "unsupported wire type {other} for field {field_number}"
            ))),
        }
    }
}

#[derive(Default)]
pub struct Writer {
    out: Vec<u8>,
}

impl Writer {
    pub fn new() -> Self {
        Writer { out: Vec::new() }
    }

    fn write_varint(&mut self, mut v: u64) {
        loop {
            let byte = (v & 0x7f) as u8;
            v >>= 7;
            if v != 0 {
                self.out.push(byte | 0x80);
            } else {
                self.out.push(byte);
                break;
            }
        }
    }

    fn tag(&mut self, field_number: u32, wire_type: u8) {
        self.write_varint(((field_number as u64) << 3) | wire_type as u64);
    }

    pub fn varint(&mut self, field_number: u32, v: u64) {
        self.tag(field_number, 0);
        self.write_varint(v);
    }

    pub fn bytes(&mut self, field_number: u32, v: &[u8]) {
        self.tag(field_number, 2);
        self.write_varint(v.len() as u64);
        self.out.extend_from_slice(v);
    }

    pub fn string(&mut self, field_number: u32, v: &str) {
        self.bytes(field_number, v.as_bytes());
    }

    pub fn out_len(&self) -> usize {
        self.out.len()
    }

    pub fn finish(self) -> Vec<u8> {
        self.out
    }
}
