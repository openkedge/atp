//! LEB128 varints (minimal/canonical) and ZigZag, plus a bounds-checked reader.
//! ATP-0001 §6.2.

use std::fmt;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DecodeError;

impl fmt::Display for DecodeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("non-canonical or truncated ATP encoding")
    }
}

impl std::error::Error for DecodeError {}

/// Unsigned LEB128, minimal form.
pub fn uvarint(mut n: u64) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let b = (n & 0x7f) as u8;
        n >>= 7;
        if n != 0 {
            out.push(b | 0x80);
        } else {
            out.push(b);
            break;
        }
    }
    out
}

pub fn zigzag(n: i64) -> u64 {
    ((n << 1) ^ (n >> 63)) as u64
}

pub fn unzigzag(u: u64) -> i64 {
    ((u >> 1) as i64) ^ -((u & 1) as i64)
}

pub fn svarint(n: i64) -> Vec<u8> {
    uvarint(zigzag(n))
}

/// A cursor over a byte slice with bounds-checked reads.
pub struct Reader<'a> {
    pub buf: &'a [u8],
    pub pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    pub fn take(&mut self, n: usize) -> Result<&'a [u8], DecodeError> {
        let end = self.pos.checked_add(n).ok_or(DecodeError)?;
        if end > self.buf.len() {
            return Err(DecodeError);
        }
        let s = &self.buf[self.pos..end];
        self.pos = end;
        Ok(s)
    }

    pub fn u8(&mut self) -> Result<u8, DecodeError> {
        Ok(self.take(1)?[0])
    }

    pub fn uvarint(&mut self) -> Result<u64, DecodeError> {
        let start = self.pos;
        let mut val: u128 = 0;
        let mut nbytes: u32 = 0;
        loop {
            let byte = self.u8()?;
            val |= ((byte & 0x7f) as u128) << (7 * nbytes);
            nbytes += 1;
            if byte & 0x80 == 0 {
                break;
            }
            if nbytes >= 10 {
                return Err(DecodeError);
            }
        }
        if val > u64::MAX as u128 {
            return Err(DecodeError);
        }
        let val = val as u64;
        // minimality: the canonical encoding must equal what we consumed.
        if uvarint(val) != self.buf[start..self.pos] {
            return Err(DecodeError);
        }
        Ok(val)
    }

    pub fn eof(&self) -> bool {
        self.pos == self.buf.len()
    }
}
