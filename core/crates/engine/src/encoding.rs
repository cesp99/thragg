//! Bytes on disk to text in a buffer, and back — Zed's
//! `language/src/file_content.rs`, with its detector trimmed.
//!
//! A buffer holds UTF-8 with `\n` line breaks and nothing else; the file it
//! came from may hold anything. So the file's *shape* — its encoding, whether
//! it opened with a byte-order mark, which line break it uses — is recorded
//! next to the buffer (see `file::FileState`) and put back on save, which is
//! what lets a `windows-1252` file with `\r\n` breaks round-trip through an
//! editor that never saw either.
//!
//! The line-ending half is the vendored `text::LineEnding`, Zed's own
//! (`text/src/text.rs:3581-3667`): detect on the first line break, normalise
//! `\r\n` and lone `\r` to `\n`, re-apply on the way out.

use encoding_rs::{Encoding, UTF_8, UTF_16BE, UTF_16LE};
pub use text::LineEnding;

/// What a file's bytes turned out to be.
pub struct DecodedText {
    pub text: String,
    pub encoding: &'static Encoding,
    /// The bytes opened with a byte-order mark, which the text no longer
    /// carries and the save must put back.
    pub has_bom: bool,
}

/// Decode a file's bytes, guessing the encoding from the bytes alone.
///
/// The order is Zed's (`file_content.rs:13-46`): a byte-order mark decides
/// outright; then a null-byte skew that says UTF-16 without one; then the
/// bytes are tried as UTF-8; and only bytes that are none of those fall back
/// to `windows-1252` — the one guess that can never fail, since every byte is
/// a character in it. Zed's fallback runs `chardetng` over the file instead;
/// Western single-byte text is what an Android editor actually meets, and a
/// wrong guess is one tap from the encoding picker either way.
pub fn decode_text(bytes: Vec<u8>) -> DecodedText {
    if let Some((encoding, _bom_len)) = Encoding::for_bom(&bytes) {
        let (text, _) = encoding.decode_with_bom_removal(&bytes);
        return DecodedText {
            text: text.into_owned(),
            encoding,
            has_bom: true,
        };
    }

    let encoding = match analyze_byte_content(&bytes) {
        ByteContent::Utf16Le => UTF_16LE,
        ByteContent::Utf16Be => UTF_16BE,
        ByteContent::Unknown => match String::from_utf8(bytes) {
            Ok(text) => {
                return DecodedText {
                    text,
                    encoding: UTF_8,
                    has_bom: false,
                };
            }
            Err(error) => return decode_with(error.into_bytes(), encoding_rs::WINDOWS_1252),
        },
    };
    decode_with(bytes, encoding)
}

/// Decode bytes as one named encoding, the way the encoding picker asks for
/// it — Zed's `Buffer::reload_impl` with `force_encoding` set
/// (`language/src/buffer.rs:1634-1652`). A Unicode encoding still honours a
/// byte-order mark, and reports it, because the mark *is* the encoding's
/// own; any other encoding takes the bytes as they are.
pub fn decode_text_as(bytes: Vec<u8>, encoding: &'static Encoding) -> DecodedText {
    let is_unicode = encoding == UTF_8 || encoding == UTF_16LE || encoding == UTF_16BE;
    if !is_unicode {
        let (text, _had_errors) = encoding.decode_without_bom_handling(&bytes);
        return DecodedText {
            text: text.into_owned(),
            encoding,
            has_bom: false,
        };
    }
    let (text, used, _had_errors) = encoding.decode(&bytes);
    let has_bom = if used == UTF_8 {
        bytes.starts_with(&[0xEF, 0xBB, 0xBF])
    } else if used == UTF_16LE {
        bytes.starts_with(&[0xFF, 0xFE])
    } else if used == UTF_16BE {
        bytes.starts_with(&[0xFE, 0xFF])
    } else {
        false
    };
    DecodedText {
        text: text.into_owned(),
        encoding: used,
        has_bom,
    }
}

fn decode_with(bytes: Vec<u8>, encoding: &'static Encoding) -> DecodedText {
    let (text, _, _) = encoding.decode(&bytes);
    DecodedText {
        text: text.into_owned(),
        encoding,
        has_bom: false,
    }
}

/// Text to bytes in `encoding`, with a byte-order mark in front when the file
/// had one — Zed's `encode_text` (`file_content.rs:48-83`). UTF-16 is written
/// by hand because `encoding_rs`, following WHATWG, *encodes* the UTF-16
/// labels as UTF-8.
pub fn encode_text(text: &str, encoding: &'static Encoding, has_bom: bool) -> Vec<u8> {
    if encoding == UTF_8 && !has_bom {
        return text.as_bytes().to_vec();
    }
    if encoding == UTF_16BE {
        let mut bytes = Vec::with_capacity(text.len() * 2 + 2);
        if has_bom {
            bytes.extend_from_slice(&[0xFE, 0xFF]);
        }
        bytes.extend(text.encode_utf16().flat_map(u16::to_be_bytes));
        return bytes;
    }
    if encoding == UTF_16LE {
        let mut bytes = Vec::with_capacity(text.len() * 2 + 2);
        if has_bom {
            bytes.extend_from_slice(&[0xFF, 0xFE]);
        }
        bytes.extend(text.encode_utf16().flat_map(u16::to_le_bytes));
        return bytes;
    }
    let (encoded, _, _) = encoding.encode(text);
    if has_bom && encoding == UTF_8 {
        let mut bytes = Vec::with_capacity(encoded.len() + 3);
        bytes.extend_from_slice(&[0xEF, 0xBB, 0xBF]);
        bytes.extend_from_slice(&encoded);
        bytes
    } else {
        encoded.into_owned()
    }
}

