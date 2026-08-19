//! Schema manifests and content-addressed identity. ATP-0002.

use crate::cbor::{encode, Cbor};
use crate::constants::{
    D_MANIFEST, HARD_MAX_LEN, MAX_MANIFEST_BYTES, MAX_SCHEMA_ENUMS, MAX_SCHEMA_FIELDS, T_BYTES,
    T_DURATION_MS, T_ENUM, T_F32, T_F64, T_I32, T_I64, T_STRING, T_TIMESTAMP_MS, T_U32, T_U64,
};
use crate::crypto::sha256;
use std::cmp::Ordering;
use std::collections::HashSet;

#[derive(Clone)]
pub struct Field {
    pub slot: u64,
    pub name: String,
    pub typ: u64,
    pub required: bool,
    pub unit: Option<String>,
    pub enum_ref: Option<String>,
    /// (constraint-key, value): 1=max_len, 2=min, 3=max (ATP-0002 §4.2).
    pub constraints: Vec<(u64, i64)>,
}

impl Field {
    pub fn new(slot: u64, name: &str, typ: u64, required: bool) -> Self {
        Field {
            slot,
            name: name.to_string(),
            typ,
            required,
            unit: None,
            enum_ref: None,
            constraints: Vec::new(),
        }
    }
    pub fn unit(mut self, u: &str) -> Self {
        self.unit = Some(u.to_string());
        self
    }
    pub fn enum_ref(mut self, e: &str) -> Self {
        self.enum_ref = Some(e.to_string());
        self
    }
    pub fn constraint(mut self, k: u64, v: i64) -> Self {
        self.constraints.push((k, v));
        self
    }

    fn to_cbor(&self) -> Cbor {
        let mut m = vec![
            (Cbor::U(1), Cbor::U(self.slot)),
            (Cbor::U(2), Cbor::Text(self.name.clone())),
            (Cbor::U(3), Cbor::U(self.typ)),
            (Cbor::U(4), Cbor::Bool(self.required)),
        ];
        if let Some(u) = &self.unit {
            m.push((Cbor::U(5), Cbor::Text(u.clone())));
        }
        if let Some(er) = &self.enum_ref {
            m.push((Cbor::U(6), Cbor::Text(er.clone())));
        }
        if !self.constraints.is_empty() {
            let cm = self
                .constraints
                .iter()
                .map(|(k, v)| (Cbor::U(*k), Cbor::I(*v)))
                .collect();
            m.push((Cbor::U(7), Cbor::Map(cm)));
        }
        Cbor::Map(m)
    }
}

#[derive(Clone)]
pub struct Manifest {
    pub name: String,
    pub version: String,
    pub primitive: u64,
    pub publisher: String,
    pub fields: Vec<Field>,
    pub enums: Vec<(String, Vec<String>)>,
    pub compatibility: Option<(String, u64)>,
}

