//! Canonical record encoding and validation. ATP-0001 §6, §9 stage 12.

use crate::constants::*;
use crate::manifest::Manifest;
use crate::varint::{svarint, uvarint, Reader};
use std::cmp::Ordering;

/// A typed value to encode into a record's positional area.
pub enum Value {
    Bool(bool),
    U(u64),
    I(i64),
    F32(f32),
    F64(f64),
    Enum(u64),
    Str(String),
    Bytes(Vec<u8>),
    Entity(u64),
    Opaque(Vec<u8>),
}

fn encode_value(typ: u64, v: &Value) -> Vec<u8> {
    match (typ, v) {
        (T_BOOL, Value::Bool(b)) => vec![if *b { 1 } else { 0 }],
        (T_U32, Value::U(n)) => {
            assert!(*n <= u32::MAX as u64, "U32 value out of range");
            uvarint(*n)
        }
        (T_U64, Value::U(n)) | (T_DURATION_MS, Value::U(n)) => uvarint(*n),
        (T_I32, Value::I(n)) => {
            assert!(
                *n >= i32::MIN as i64 && *n <= i32::MAX as i64,
                "I32 value out of range"
            );
            svarint(*n)
        }
        (T_I64, Value::I(n)) | (T_TIMESTAMP_MS, Value::I(n)) => svarint(*n),
        (T_F32, Value::F32(f)) => {
            if f.is_nan() {
                0x7fc0_0000u32.to_be_bytes().to_vec()
            } else {
                f.to_be_bytes().to_vec()
            }
        }
        (T_F64, Value::F64(f)) => {
            if f.is_nan() {
                0x7ff8_0000_0000_0000u64.to_be_bytes().to_vec()
            } else {
                f.to_be_bytes().to_vec()
            }
        }
        (T_ENUM, Value::Enum(o)) => uvarint(*o),
        (T_ENTITY_REF, Value::Entity(a)) => uvarint(*a),
        (T_STRING, Value::Str(s)) => {
            let mut out = uvarint(s.len() as u64);
            out.extend_from_slice(s.as_bytes());
            out
        }
        (T_BYTES, Value::Bytes(b)) | (T_OPAQUE_REF, Value::Opaque(b)) => {
            let mut out = uvarint(b.len() as u64);
            out.extend_from_slice(b);
            out
        }
        _ => panic!("value/type mismatch for type {typ}"),
    }
}

/// Encode a canonical record. `values` maps slot -> Value; it must contain all
/// required slots and any present optional slots.
pub fn encode_record(
    schema: &Manifest,
    schema_ref: u64,
    entity_ref: u64,
    time_delta: u64,
    values: &[(u64, Value)],
) -> Vec<u8> {
    let optional: Vec<&_> = schema.fields.iter().filter(|f| !f.required).collect();
    let nbytes = optional.len().div_ceil(8);
    let mut bitmap = vec![0u8; nbytes];
    for (j, f) in optional.iter().enumerate() {
        if values.iter().any(|(s, _)| *s == f.slot) {
            bitmap[j >> 3] |= 1 << (j & 7);
        }
    }
    let mut body = Vec::new();
    for f in &schema.fields {
        let present = f.required || values.iter().any(|(s, _)| *s == f.slot);
        if !present {
            continue;
        }
        let v = values
            .iter()
            .find(|(s, _)| *s == f.slot)
            .map(|(_, v)| v)
            .expect("present value missing");
        body.extend(encode_value(f.typ, v));
    }
    let mut out = uvarint(schema_ref);
    out.extend(uvarint(entity_ref));
    out.extend(uvarint(time_delta));
    out.extend(bitmap);
    out.extend(body);
    out
}