/// The encodings the picker offers, sorted by name — Zed's
/// `available_encodings` (`encoding_selector.rs:153-207`), which leaves out
/// `REPLACEMENT` and `X_USER_DEFINED` for the reasons it gives there: one is
/// for decoding errors, the other for binary data.
pub fn available_encodings() -> Vec<&'static Encoding> {
    let mut encodings = vec![
        // Unicode
        encoding_rs::UTF_8,
        encoding_rs::UTF_16LE,
        encoding_rs::UTF_16BE,
        // Japanese
        encoding_rs::SHIFT_JIS,
        encoding_rs::EUC_JP,
        encoding_rs::ISO_2022_JP,
        // Chinese
        encoding_rs::GBK,
        encoding_rs::GB18030,
        encoding_rs::BIG5,
        // Korean
        encoding_rs::EUC_KR,
        // Windows / single-byte series
        encoding_rs::WINDOWS_1252,
        encoding_rs::WINDOWS_1250,
        encoding_rs::WINDOWS_1251,
        encoding_rs::WINDOWS_1253,
        encoding_rs::WINDOWS_1254,
        encoding_rs::WINDOWS_1255,
        encoding_rs::WINDOWS_1256,
        encoding_rs::WINDOWS_1257,
        encoding_rs::WINDOWS_1258,
        encoding_rs::WINDOWS_874,
        // ISO-8859 series
        encoding_rs::ISO_8859_2,
        encoding_rs::ISO_8859_3,
        encoding_rs::ISO_8859_4,
        encoding_rs::ISO_8859_5,
        encoding_rs::ISO_8859_6,
        encoding_rs::ISO_8859_7,
        encoding_rs::ISO_8859_8,
        encoding_rs::ISO_8859_8_I,
        encoding_rs::ISO_8859_10,
        encoding_rs::ISO_8859_13,
        encoding_rs::ISO_8859_14,
        encoding_rs::ISO_8859_15,
        encoding_rs::ISO_8859_16,
        // Cyrillic and legacy
        encoding_rs::KOI8_R,
        encoding_rs::KOI8_U,
        encoding_rs::IBM866,
        encoding_rs::MACINTOSH,
        encoding_rs::X_MAC_CYRILLIC,
    ];
    encodings.sort_by_key(|encoding| encoding.name());
    encodings
}

/// The encoding behind a name the picker handed back, by WHATWG label
/// (`"windows-1252"`, `"UTF-16LE"`), and only one of the offered ones.
pub fn encoding_named(name: &str) -> Option<&'static Encoding> {
    let encoding = Encoding::for_label(name.as_bytes())?;
    available_encodings().contains(&encoding).then_some(encoding)
}

#[derive(Debug, PartialEq)]
enum ByteContent {
    Utf16Le,
    Utf16Be,
    Unknown,
}

/// How many bytes of a file the UTF-16 sniff reads (`file_content.rs:6`).
const FILE_ANALYSIS_BYTES: usize = 1024;

/// UTF-16 without a byte-order mark, told by where its null bytes fall — the
/// half of Zed's `analyze_byte_content` (`file_content.rs:105-160`) that
/// distinguishes text from text. Zed's other half refuses binary files; the
/// engine has no such refusal today, and adding one is a separate change
/// from reading text correctly.
fn analyze_byte_content(bytes: &[u8]) -> ByteContent {
    if bytes.len() < 2 {
        return ByteContent::Unknown;
    }
    let limit = bytes.len().min(FILE_ANALYSIS_BYTES);
    let mut even_null_count = 0usize;
    let mut odd_null_count = 0usize;
    for (i, &byte) in bytes[..limit].iter().enumerate() {
        if byte == 0 {
            if i % 2 == 0 {
                even_null_count += 1;
            } else {
                odd_null_count += 1;
            }
        }
    }
    let total_null_count = even_null_count + odd_null_count;
    if total_null_count < limit / 16 {
        return ByteContent::Unknown;
    }
    let sample = &bytes[..limit];
    // UTF-16BE ASCII is `[0x00, char]` — nulls on even offsets; LE the reverse.
    if even_null_count > odd_null_count * 4 && is_plausible_utf16_text(sample, false) {
        return ByteContent::Utf16Be;
    }
    if odd_null_count > even_null_count * 4 && is_plausible_utf16_text(sample, true) {
        return ByteContent::Utf16Le;
    }
    ByteContent::Unknown
}