impl Manifest {
    pub fn validate(&self) -> Result<(), String> {
        if self.name.len() > 128 || !valid_dotted_name(&self.name) {
            return Err("BAD_SCHEMA_NAME".into());
        }
        if !valid_semver(&self.version) {
            return Err("BAD_SCHEMA_VERSION".into());
        }
        if self.primitive > 3 {
            return Err("BAD_PRIMITIVE".into());
        }
        if self.publisher.is_empty()
            || self.publisher.len() > 128
            || !self.publisher.bytes().all(|b| (0x21..=0x7e).contains(&b))
        {
            return Err("BAD_PUBLISHER".into());
        }
        if self.fields.is_empty() || self.fields.len() > MAX_SCHEMA_FIELDS {
            return Err("EMPTY_FIELDS".into());
        }

        let mut field_names = HashSet::new();
        for (index, field) in self.fields.iter().enumerate() {
            if field.slot != index as u64 {
                return Err(format!(
                    "SLOT_NOT_DENSE:expected={index}:got={}",
                    field.slot
                ));
            }
            if field.name.len() > 64 || !valid_field_name(&field.name) {
                return Err(format!("BAD_FIELD_NAME:{index}"));
            }
            if !field_names.insert(field.name.as_str()) {
                return Err(format!("DUP_FIELD_NAME:{}", field.name));
            }
            if field.typ > 13 {
                return Err(format!("UNKNOWN_TYPE:{}", field.typ));
            }
            if let Some(unit) = &field.unit {
                if unit.is_empty()
                    || unit.len() > 64
                    || !unit.bytes().all(|b| (0x21..=0x7e).contains(&b))
                {
                    return Err(format!("BAD_UNIT:{}", field.name));
                }
            }
            if field.typ == T_ENUM {
                let enum_ref = field
                    .enum_ref
                    .as_ref()
                    .ok_or_else(|| "ENUM_MISSING_ENUM_REF".to_string())?;
                if self.enum_members(enum_ref).is_none() {
                    return Err(format!("ENUM_REF_UNRESOLVED:{enum_ref}"));
                }
            } else if field.enum_ref.is_some() {
                return Err(format!("ENUM_REF_ON_NONENUM:{}", field.name));
            }
            if field.name == "intent_ref"
                && (!(self.primitive == 0 || self.primitive == 2) || field.typ != T_BYTES)
            {
                return Err("BAD_INTENT_REF".into());
            }
            validate_constraints(field)?;
        }

        if self.enums.len() > MAX_SCHEMA_ENUMS {
            return Err("TOO_MANY_ENUMS".into());
        }
        let mut enum_names = HashSet::new();
        for (name, members) in &self.enums {
            if name.len() > 64 || !valid_field_name(name) {
                return Err("BAD_ENUM_NAME".into());
            }
            if !enum_names.insert(name.as_str()) {
                return Err(format!("DUP_ENUM:{name}"));
            }
            if members.is_empty() || members.len() > 65_535 {
                return Err(format!("EMPTY_ENUM:{name}"));
            }
            let mut unique = HashSet::new();
            for member in members {
                if member.is_empty()
                    || member.len() > 128
                    || !member.bytes().all(|b| (0x21..=0x7e).contains(&b))
                {
                    return Err(format!("BAD_ENUM_MEMBER:{name}"));
                }
                if !unique.insert(member.as_str()) {
                    return Err(format!("DUP_ENUM_MEMBER:{name}"));
                }
            }
        }

        if let Some((version, mode)) = &self.compatibility {
            if !valid_semver(version) {
                return Err("BAD_COMPATIBILITY_VERSION".into());
            }
            if *mode > 3 {
                return Err("BAD_COMPATIBILITY_MODE".into());
            }
            let ordering = compare_semver(version, &self.version)
                .ok_or_else(|| "BAD_COMPATIBILITY_VERSION".to_string())?;
            if ordering == Ordering::Greater || (*mode == 0 && ordering != Ordering::Equal) {
                return Err("BAD_COMPATIBILITY_RANGE".into());
            }
        }
        if self.canonical_cbor().len() > MAX_MANIFEST_BYTES {
            return Err("MANIFEST_TOO_LARGE".into());
        }
        Ok(())
    }

    pub fn to_cbor(&self) -> Cbor {
        let mut m = vec![
            (Cbor::U(1), Cbor::Text(self.name.clone())),
            (Cbor::U(2), Cbor::Text(self.version.clone())),
            (Cbor::U(3), Cbor::U(self.primitive)),
            (Cbor::U(4), Cbor::Text(self.publisher.clone())),
            (
                Cbor::U(5),
                Cbor::Array(self.fields.iter().map(|f| f.to_cbor()).collect()),
            ),
            (
                Cbor::U(6),
                Cbor::Map(
                    self.enums
                        .iter()
                        .map(|(k, vs)| {
                            (
                                Cbor::Text(k.clone()),
                                Cbor::Array(vs.iter().map(|x| Cbor::Text(x.clone())).collect()),
                            )
                        })
                        .collect(),
                ),
            ),
        ];
        if let Some((mcv, mode)) = &self.compatibility {
            m.push((
                Cbor::U(7),
                Cbor::Map(vec![
                    (Cbor::U(1), Cbor::Text(mcv.clone())),
                    (Cbor::U(2), Cbor::U(*mode)),
                ]),
            ));
        }
        Cbor::Map(m)
    }

