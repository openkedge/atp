//! Deterministic OpenTelemetry bridge primitives from ATP-0004.

use crate::constants::*;
use crate::crypto::sha256;
use crate::manifest::{Field, Manifest};
use crate::varint::uvarint;
use std::collections::BTreeMap;

pub const OTEL_PUBLISHER: &str = "openkedge.io/otel";

#[derive(Clone, Debug, PartialEq)]
pub enum AnyValue {
    Empty,
    String(String),
    Bool(bool),
    I64(i64),
    F64(f64),
    Bytes(Vec<u8>),
    Array(Vec<AnyValue>),
    KvList(Attributes),
}

pub type Attributes = BTreeMap<String, AnyValue>;

pub fn encode_any_value(value: &AnyValue) -> Vec<u8> {
    match value {
        AnyValue::Empty => vec![0],
        AnyValue::String(value) => {
            let mut out = vec![1];
            out.extend(lp(value.as_bytes()));
            out
        }
        AnyValue::Bool(value) => vec![2, u8::from(*value)],
        AnyValue::I64(value) => {
            let mut out = vec![3];
            out.extend_from_slice(&value.to_be_bytes());
            out
        }
        AnyValue::F64(value) => {
            let bits = if value.is_nan() {
                0x7ff8_0000_0000_0000
            } else {
                value.to_bits()
            };
            let mut out = vec![4];
            out.extend_from_slice(&bits.to_be_bytes());
            out
        }
        AnyValue::Bytes(value) => {
            let mut out = vec![5];
            out.extend(lp(value));
            out
        }
        AnyValue::Array(values) => {
            let mut out = vec![6];
            out.extend(uvarint(values.len() as u64));
            for value in values {
                out.extend(encode_any_value(value));
            }
            out
        }
        AnyValue::KvList(values) => {
            let mut out = vec![7];
            out.extend(encode_attribute_map(values));
            out
        }
    }
}

pub fn encode_attribute_map(attributes: &Attributes) -> Vec<u8> {
    let mut out = uvarint(attributes.len() as u64);
    for (key, value) in attributes {
        out.extend(lp(key.as_bytes()));
        out.extend(encode_any_value(value));
    }
    out
}

pub fn resource_entity(identity_scope: &str, attributes: &Attributes) -> Result<String, String> {
    validate_scope(identity_scope)?;

    if let Some(AnyValue::String(uid)) = attributes.get("k8s.pod.uid") {
        if let Some(identifier) = canonical_identifier(uid) {
            let candidate = format!("k8s.{identity_scope}:pod:{identifier}");
            if candidate.len() <= MAX_ENTITY_ID_BYTES {
                return Ok(candidate);
            }
        }
    }

    if let Some(AnyValue::String(service_name)) = attributes.get("service.name") {
        if !service_name.is_empty() {
            let mut identity = Attributes::new();
            identity.insert(
                "service.name".into(),
                AnyValue::String(service_name.clone()),
            );
            for key in ["service.namespace", "service.instance.id"] {
                if let Some(AnyValue::String(value)) = attributes.get(key) {
                    identity.insert(key.into(), AnyValue::String(value.clone()));
                }
            }
            let digest = resource_digest(&identity);
            let kind = if identity.contains_key("service.instance.id") {
                "service_instance"
            } else {
                "service"
            };
            return Ok(format!(
                "otel.{identity_scope}:{kind}:{}",
                hex_lower(&digest)
            ));
        }
    }

    Ok(format!(
        "otel.{identity_scope}:resource:{}",
        hex_lower(&resource_digest(attributes))
    ))
}

pub fn span_entity(
    identity_scope: &str,
    trace_id: &[u8; 16],
    span_id: &[u8; 8],
) -> Result<String, String> {
    validate_scope(identity_scope)?;
    if trace_id.iter().all(|byte| *byte == 0) || span_id.iter().all(|byte| *byte == 0) {
        return Err("invalid all-zero span identifier".into());
    }
    Ok(format!(
        "otel.{identity_scope}:span:{}/{}",
        hex_lower(trace_id),
        hex_lower(span_id)
    ))
}

pub fn intent_ref(trace_id: &[u8; 16]) -> Result<[u8; 32], String> {
    if trace_id.iter().all(|byte| *byte == 0) {
        return Err("invalid all-zero trace identifier".into());
    }
    let mut preimage = D_OTEL_INTENT.to_vec();
    preimage.extend_from_slice(trace_id);
    Ok(sha256(&preimage))
}

pub fn severity_ordinal(severity_number: u32) -> Result<u64, String> {
    match severity_number {
        0 => Ok(0),
        1..=4 => Ok(1),
        5..=8 => Ok(2),
        9..=12 => Ok(3),
        13..=16 => Ok(4),
        17..=20 => Ok(5),
        21..=24 => Ok(6),
        _ => Err("severity_number outside 0..24".into()),
    }
}

pub fn opaque_ref(payload: &[u8], media_type: &str) -> Result<Vec<u8>, String> {
    if !valid_media_type(media_type) {
        return Err("invalid canonical media type".into());
    }
    let digest = sha256(payload);
    let digest_hex = hex_lower(&digest);
    let opaque_id = format!("sha256-{digest_hex}");
    let storage_uri = format!("atp-opaque:sha256:{digest_hex}");

    let mut out = lp(opaque_id.as_bytes());
    out.extend(lp(media_type.as_bytes()));
    out.extend(uvarint(payload.len() as u64));
    out.extend_from_slice(&digest);
    out.extend(lp(storage_uri.as_bytes()));
    out.extend(uvarint(0));
    Ok(out)
}