/// Null skew alone is not enough — PCM audio has it too — so decode the
/// sample as UTF-16 and reject it if too many units are control characters
/// or unpaired surrogates, or too few are letters (`file_content.rs:184-249`).
fn is_plausible_utf16_text(bytes: &[u8], little_endian: bool) -> bool {
    let mut suspicious_count = 0usize;
    let mut word_like_count = 0usize;
    let mut total = 0usize;
    let mut i = 0;
    while let Some(code_unit) = read_u16(bytes, i, little_endian) {
        total += 1;
        match code_unit {
            0x0009 | 0x000A | 0x000C | 0x000D => {}
            0x0020 | 0x0030..=0x0039 | 0x0041..=0x005A | 0x0061..=0x007A => {
                word_like_count += 1;
            }
            0x0000..=0x001F | 0x007F..=0x009F | 0xFFFE | 0xFFFF => suspicious_count += 1,
            0xD800..=0xDBFF => {
                let has_low_surrogate = read_u16(bytes, i + 2, little_endian)
                    .is_some_and(|next| (0xDC00..=0xDFFF).contains(&next));
                if has_low_surrogate {
                    total += 1;
                    word_like_count += 2;
                    i += 2;
                } else {
                    suspicious_count += 1;
                }
            }
            0xDC00..=0xDFFF => suspicious_count += 1,
            0x0100.. => word_like_count += 1,
            _ => {}
        }
        i += 2;
    }
    if total == 0 {
        return false;
    }
    let low_control_ratio = suspicious_count * 100 < total * 2;
    let enough_word_chars = word_like_count * 100 >= total * 30;
    low_control_ratio && enough_word_chars
}

fn read_u16(bytes: &[u8], offset: usize, little_endian: bool) -> Option<u16> {
    let pair = [*bytes.get(offset)?, *bytes.get(offset + 1)?];
    Some(if little_endian {
        u16::from_le_bytes(pair)
    } else {
        u16::from_be_bytes(pair)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_utf8_is_utf8_without_a_mark() {
        let decoded = decode_text(b"fn main() {}\n".to_vec());
        assert_eq!(decoded.encoding, UTF_8);
        assert!(!decoded.has_bom);
        assert_eq!(decoded.text, "fn main() {}\n");
    }

    #[test]
    fn a_byte_order_mark_is_stripped_and_put_back() {
        for encoding in [UTF_8, UTF_16LE, UTF_16BE] {
            let bytes = encode_text("Hello, мир\n", encoding, true);
            let decoded = decode_text(bytes.clone());
            assert_eq!(decoded.text, "Hello, мир\n");
            assert_eq!(decoded.encoding, encoding);
            assert!(decoded.has_bom);
            assert_eq!(encode_text(&decoded.text, decoded.encoding, true), bytes);
        }
    }

    #[test]
    fn bytes_that_are_not_utf8_fall_back_to_windows_1252() {
        // "café" in Latin-1: the é is a lone 0xE9, which no UTF-8 decoder takes.
        let bytes = b"caf\xE9\n".to_vec();
        let decoded = decode_text(bytes.clone());
        assert_eq!(decoded.encoding, encoding_rs::WINDOWS_1252);
        assert_eq!(decoded.text, "café\n");
        assert_eq!(encode_text(&decoded.text, decoded.encoding, false), bytes);
    }

    #[test]
    fn utf16_without_a_mark_is_told_by_its_nulls() {
        let text = "let answer = 42;\nlet other = answer + 1;\n";
        let le = encode_text(text, UTF_16LE, false);
        let decoded = decode_text(le);
        assert_eq!(decoded.encoding, UTF_16LE);
        assert_eq!(decoded.text, text);
        let be = encode_text(text, UTF_16BE, false);
        assert_eq!(decode_text(be).encoding, UTF_16BE);
    }

    #[test]
    fn reinterpreting_takes_the_named_encoding_at_its_word() {
        // The same byte is é in windows-1252 and й in windows-1251.
        let bytes = b"\xE9".to_vec();
        assert_eq!(
            decode_text_as(bytes.clone(), encoding_rs::WINDOWS_1251).text,
            "й"
        );
        // A UTF-8 mark is still recognised when UTF-8 is what was asked for.
        let marked = decode_text_as(b"\xEF\xBB\xBFhi".to_vec(), UTF_8);
        assert!(marked.has_bom);
        assert_eq!(marked.text, "hi");
    }

    #[test]
    fn only_offered_encodings_answer_to_their_names() {
        assert_eq!(encoding_named("windows-1252"), Some(encoding_rs::WINDOWS_1252));
        assert_eq!(encoding_named("UTF-16LE"), Some(UTF_16LE));
        assert_eq!(encoding_named("latin1"), Some(encoding_rs::WINDOWS_1252));
        assert_eq!(encoding_named("replacement"), None);
        assert_eq!(encoding_named("x-user-defined"), None);
        assert_eq!(encoding_named("no such thing"), None);
    }
}
