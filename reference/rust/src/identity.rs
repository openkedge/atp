//! Canonical entity identifiers. ATP-0001 §4.

use crate::constants::MAX_ENTITY_ID_BYTES;

fn valid_component(s: &str) -> bool {
    let mut bytes = s.bytes();
    matches!(bytes.next(), Some(b) if b.is_ascii_alphanumeric())
        && bytes.all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
}

/// Validate `namespace:resource_type:canonical_identifier`.
///
/// The canonical form is ASCII. Non-ASCII source identifiers are represented
/// by uppercase `%HH` escapes of their UTF-8 bytes. A literal percent is
/// represented as `%25`; printable ASCII otherwise remains unescaped.
pub fn valid_entity_id(id: &str) -> bool {
    if id.is_empty() || id.len() > MAX_ENTITY_ID_BYTES || !id.is_ascii() {
        return false;
    }
    let mut parts = id.splitn(3, ':');
    let (Some(namespace), Some(resource_type), Some(identifier)) =
        (parts.next(), parts.next(), parts.next())
    else {
        return false;
    };
    if !valid_component(namespace) || !valid_component(resource_type) || identifier.is_empty() {
        return false;
    }

    let bytes = identifier.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if !(0x21..=0x7e).contains(&b) {
            return false;
        }
        if b == b'%' {
            if i + 2 >= bytes.len()
                || !bytes[i + 1].is_ascii_hexdigit()
                || !bytes[i + 2].is_ascii_hexdigit()
                || bytes[i + 1].is_ascii_lowercase()
                || bytes[i + 2].is_ascii_lowercase()
            {
                return false;
            }
            let value = match (
                (bytes[i + 1] as char).to_digit(16),
                (bytes[i + 2] as char).to_digit(16),
            ) {
                (Some(hi), Some(lo)) => ((hi << 4) | lo) as u8,
                _ => return false,
            };
            if value < 0x80 && value != b'%' {
                return false;
            }
            decoded.push(value);
            i += 3;
        } else {
            decoded.push(b);
            i += 1;
        }
    }
    std::str::from_utf8(&decoded).is_ok()
}
