//! Canonical ATP-TAB resolved-record rendering from ATP-0001 Section 13.

use crate::batch::Batch;
use crate::constants::*;
use crate::manifest::Manifest;
use crate::varint::{unzigzag, Reader};
use std::collections::HashMap;

pub fn render_batch(
    batch: &Batch,
    schemas: &HashMap<[u8; 32], Manifest>,
    entity_aliases: &[String],
) -> Result<String, &'static str> {
    let mut output = String::new();
    let mut previous_schema = None;
    render_batch_into(
        &mut output,
        &mut previous_schema,
        batch,
        schemas,
        entity_aliases,
    )?;
    Ok(output)
}

pub fn render_batches(
    batches: &[&Batch],
    schemas: &HashMap<[u8; 32], Manifest>,
    entity_aliases: &[String],
) -> Result<String, &'static str> {
    let mut output = String::new();
    let mut previous_schema = None;
    for batch in batches {
        render_batch_into(
            &mut output,
            &mut previous_schema,
            batch,
            schemas,
            entity_aliases,
        )?;
    }
    Ok(output)
}

fn render_batch_into(
    output: &mut String,
    previous_schema: &mut Option<[u8; 32]>,
    batch: &Batch,
    schemas: &HashMap<[u8; 32], Manifest>,
    entity_aliases: &[String],
) -> Result<(), &'static str> {
    for (index, record) in batch.records.iter().enumerate() {
        let sequence = batch
            .first_sequence
            .checked_add(index as u64)
            .ok_or(E_MALFORMED_RECORD)?;
        let mut reader = Reader::new(record);
        let schema_ref = usize::try_from(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?)
            .map_err(|_| E_MALFORMED_RECORD)?;
        let entity_ref = usize::try_from(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?)
            .map_err(|_| E_MALFORMED_RECORD)?;
        let time_delta = reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
        let absolute_time = batch
            .base_time
            .checked_add(time_delta)
            .ok_or(E_MALFORMED_RECORD)?;

        let digest = *batch
            .schema_digests
            .get(schema_ref)
            .ok_or(E_MALFORMED_RECORD)?;
        let schema = schemas.get(&digest).ok_or(E_SCHEMA_UNKNOWN)?;
        let entity = entity_aliases.get(entity_ref).ok_or(E_MALFORMED_RECORD)?;

        if *previous_schema != Some(digest) {
            render_header(output, &digest, schema);
            *previous_schema = Some(digest);
        }

        let optional: Vec<_> = schema
            .fields
            .iter()
            .filter(|field| !field.required)
            .collect();
        let bitmap_len = optional.len().div_ceil(8);
        let bitmap = reader
            .take(bitmap_len)
            .map_err(|_| E_MALFORMED_RECORD)?
            .to_vec();
        if optional.len() % 8 != 0 && bitmap[bitmap_len - 1] >> (optional.len() % 8) != 0 {
            return Err(E_MALFORMED_RECORD);
        }

        output.push_str(&sequence.to_string());
        output.push('\t');
        output.push_str(&absolute_time.to_string());
        output.push('\t');
        output.push_str(&escape(entity.as_bytes()));

        for field in &schema.fields {
            output.push('\t');
            if !field.required {
                let position = optional
                    .iter()
                    .position(|optional_field| optional_field.slot == field.slot)
                    .ok_or(E_SCHEMA_VIOLATION)?;
                if (bitmap[position >> 3] >> (position & 7)) & 1 == 0 {
                    output.push('~');
                    continue;
                }
            }
            output.push_str(&render_value(
                &mut reader,
                field.typ,
                field.enum_ref.as_deref(),
                schema,
                entity_aliases,
            )?);
        }
        if !reader.eof() {
            return Err(E_MALFORMED_RECORD);
        }
        output.push('\n');
    }
    Ok(())
}

pub fn escape(bytes: &[u8]) -> String {
    let mut output = String::new();
    for byte in bytes {
        match *byte {
            0x21..=0x7e if *byte != b'\\' => output.push(*byte as char),
            b'\\' => output.push_str("\\\\"),
            _ => output.push_str(&format!("\\x{byte:02X}")),
        }
    }
    output
}

