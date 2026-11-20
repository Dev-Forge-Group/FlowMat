//! The two character classes the Java tokenizer asks the JDK about, as JDK 21 answers for one UTF-16 code unit.
//!
//! Java decides with `Character.isWhitespace(char)` and `Character.isDigit(char)`, which differ from Rust's
//! `char::is_whitespace` and `char::is_numeric`: Java counts U+001C–U+001F as whitespace and not U+00A0 or U+0085, and
//! counts every Unicode decimal digit (Arabic-Indic, Devanagari, full-width, …) as a digit. The fixture records the
//! JDK's answer for every code unit, and both test suites check their side against it.

/// The zero of each run of ten decimal digits the JDK knows in the Basic Multilingual Plane. The JDK's `digit(c, 10)` is
/// `c - zero` inside each run.
pub const DIGIT_ZEROS: [u16; 37] = [
    0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6, 0x0D66, 0x0DE6,
    0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50, 0x1BB0, 0x1C40,
    0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10,
];

/// `Character.isWhitespace(char)`.
pub fn is_whitespace(unit: u16) -> bool {
    matches!(
        unit,
        0x0009..=0x000D | 0x001C..=0x0020 | 0x1680 | 0x2000..=0x2006 | 0x2008..=0x200A | 0x2028 | 0x2029 | 0x205F | 0x3000
    )
}

/// `Character.digit(char, 10)` for a code unit `Character.isDigit` accepts, or `None`.
pub fn digit(unit: u16) -> Option<u8> {
    if unit < 0x80 {
        return unit
            .checked_sub(u16::from(b'0'))
            .filter(|value| *value < 10)
            .map(|value| value as u8);
    }
    let run = DIGIT_ZEROS.partition_point(|zero| *zero <= unit).checked_sub(1)?;
    let value = unit - DIGIT_ZEROS[run];
    (value < 10).then_some(value as u8)
}

/// `Character.isDigit(char)`.
pub fn is_digit(unit: u16) -> bool {
    digit(unit).is_some()
}

/// `digit` for a character: one outside the Basic Multilingual Plane is never a digit to the JDK's `isDigit(char)`.
pub fn char_digit(character: char) -> Option<u8> {
    u16::try_from(u32::from(character)).ok().and_then(digit)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digits_cover_each_run_of_ten_and_nothing_between() {
        assert_eq!(digit(u16::from(b'7')), Some(7));
        assert_eq!(digit(0x0663), Some(3));
        assert_eq!(digit(0xFF19), Some(9));
        assert_eq!(digit(0x065F), None);
        assert_eq!(digit(0x066A), None);
        assert_eq!(digit(u16::from(b'/')), None);
        assert_eq!(digit(u16::from(b':')), None);
    }

    #[test]
    fn whitespace_is_javas_not_unicodes() {
        assert!(is_whitespace(0x001C));
        assert!(is_whitespace(0x3000));
        assert!(!is_whitespace(0x00A0));
        assert!(!is_whitespace(0x0085));
        assert!(!is_whitespace(0x2007));
        assert!(!is_whitespace(0x202F));
    }
}