pub fn span_manifest() -> Manifest {
    Manifest {
        name: "otel.span.transition".into(),
        version: "1.0.0".into(),
        primitive: 0,
        publisher: OTEL_PUBLISHER.into(),
        fields: vec![
            Field::new(0, "old_state", T_ENUM, true).enum_ref("span_state"),
            Field::new(1, "new_state", T_ENUM, true).enum_ref("span_state"),
            Field::new(2, "operation", T_STRING, false).constraint(1, 256),
            Field::new(3, "span_name_ref", T_OPAQUE_REF, false),
            Field::new(4, "status_code", T_I32, false),
            Field::new(5, "intent_ref", T_BYTES, false).constraint(1, 32),
            Field::new(6, "trace_ref", T_BYTES, true).constraint(1, 16),
            Field::new(7, "span_ref", T_BYTES, true).constraint(1, 8),
            Field::new(8, "span_kind", T_ENUM, true).enum_ref("span_kind"),
            Field::new(9, "attributes_ref", T_OPAQUE_REF, false),
        ],
        enums: vec![
            (
                "span_state".into(),
                ["Running", "Unset", "Ok", "Error"]
                    .into_iter()
                    .map(str::to_string)
                    .collect(),
            ),
            (
                "span_kind".into(),
                [
                    "Unspecified",
                    "Internal",
                    "Server",
                    "Client",
                    "Producer",
                    "Consumer",
                ]
                .into_iter()
                .map(str::to_string)
                .collect(),
            ),
        ],
        compatibility: None,
    }
}

pub fn log_manifest() -> Manifest {
    Manifest {
        name: "otel.log.observation".into(),
        version: "1.0.0".into(),
        primitive: 1,
        publisher: OTEL_PUBLISHER.into(),
        fields: vec![
            Field::new(0, "severity", T_ENUM, true).enum_ref("severity"),
            Field::new(1, "event_name", T_STRING, false).constraint(1, 256),
            Field::new(2, "trace_ref", T_BYTES, false).constraint(1, 16),
            Field::new(3, "span_ref", T_BYTES, false).constraint(1, 8),
            Field::new(4, "body_ref", T_OPAQUE_REF, false),
            Field::new(5, "attributes_ref", T_OPAQUE_REF, false),
        ],
        enums: vec![(
            "severity".into(),
            [
                "UNSPECIFIED",
                "TRACE",
                "DEBUG",
                "INFO",
                "WARN",
                "ERROR",
                "FATAL",
            ]
            .into_iter()
            .map(str::to_string)
            .collect(),
        )],
        compatibility: None,
    }
}

pub fn metric_manifest() -> Manifest {
    Manifest {
        name: "otel.metric.number.observation".into(),
        version: "1.0.0".into(),
        primitive: 1,
        publisher: OTEL_PUBLISHER.into(),
        fields: vec![
            Field::new(0, "metric_name", T_STRING, true).constraint(1, 256),
            Field::new(1, "unit", T_STRING, true).constraint(1, 64),
            Field::new(2, "metric_kind", T_ENUM, true).enum_ref("metric_kind"),
            Field::new(3, "value_int", T_I64, false),
            Field::new(4, "value_float", T_F64, false),
            Field::new(5, "start_time_ms", T_TIMESTAMP_MS, false),
            Field::new(6, "temporality", T_ENUM, true).enum_ref("temporality"),
            Field::new(7, "is_monotonic", T_BOOL, true),
            Field::new(8, "flags", T_U32, true),
            Field::new(9, "attributes_ref", T_OPAQUE_REF, false),
        ],
        enums: vec![
            (
                "metric_kind".into(),
                ["Gauge", "Sum"].into_iter().map(str::to_string).collect(),
            ),
            (
                "temporality".into(),
                ["Unspecified", "Delta", "Cumulative"]
                    .into_iter()
                    .map(str::to_string)
                    .collect(),
            ),
        ],
        compatibility: None,
    }
}

fn resource_digest(attributes: &Attributes) -> [u8; 32] {
    let mut preimage = D_OTEL_RESOURCE.to_vec();
    preimage.extend(encode_attribute_map(attributes));
    sha256(&preimage)
}

fn canonical_identifier(value: &str) -> Option<String> {
    if value.is_empty() {
        return None;
    }
    let mut out = String::new();
    for byte in value.as_bytes() {
        match *byte {
            0x21..=0x7e if *byte != b'%' => out.push(*byte as char),
            b'%' | 0x80..=0xff => out.push_str(&format!("%{byte:02X}")),
            _ => return None,
        }
    }
    Some(out)
}

fn validate_scope(scope: &str) -> Result<(), String> {
    if scope.len() > 128 {
        return Err("invalid identity_scope".into());
    }
    let mut bytes = scope.bytes();
    if !matches!(bytes.next(), Some(byte) if byte.is_ascii_lowercase())
        || !bytes.all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'_' | b'-')
        })
    {
        return Err("invalid identity_scope".into());
    }
    Ok(())
}

fn valid_media_type(media_type: &str) -> bool {
    if media_type.len() < 3
        || media_type.len() > MAX_MEDIA_TYPE_BYTES
        || media_type.bytes().any(|byte| byte.is_ascii_uppercase())
    {
        return false;
    }
    let mut parts = media_type.as_bytes().split(|byte| *byte == b'/');
    matches!(
        (parts.next(), parts.next(), parts.next()),
        (Some(major), Some(minor), None) if valid_token(major) && valid_token(minor)
    )
}

fn valid_token(value: &[u8]) -> bool {
    !value.is_empty()
        && value.iter().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(
                    byte,
                    b'!' | b'#' | b'$' | b'&' | b'^' | b'_' | b'.' | b'+' | b'-'
                )
        })
}

fn lp(bytes: &[u8]) -> Vec<u8> {
    let mut out = uvarint(bytes.len() as u64);
    out.extend_from_slice(bytes);
    out
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
