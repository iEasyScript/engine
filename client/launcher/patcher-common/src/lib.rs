//! Patch targets shared by the Linux, Windows and macOS runtime patchers.
//!
//! Everything here is a search pattern plus the transformation applied to what
//! it finds. Nothing here touches memory or the host OS — each platform crate
//! supplies its own region enumeration and write primitive and calls into this.

use memchr::memmem::Finder;

/// Login RSA modulus the client encrypts the login block with, stored as a
/// lowercase hex ASCII string. Only the leading run is matched; the whole string
/// is then overwritten with ours.
pub const LOGIN_MODULUS_PREFIX: &[u8] = b"b2c61c4772bf8882cab71c2b07ccac16";
pub const LOGIN_MODULUS_HEX_LEN: usize = 256;

/// JS5 master-index signature verification key, same storage form as the login
/// modulus but a much longer key.
pub const JS5_MODULUS_PREFIX: &[u8] = b"9230a2ba97fd70ad04b41d730dd4fb93";
pub const JS5_MODULUS_HEX_LEN: usize = 1024;

/// Download-verification key carried by the Jagex `rs3*` launchers. Byte-identical
/// across the three OS launchers, and absent from `rs2client`, so a scan for it is
/// self-selecting: it can only match inside a launcher process.
pub const LAUNCHER_MODULUS_PREFIX: &[u8] = b"a49962fc0737fddcd94c0daf84e5d214";
pub const LAUNCHER_MODULUS_HEX_LEN: usize = 1024;

/// Regex the `rs3*` launchers validate a jav_config `codebase` against. A private
/// server host never matches it, so the launcher refuses the download and reports
/// it as a file-save failure.
pub const CODEBASE_REGEX: &[u8] = b"^https?://[a-z0-9\\-]*\\.?runescape.com(:[0-9]+)?/";

/// Permissive replacement accepting any http(s) codebase. Shorter than the
/// original, so [`relaxed_codebase_regex`] NUL-pads it back to the original length.
pub const CODEBASE_REGEX_REPLACEMENT: &[u8] = b"^https?://.*/";

/// The replacement regex padded to the original's length, terminating the string
/// and leaving no tail of the original behind.
pub fn relaxed_codebase_regex() -> Vec<u8> {
    let mut relaxed = CODEBASE_REGEX_REPLACEMENT.to_vec();
    relaxed.resize(CODEBASE_REGEX.len(), 0u8);
    relaxed
}

// -- HTTP content port ---------------------------------------------------------
//
// `jag::WorldLobbyData::GetHTTPURL` builds the JS5-over-HTTP content URL. In live
// mode it uses a literal port; otherwise it derives one from a world-port base.
// We rewrite the literal so content requests reach our server.
//
// The literal alone is far too common to search for: each toolchain picks a
// different register and operand width, and every one of those encodings occurs
// dozens of times across the image. What actually identifies the site is its
// neighbourhood — the two world-port bases are the other arms of the same
// select, and appear within a few instructions of the literal. Anchoring on them
// rather than on surrounding opcode bytes survives a recompile that changes the
// branch shape, the register allocation, or whether the function got inlined.

const LIVE_HTTP_PORT_IMM: u8 = 80;
const WORLD_PORT_BASE: u16 = 7000;
const WORLD_PORT_BASE_ALT: u16 = 12000;
const ANCHOR_WINDOW: usize = 64;

struct PortEncoding {
    bytes: &'static [u8],
    imm_offset: usize,
    imm_width: usize,
}

const PORT_ENCODINGS: &[PortEncoding] = &[
    PortEncoding { bytes: &[0x41, 0xb8, LIVE_HTTP_PORT_IMM, 0x00, 0x00, 0x00], imm_offset: 2, imm_width: 4 },
    PortEncoding { bytes: &[0x66, 0xbe, LIVE_HTTP_PORT_IMM, 0x00], imm_offset: 2, imm_width: 2 },
    PortEncoding { bytes: &[0x66, 0xb8, LIVE_HTTP_PORT_IMM, 0x00], imm_offset: 2, imm_width: 2 },
    PortEncoding { bytes: &[0xb8, LIVE_HTTP_PORT_IMM, 0x00, 0x00, 0x00], imm_offset: 1, imm_width: 4 },
    PortEncoding { bytes: &[0xb9, LIVE_HTTP_PORT_IMM, 0x00, 0x00, 0x00], imm_offset: 1, imm_width: 4 },
    PortEncoding { bytes: &[0xba, LIVE_HTTP_PORT_IMM, 0x00, 0x00, 0x00], imm_offset: 1, imm_width: 4 },
    PortEncoding { bytes: &[0xbe, LIVE_HTTP_PORT_IMM, 0x00, 0x00, 0x00], imm_offset: 1, imm_width: 4 },
];