/// Validate a record against its resolved schema (ATP-0001 §9 stage 12).
pub fn validate_record(
    manifest: &Manifest,
    rec: &[u8],
    entity_alias_count: u64,
) -> Result<(), &'static str> {
    if rec.is_empty() || rec.len() > MAX_RECORD_BYTES {
        return Err(E_MALFORMED_RECORD);
    }
    let optional: Vec<&_> = manifest.fields.iter().filter(|f| !f.required).collect();
    let mut r = Reader::new(rec);
    let _schema_ref = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
    let entity_ref = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
    let _time_delta = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
    let nbytes = optional.len().div_ceil(8);
    let bitmap = r.take(nbytes).map_err(|_| E_MALFORMED_RECORD)?.to_vec();
    // trailing padding bits must be zero
    if optional.len() % 8 != 0 {
        let used = optional.len() - (optional.len() / 8) * 8;
        if bitmap[nbytes - 1] >> used != 0 {
            return Err(E_MALFORMED_RECORD);
        }
    }
    if entity_ref >= entity_alias_count {
        return Err(E_MALFORMED_RECORD);
    }
    let present = |j: usize| -> bool { (bitmap[j >> 3] >> (j & 7)) & 1 == 1 };
    // optional-slot -> index among optionals
    let opt_index = |slot: u64| -> usize { optional.iter().position(|f| f.slot == slot).unwrap() };

    for f in &manifest.fields {
        if !f.required && !present(opt_index(f.slot)) {
            continue;
        }
        match f.typ {
            T_BOOL => {
                let b = r.u8().map_err(|_| E_MALFORMED_RECORD)?;
                if b > 1 {
                    return Err(E_MALFORMED_RECORD);
                }
            }
            T_U32 => {
                let v = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                if v > u32::MAX as u64 {
                    return Err(E_SCHEMA_VIOLATION);
                }
                check_unsigned_numeric(f, v)?;
            }
            T_U64 | T_DURATION_MS => {
                let v = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                check_unsigned_numeric(f, v)?;
            }
            T_I32 => {
                let u = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                let v = crate::varint::unzigzag(u);
                if v < i32::MIN as i64 || v > i32::MAX as i64 {
                    return Err(E_SCHEMA_VIOLATION);
                }
                check_signed_numeric(f, v)?;
            }
            T_I64 | T_TIMESTAMP_MS => {
                let u = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                let v = crate::varint::unzigzag(u);
                check_signed_numeric(f, v)?;
            }
            T_F32 => {
                let raw: [u8; 4] = r
                    .take(4)
                    .map_err(|_| E_MALFORMED_RECORD)?
                    .try_into()
                    .unwrap();
                let bits = u32::from_be_bytes(raw);
                let value = f32::from_bits(bits);
                if value.is_nan() && bits != 0x7fc0_0000 {
                    return Err(E_MALFORMED_RECORD);
                }
                check_float_numeric(f, value as f64)?;
            }
            T_F64 => {
                let raw: [u8; 8] = r
                    .take(8)
                    .map_err(|_| E_MALFORMED_RECORD)?
                    .try_into()
                    .unwrap();
                let bits = u64::from_be_bytes(raw);
                let value = f64::from_bits(bits);
                if value.is_nan() && bits != 0x7ff8_0000_0000_0000 {
                    return Err(E_MALFORMED_RECORD);
                }
                check_float_numeric(f, value)?;
            }
            T_ENUM => {
                let ord = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                let members = f
                    .enum_ref
                    .as_ref()
                    .and_then(|e| manifest.enum_members(e))
                    .ok_or(E_SCHEMA_VIOLATION)?;
                if usize::try_from(ord)
                    .map(|ordinal| ordinal >= members.len())
                    .unwrap_or(true)
                {
                    return Err(E_SCHEMA_VIOLATION);
                }
            }
            T_ENTITY_REF => {
                let alias = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                if alias >= entity_alias_count {
                    return Err(E_MALFORMED_RECORD);
                }
            }
            T_STRING | T_BYTES => {
                let ln = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                let raw = r
                    .take(usize::try_from(ln).map_err(|_| E_MALFORMED_RECORD)?)
                    .map_err(|_| E_MALFORMED_RECORD)?;
                if f.typ == T_STRING && std::str::from_utf8(raw).is_err() {
                    return Err(E_MALFORMED_RECORD);
                }
                let max = f
                    .constraints
                    .iter()
                    .find(|(k, _)| *k == 1)
                    .map(|(_, v)| *v as u64)
                    .unwrap_or(DEFAULT_MAX_LEN);
                if ln > max.min(HARD_MAX_LEN) {
                    return Err(E_SCHEMA_VIOLATION);
                }
                if f.name == "intent_ref" && (f.typ != T_BYTES || ln != 32) {
                    return Err(E_SCHEMA_VIOLATION);
                }
            }
            T_OPAQUE_REF => {
                let ln = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
                let raw = r
                    .take(usize::try_from(ln).map_err(|_| E_MALFORMED_RECORD)?)
                    .map_err(|_| E_MALFORMED_RECORD)?;
                validate_opaque_ref(raw)?;
            }
            _ => return Err(E_SCHEMA_VIOLATION),
        }
    }
    if !r.eof() {
        return Err(E_MALFORMED_RECORD);
    }
    Ok(())
}

fn check_signed_numeric(f: &crate::manifest::Field, v: i64) -> Result<(), &'static str> {
    if let Some((_, min)) = f.constraints.iter().find(|(k, _)| *k == 2) {
        if v < *min {
            return Err(E_SCHEMA_VIOLATION);
        }
    }
    if let Some((_, max)) = f.constraints.iter().find(|(k, _)| *k == 3) {
        if v > *max {
            return Err(E_SCHEMA_VIOLATION);
        }
    }
    Ok(())
}

fn check_unsigned_numeric(f: &crate::manifest::Field, v: u64) -> Result<(), &'static str> {
    if let Some((_, min)) = f.constraints.iter().find(|(k, _)| *k == 2) {
        if *min >= 0 && v < *min as u64 {
            return Err(E_SCHEMA_VIOLATION);
        }
    }
    if let Some((_, max)) = f.constraints.iter().find(|(k, _)| *k == 3) {
        if *max < 0 || v > *max as u64 {
            return Err(E_SCHEMA_VIOLATION);
        }
    }
    Ok(())
}