fn render_header(output: &mut String, digest: &[u8; 32], schema: &Manifest) {
    output.push_str("@schema\t");
    output.push_str(&hex_lower(digest));
    output.push('\t');
    output.push_str(&escape(schema.name.as_bytes()));
    output.push('\t');
    output.push_str(&escape(schema.version.as_bytes()));
    for field in &schema.fields {
        output.push('\t');
        output.push_str(&escape(field.name.as_bytes()));
    }
    output.push('\n');
}

fn render_value(
    reader: &mut Reader<'_>,
    typ: u64,
    enum_ref: Option<&str>,
    schema: &Manifest,
    entity_aliases: &[String],
) -> Result<String, &'static str> {
    match typ {
        T_BOOL => match reader.u8().map_err(|_| E_MALFORMED_RECORD)? {
            0 => Ok("b:0".into()),
            1 => Ok("b:1".into()),
            _ => Err(E_MALFORMED_RECORD),
        },
        T_U32 => {
            let value = reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
            if value > u32::MAX as u64 {
                return Err(E_SCHEMA_VIOLATION);
            }
            Ok(format!("u:{value}"))
        }
        T_U64 => Ok(format!(
            "u:{}",
            reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?
        )),
        T_I32 => {
            let value = unzigzag(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?);
            if value < i32::MIN as i64 || value > i32::MAX as i64 {
                return Err(E_SCHEMA_VIOLATION);
            }
            Ok(format!("i:{value}"))
        }
        T_I64 => Ok(format!(
            "i:{}",
            unzigzag(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?)
        )),
        T_F32 => Ok(format!(
            "f32:{}",
            hex_lower(reader.take(4).map_err(|_| E_MALFORMED_RECORD)?)
        )),
        T_F64 => Ok(format!(
            "f64:{}",
            hex_lower(reader.take(8).map_err(|_| E_MALFORMED_RECORD)?)
        )),
        T_ENUM => {
            let ordinal = reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?;
            let members = enum_ref
                .and_then(|name| schema.enum_members(name))
                .ok_or(E_SCHEMA_VIOLATION)?;
            let member = members
                .get(usize::try_from(ordinal).map_err(|_| E_SCHEMA_VIOLATION)?)
                .ok_or(E_SCHEMA_VIOLATION)?;
            Ok(format!("e:{ordinal}:{}", escape(member.as_bytes())))
        }
        T_STRING => {
            let bytes = read_length_prefixed(reader)?;
            std::str::from_utf8(bytes).map_err(|_| E_MALFORMED_RECORD)?;
            Ok(format!("s:{}", escape(bytes)))
        }
        T_BYTES => Ok(format!("x:{}", hex_lower(read_length_prefixed(reader)?))),
        T_TIMESTAMP_MS => Ok(format!(
            "t:{}",
            unzigzag(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?)
        )),
        T_ENTITY_REF => {
            let alias = usize::try_from(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?)
                .map_err(|_| E_MALFORMED_RECORD)?;
            let entity = entity_aliases.get(alias).ok_or(E_MALFORMED_RECORD)?;
            Ok(format!("r:{}", escape(entity.as_bytes())))
        }
        T_OPAQUE_REF => Ok(format!("o:{}", hex_lower(read_length_prefixed(reader)?))),
        T_DURATION_MS => Ok(format!(
            "d:{}",
            reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?
        )),
        _ => Err(E_SCHEMA_VIOLATION),
    }
}

fn read_length_prefixed<'a>(reader: &mut Reader<'a>) -> Result<&'a [u8], &'static str> {
    let length = usize::try_from(reader.uvarint().map_err(|_| E_MALFORMED_RECORD)?)
        .map_err(|_| E_MALFORMED_RECORD)?;
    reader.take(length).map_err(|_| E_MALFORMED_RECORD)
}

fn hex_lower(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}