/// A confirmed live-mode HTTP port literal, located relative to the start of the
/// scanned slice.
pub struct PortSite {
    pub offset: usize,
    pub imm_offset: usize,
    pub imm_width: usize,
}

impl PortSite {
    /// Offset of the immediate itself — what the caller writes to.
    pub fn patch_offset(&self) -> usize {
        self.offset + self.imm_offset
    }
}

/// Every live-mode HTTP port literal in `haystack`, filtered down to those whose
/// neighbourhood carries both world-port bases.
///
/// Callers must treat an empty result as a hard failure: a client whose port
/// literal was not rewritten fetches content from Jagex.
pub fn find_port_sites(haystack: &[u8]) -> Vec<PortSite> {
    let mut sites: Vec<PortSite> = Vec::new();

    for encoding in PORT_ENCODINGS {
        let finder = Finder::new(encoding.bytes);
        for offset in finder.find_iter(haystack) {
            if is_tail_of_prefixed_encoding(haystack, offset, encoding) {
                continue;
            }
            if !has_world_port_anchors(haystack, offset) {
                continue;
            }
            sites.push(PortSite {
                offset,
                imm_offset: encoding.imm_offset,
                imm_width: encoding.imm_width,
            });
        }
    }

    sites.sort_by_key(PortSite::patch_offset);
    sites.dedup_by_key(|site| site.patch_offset());
    sites
}

fn is_instruction_prefix(byte: u8) -> bool {
    matches!(byte, 0x40..=0x4f | 0x66)
}

/// True when this match is really the tail of a longer, prefixed encoding that
/// another entry in the table already covers — patching it would land the
/// immediate correctly but report a duplicate site.
fn is_tail_of_prefixed_encoding(haystack: &[u8], offset: usize, encoding: &PortEncoding) -> bool {
    if offset == 0 || is_instruction_prefix(encoding.bytes[0]) {
        return false;
    }
    is_instruction_prefix(haystack[offset - 1])
}

fn has_world_port_anchors(haystack: &[u8], offset: usize) -> bool {
    let start = offset.saturating_sub(ANCHOR_WINDOW);
    let end = offset.saturating_add(ANCHOR_WINDOW).min(haystack.len());
    let window = &haystack[start..end];
    contains(window, &WORLD_PORT_BASE.to_le_bytes())
        && contains(window, &WORLD_PORT_BASE_ALT.to_le_bytes())
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    Finder::new(needle).find(haystack).is_some()
}

/// The port as a little-endian immediate, widest form. Callers truncate to the
/// site's own `imm_width`.
pub fn port_immediate(port: u16) -> [u8; 4] {
    let mut immediate = [0u8; 4];
    immediate[..2].copy_from_slice(&port.to_le_bytes());
    immediate
}

// -- hex helpers ---------------------------------------------------------------

/// Clean a hex modulus string (strip `0x`/`0X`, trim, lowercase) and left-pad
/// with `'0'` to exactly `len` chars. `None` if the cleaned hex is longer.
pub fn pad_modulus_hex(hex: &str, len: usize) -> Option<String> {
    let trimmed = hex.trim();
    let body = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
        .unwrap_or(trimmed)
        .to_ascii_lowercase();
    if body.len() > len {
        return None;
    }
    let mut padded = "0".repeat(len - body.len());
    padded.push_str(&body);
    Some(padded)
}