    pub fn canonical_cbor(&self) -> Vec<u8> {
        encode(&self.to_cbor())
    }

    pub fn digest(&self) -> [u8; 32] {
        let mut pre = D_MANIFEST.to_vec();
        pre.extend(self.canonical_cbor());
        sha256(&pre)
    }

    pub fn enum_members(&self, name: &str) -> Option<&Vec<String>> {
        self.enums.iter().find(|(k, _)| k == name).map(|(_, v)| v)
    }
}

fn valid_dotted_name(value: &str) -> bool {
    let mut chars = value.chars();
    if !matches!(chars.next(), Some(c) if c.is_ascii_lowercase()) {
        return false;
    }
    let mut previous_separator = false;
    for c in chars {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            previous_separator = false;
        } else if matches!(c, '.' | '_' | '-') && !previous_separator {
            previous_separator = true;
        } else {
            return false;
        }
    }
    !previous_separator
}

fn valid_field_name(value: &str) -> bool {
    let mut chars = value.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
}

fn valid_semver(value: &str) -> bool {
    if value.is_empty() || value.len() > 64 || !value.is_ascii() {
        return false;
    }
    let (without_build, build) = value
        .split_once('+')
        .map_or((value, None), |(left, right)| (left, Some(right)));
    if build
        .map(|ids| !valid_semver_identifiers(ids, false))
        .unwrap_or(false)
    {
        return false;
    }
    let (core, prerelease) = without_build
        .split_once('-')
        .map_or((without_build, None), |(left, right)| (left, Some(right)));
    if prerelease
        .map(|ids| !valid_semver_identifiers(ids, true))
        .unwrap_or(false)
    {
        return false;
    }
    let mut parts = core.split('.');
    let (Some(major), Some(minor), Some(patch), None) =
        (parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return false;
    };
    [major, minor, patch].iter().all(|part| {
        !part.is_empty()
            && part.bytes().all(|b| b.is_ascii_digit())
            && (*part == "0" || !part.starts_with('0'))
    })
}

fn valid_semver_identifiers(value: &str, reject_numeric_leading_zero: bool) -> bool {
    !value.is_empty()
        && value.split('.').all(|part| {
            !part.is_empty()
                && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
                && (!reject_numeric_leading_zero
                    || !part.bytes().all(|b| b.is_ascii_digit())
                    || part == "0"
                    || !part.starts_with('0'))
        })
}

fn compare_semver(left: &str, right: &str) -> Option<Ordering> {
    if !valid_semver(left) || !valid_semver(right) {
        return None;
    }
    let (left_core, left_pre) = split_semver(left);
    let (right_core, right_pre) = split_semver(right);
    for (a, b) in left_core.split('.').zip(right_core.split('.')) {
        let ordering = a.len().cmp(&b.len()).then_with(|| a.cmp(b));
        if ordering != Ordering::Equal {
            return Some(ordering);
        }
    }
    match (left_pre, right_pre) {
        (None, None) => Some(Ordering::Equal),
        (None, Some(_)) => Some(Ordering::Greater),
        (Some(_), None) => Some(Ordering::Less),
        (Some(a), Some(b)) => {
            let mut ai = a.split('.');
            let mut bi = b.split('.');
            loop {
                match (ai.next(), bi.next()) {
                    (None, None) => return Some(Ordering::Equal),
                    (None, Some(_)) => return Some(Ordering::Less),
                    (Some(_), None) => return Some(Ordering::Greater),
                    (Some(x), Some(y)) => {
                        let x_numeric = x.bytes().all(|byte| byte.is_ascii_digit());
                        let y_numeric = y.bytes().all(|byte| byte.is_ascii_digit());
                        let ordering = match (x_numeric, y_numeric) {
                            (true, true) => x.len().cmp(&y.len()).then_with(|| x.cmp(y)),
                            (true, false) => Ordering::Less,
                            (false, true) => Ordering::Greater,
                            (false, false) => x.cmp(y),
                        };
                        if ordering != Ordering::Equal {
                            return Some(ordering);
                        }
                    }
                }
            }
        }
    }
}

