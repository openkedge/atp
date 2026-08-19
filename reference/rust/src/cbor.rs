//! Deterministic CBOR (RFC 8949 §4.2.1) — the minimal subset needed for ATP
//! schema manifests. ATP-0002 §6.

pub enum Cbor {
    U(u64),
    I(i64),
    Bool(bool),
    Text(String),
    Bytes(Vec<u8>),
    Array(Vec<Cbor>),
    Map(Vec<(Cbor, Cbor)>),
}

fn head(major: u8, n: u64) -> Vec<u8> {
    let mt = major << 5;
    if n < 24 {
        vec![mt | n as u8]
    } else if n < 0x100 {
        vec![mt | 24, n as u8]
    } else if n < 0x10000 {
        let mut v = vec![mt | 25];
        v.extend_from_slice(&(n as u16).to_be_bytes());
        v
    } else if n < 0x1_0000_0000 {
        let mut v = vec![mt | 26];
        v.extend_from_slice(&(n as u32).to_be_bytes());
        v
    } else {
        let mut v = vec![mt | 27];
        v.extend_from_slice(&n.to_be_bytes());
        v
    }
}

pub fn encode(c: &Cbor) -> Vec<u8> {
    match c {
        Cbor::U(n) => head(0, *n),
        Cbor::I(n) => {
            if *n < 0 {
                head(1, (-1 - *n) as u64)
            } else {
                head(0, *n as u64)
            }
        }
        Cbor::Bool(b) => vec![if *b { 0xf5 } else { 0xf4 }],
        Cbor::Text(s) => {
            let mut v = head(3, s.len() as u64);
            v.extend_from_slice(s.as_bytes());
            v
        }
        Cbor::Bytes(b) => {
            let mut v = head(2, b.len() as u64);
            v.extend_from_slice(b);
            v
        }
        Cbor::Array(items) => {
            let mut v = head(4, items.len() as u64);
            for it in items {
                v.extend(encode(it));
            }
            v
        }
        Cbor::Map(entries) => {
            // RFC 8949 §4.2.1: sort by bytewise lexicographic order of encoded key.
            let mut enc: Vec<(Vec<u8>, Vec<u8>)> = entries
                .iter()
                .map(|(k, val)| (encode(k), encode(val)))
                .collect();
            enc.sort_by(|a, b| a.0.cmp(&b.0));
            let mut v = head(5, enc.len() as u64);
            for (k, val) in enc {
                v.extend(k);
                v.extend(val);
            }
            v
        }
    }
}