pub fn hex_to_bytes(hex: &str) -> Option<Vec<u8>> {
    let trimmed = hex.trim();
    let body = trimmed
        .strip_prefix("0x")
        .or_else(|| trimmed.strip_prefix("0X"))
        .unwrap_or(trimmed);
    if body.len() % 2 != 0 {
        return None;
    }
    let mut bytes = Vec::with_capacity(body.len() / 2);
    for pair in (0..body.len()).step_by(2) {
        bytes.push(u8::from_str_radix(&body[pair..pair + 2], 16).ok()?);
    }
    Some(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn anchors() -> Vec<u8> {
        let mut noise = vec![0x90u8; 16];
        noise.extend_from_slice(&WORLD_PORT_BASE.to_le_bytes());
        noise.extend_from_slice(&[0x90; 8]);
        noise.extend_from_slice(&WORLD_PORT_BASE_ALT.to_le_bytes());
        noise
    }

    #[test]
    fn port_literal_without_anchors_is_rejected() {
        let mut image = vec![0x90u8; 64];
        image.extend_from_slice(&[0xb8, 80, 0x00, 0x00, 0x00]);
        image.extend_from_slice(&[0x90; 64]);
        assert!(find_port_sites(&image).is_empty());
    }

    #[test]
    fn anchored_port_literal_is_found_with_its_immediate_offset() {
        let mut image = anchors();
        let literal_at = image.len();
        image.extend_from_slice(&[0x41, 0xb8, 80, 0x00, 0x00, 0x00]);
        image.extend_from_slice(&anchors());

        let sites = find_port_sites(&image);
        assert_eq!(sites.len(), 1);
        assert_eq!(sites[0].offset, literal_at);
        assert_eq!(sites[0].imm_width, 4);
        assert_eq!(sites[0].patch_offset(), literal_at + 2);
    }

    #[test]
    fn rex_prefixed_literal_is_reported_once() {
        let mut image = anchors();
        image.extend_from_slice(&[0x41, 0xb8, 80, 0x00, 0x00, 0x00]);
        image.extend_from_slice(&anchors());
        assert_eq!(find_port_sites(&image).len(), 1);
    }

    #[test]
    fn sixteen_bit_literal_yields_a_two_byte_immediate() {
        let mut image = anchors();
        let literal_at = image.len();
        image.extend_from_slice(&[0x66, 0xbe, 80, 0x00]);
        image.extend_from_slice(&anchors());

        let sites = find_port_sites(&image);
        assert_eq!(sites.len(), 1);
        assert_eq!(sites[0].imm_width, 2);
        assert_eq!(sites[0].patch_offset(), literal_at + 2);
    }

    #[test]
    fn multiple_inlined_sites_are_all_reported() {
        let mut image = anchors();
        image.extend_from_slice(&[0x66, 0xbe, 80, 0x00]);
        image.extend_from_slice(&anchors());
        image.extend_from_slice(&anchors());
        image.extend_from_slice(&[0x66, 0xbe, 80, 0x00]);
        image.extend_from_slice(&anchors());
        assert_eq!(find_port_sites(&image).len(), 2);
    }

    #[test]
    fn port_immediate_is_little_endian_zero_extended() {
        assert_eq!(port_immediate(8829), [0x7d, 0x22, 0x00, 0x00]);
        assert_eq!(&port_immediate(8829)[..2], &[0x7d, 0x22]);
    }

    #[test]
    fn relaxed_regex_matches_original_length_and_is_terminated() {
        let relaxed = relaxed_codebase_regex();
        assert_eq!(relaxed.len(), CODEBASE_REGEX.len());
        assert!(relaxed.starts_with(CODEBASE_REGEX_REPLACEMENT));
        assert_eq!(relaxed[CODEBASE_REGEX_REPLACEMENT.len()], 0);
    }

    #[test]
    fn pad_modulus_hex_normalises_and_left_pads() {
        assert_eq!(pad_modulus_hex("0xDEADBEEF", 16).unwrap(), "00000000deadbeef");
        assert_eq!(pad_modulus_hex("ABCD", 4).unwrap(), "abcd");
        assert!(pad_modulus_hex("deadbeef", 4).is_none());
    }

    #[test]
    fn hex_to_bytes_rejects_malformed_input() {
        assert_eq!(hex_to_bytes("deadbeef"), Some(vec![0xde, 0xad, 0xbe, 0xef]));
        assert_eq!(hex_to_bytes("0xDEADBEEF"), Some(vec![0xde, 0xad, 0xbe, 0xef]));
        assert_eq!(hex_to_bytes("abc"), None);
        assert_eq!(hex_to_bytes("zz"), None);
    }

    #[test]
    fn key_patterns_have_the_expected_shape() {
        assert_eq!(LOGIN_MODULUS_PREFIX.len(), 32);
        assert_eq!(JS5_MODULUS_PREFIX.len(), 32);
        assert_eq!(LAUNCHER_MODULUS_PREFIX.len(), 32);
        assert!(CODEBASE_REGEX_REPLACEMENT.len() < CODEBASE_REGEX.len());
    }
}