fn split_semver(value: &str) -> (&str, Option<&str>) {
    let without_build = value.split_once('+').map_or(value, |(head, _)| head);
    without_build
        .split_once('-')
        .map_or((without_build, None), |(core, pre)| (core, Some(pre)))
}

fn validate_constraints(field: &Field) -> Result<(), String> {
    let mut keys = HashSet::new();
    for (key, value) in &field.constraints {
        if !keys.insert(*key) {
            return Err(format!("DUP_CONSTRAINT:{}", field.name));
        }
        match *key {
            1 if field.typ != T_STRING && field.typ != T_BYTES => {
                return Err(format!("MAX_LEN_ON_NONLEN_TYPE:{}", type_name(field.typ)));
            }
            2 | 3
                if !matches!(
                    field.typ,
                    T_U32 | T_U64 | T_I32 | T_I64 | T_F32 | T_F64 | T_TIMESTAMP_MS | T_DURATION_MS
                ) =>
            {
                return Err(format!("RANGE_ON_NONNUMERIC:{}", type_name(field.typ)));
            }
            1..=3 => {
                let _ = value;
            }
            _ => return Err(format!("UNKNOWN_CONSTRAINT:{key}")),
        }
    }
    let get = |key| {
        field
            .constraints
            .iter()
            .find(|(k, _)| *k == key)
            .map(|x| x.1)
    };
    if let Some(max_len) = get(1) {
        if max_len < 1 || max_len as u64 > HARD_MAX_LEN {
            return Err(format!("BAD_MAX_LEN:{}", field.name));
        }
    }
    let (min, max) = (get(2), get(3));
    if min.zip(max).map(|(a, b)| a > b).unwrap_or(false) {
        return Err(format!("MIN_GT_MAX:{}", field.name));
    }
    if matches!(field.typ, T_U32 | T_U64 | T_DURATION_MS)
        && min.into_iter().chain(max).any(|v| v < 0)
    {
        return Err(format!("UNSIGNED_RANGE_OUT_OF_DOMAIN:{}", field.name));
    }
    if field.typ == T_U32
        && min
            .into_iter()
            .chain(max)
            .any(|v| v as u64 > u32::MAX as u64)
    {
        return Err(format!("UNSIGNED_RANGE_OUT_OF_DOMAIN:{}", field.name));
    }
    if field.typ == T_I32
        && min
            .into_iter()
            .chain(max)
            .any(|v| v < i32::MIN as i64 || v > i32::MAX as i64)
    {
        return Err(format!("I32_RANGE_OUT_OF_DOMAIN:{}", field.name));
    }
    Ok(())
}

fn type_name(typ: u64) -> &'static str {
    match typ {
        0 => "BOOL",
        1 => "U32",
        2 => "U64",
        3 => "I32",
        4 => "I64",
        5 => "F32",
        6 => "F64",
        7 => "ENUM",
        8 => "STRING",
        9 => "BYTES",
        10 => "TIMESTAMP_MS",
        11 => "ENTITY_REF",
        12 => "OPAQUE_REF",
        13 => "DURATION_MS",
        _ => "UNKNOWN",
    }
}

#[cfg(test)]
mod tests {
    use super::{compare_semver, valid_semver};
    use std::cmp::Ordering;

    #[test]
    fn semver_syntax_is_strict() {
        for valid in [
            "0.0.0",
            "1.2.3",
            "1.0.0-alpha.1",
            "1.0.0+build.7",
            "1.0.0-rc.1+sha.abc",
        ] {
            assert!(valid_semver(valid), "{valid}");
        }
        for invalid in [
            "", "v1.0.0", "1.0", "01.0.0", "1.0.0-", "1.0.0-01", "1.0.0+", "1.0.0++x",
        ] {
            assert!(!valid_semver(invalid), "{invalid}");
        }
    }

    #[test]
    fn semver_precedence_ignores_build_metadata() {
        assert_eq!(
            compare_semver("1.0.0-alpha.2", "1.0.0-alpha.10"),
            Some(Ordering::Less)
        );
        assert_eq!(compare_semver("1.0.0-alpha", "1.0.0"), Some(Ordering::Less));
        assert_eq!(
            compare_semver("1.0.0+left", "1.0.0+right"),
            Some(Ordering::Equal)
        );
    }
}