fn check_float_numeric(f: &crate::manifest::Field, v: f64) -> Result<(), &'static str> {
    if v.is_nan() && f.constraints.iter().any(|(k, _)| *k == 2 || *k == 3) {
        return Err(E_SCHEMA_VIOLATION);
    }
    if let Some((_, min)) = f.constraints.iter().find(|(k, _)| *k == 2) {
        if compare_float_to_i64(v, *min) == Some(Ordering::Less) {
            return Err(E_SCHEMA_VIOLATION);
        }
    }
    if let Some((_, max)) = f.constraints.iter().find(|(k, _)| *k == 3) {
        if compare_float_to_i64(v, *max) == Some(Ordering::Greater) {
            return Err(E_SCHEMA_VIOLATION);
        }
    }
    Ok(())
}

fn compare_float_to_i64(value: f64, bound: i64) -> Option<Ordering> {
    if value.is_nan() {
        return None;
    }
    if value < i64::MIN as f64 {
        return Some(Ordering::Less);
    }
    // `i64::MAX as f64` rounds to 2^63, which is above every i64.
    if value >= i64::MAX as f64 {
        return Some(Ordering::Greater);
    }

    let integer_part = value.trunc() as i64;
    let integer_order = integer_part.cmp(&bound);
    if integer_order != Ordering::Equal {
        return Some(integer_order);
    }
    value.partial_cmp(&(integer_part as f64))
}

fn valid_ascii_token(s: &[u8]) -> bool {
    !s.is_empty()
        && s.iter().all(|b| {
            b.is_ascii_alphanumeric()
                || matches!(
                    b,
                    b'!' | b'#' | b'$' | b'&' | b'^' | b'_' | b'.' | b'+' | b'-'
                )
        })
}

fn read_bounded_text<'a>(
    r: &mut Reader<'a>,
    min: usize,
    max: usize,
) -> Result<&'a str, &'static str> {
    let len = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
    let len = usize::try_from(len).map_err(|_| E_MALFORMED_RECORD)?;
    if len < min || len > max {
        return Err(E_SCHEMA_VIOLATION);
    }
    let raw = r.take(len).map_err(|_| E_MALFORMED_RECORD)?;
    std::str::from_utf8(raw).map_err(|_| E_MALFORMED_RECORD)
}

/// Validate the canonical inner value of an `OPAQUE_REF` field.
pub fn validate_opaque_ref(raw: &[u8]) -> Result<(), &'static str> {
    if raw.is_empty() || raw.len() > MAX_OPAQUE_REF_BYTES {
        return Err(E_SCHEMA_VIOLATION);
    }
    let mut r = Reader::new(raw);
    let opaque_id = read_bounded_text(&mut r, 1, MAX_OPAQUE_ID_BYTES)?;
    if !opaque_id.bytes().all(|b| (0x21..=0x7e).contains(&b)) {
        return Err(E_SCHEMA_VIOLATION);
    }

    let media_type = read_bounded_text(&mut r, 3, MAX_MEDIA_TYPE_BYTES)?;
    let mut media_parts = media_type.as_bytes().split(|b| *b == b'/');
    let (Some(major), Some(minor), None) =
        (media_parts.next(), media_parts.next(), media_parts.next())
    else {
        return Err(E_SCHEMA_VIOLATION);
    };
    if !valid_ascii_token(major)
        || !valid_ascii_token(minor)
        || !media_type.bytes().all(|b| !b.is_ascii_uppercase())
    {
        return Err(E_SCHEMA_VIOLATION);
    }

    r.uvarint().map_err(|_| E_MALFORMED_RECORD)?; // byte_length
    r.take(32).map_err(|_| E_MALFORMED_RECORD)?; // payload_digest

    let storage_uri = read_bounded_text(&mut r, 1, MAX_STORAGE_URI_BYTES)?;
    if !storage_uri.is_ascii()
        || !storage_uri.bytes().all(|b| (0x21..=0x7e).contains(&b))
        || !valid_uri_scheme(storage_uri)
    {
        return Err(E_SCHEMA_VIOLATION);
    }
    let retention = r.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
    if retention > u32::MAX as u64 || !r.eof() {
        return Err(E_SCHEMA_VIOLATION);
    }
    Ok(())
}

/// Verify bytes fetched for an `OpaqueRef`, checking the declared length
/// before the digest so implementations return the same first failure.
pub fn verify_opaque_payload(
    expected_length: u64,
    expected_digest: &[u8; 32],
    payload: &[u8],
) -> Result<(), &'static str> {
    if u64::try_from(payload.len()).map_err(|_| E_OPAQUE_LENGTH_MISMATCH)? != expected_length {
        return Err(E_OPAQUE_LENGTH_MISMATCH);
    }
    if crate::crypto::sha256(payload) != *expected_digest {
        return Err(E_OPAQUE_DIGEST_MISMATCH);
    }
    Ok(())
}

fn valid_uri_scheme(uri: &str) -> bool {
    let Some((scheme, _)) = uri.split_once(':') else {
        return false;
    };
    let mut bytes = scheme.bytes();
    matches!(bytes.next(), Some(b) if b.is_ascii_lowercase())
        && bytes.all(|b| {
            b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'+' | b'-' | b'.')
        })
}
