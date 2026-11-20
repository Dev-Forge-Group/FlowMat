//! An exact decimal number, for comparing only. Java compares two numbers with `new BigDecimal(x.toString())` and
//! `compareTo`, which orders by value and ignores scale: `2`, `2.0` and `2.00` are equal, and nothing is rounded.

use std::cmp::Ordering;

use crate::chars;

/// A decimal number as `±0.d₁d₂…dₙ × 10^point`, with no leading or trailing zero digit, so that equal numbers are equal
/// values. Zero has no digits and no sign.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Decimal {
    negative: bool,
    digits: Vec<u8>,
    point: i64,
}

impl Decimal {
    /// Reads a number the way `new BigDecimal(String)` does: an optional sign, digits with an optional decimal point, and
    /// an optional exponent. A digit is any character `Character.isDigit` accepts, so `١٢.٥` is 12.5. `None` where Java
    /// throws `NumberFormatException`, including an exponent or a scale outside a Java `int`.
    pub fn parse(text: &str) -> Option<Decimal> {
        let mut characters = text.chars().peekable();
        let negative = match characters.peek() {
            Some('-') => {
                characters.next();
                true
            }
            Some('+') => {
                characters.next();
                false
            }
            _ => false,
        };
        let mut digits = Vec::new();
        let mut fraction: i64 = 0;
        let mut seen_point = false;
        let mut exponent: i64 = 0;
        while let Some(character) = characters.next() {
            if let Some(digit) = chars::char_digit(character) {
                digits.push(digit);
                if seen_point {
                    fraction += 1;
                }
            } else if character == '.' && !seen_point {
                seen_point = true;
            } else if character == 'e' || character == 'E' {
                exponent = parse_exponent(characters.by_ref().collect::<String>().as_str())?;
                break;
            } else {
                return None;
            }
        }
        if digits.is_empty() {
            return None;
        }
        // Java keeps the scale (fraction digits minus the exponent) in an int.
        let scale = fraction - exponent;
        if scale > i64::from(i32::MAX) || scale < i64::from(i32::MIN) {
            return None;
        }
        Some(Decimal::normalised(negative, digits, -scale))
    }

    /// `digits × 10^exponent`, reduced so that equal numbers have one form.
    fn normalised(negative: bool, mut digits: Vec<u8>, exponent: i64) -> Decimal {
        let leading = digits.iter().take_while(|digit| **digit == 0).count();
        digits.drain(..leading);
        // d₁…dₙ × 10^exponent = 0.d₁…dₙ × 10^(n + exponent), and dropping trailing zeros after the point changes nothing.
        let point = exponent + digits.len() as i64;
        while digits.last() == Some(&0) {
            digits.pop();
        }
        if digits.is_empty() {
            return Decimal {
                negative: false,
                digits,
                point: 0,
            };
        }
        Decimal {
            negative,
            digits,
            point,
        }
    }

    fn signum(&self) -> i8 {
        match (self.digits.is_empty(), self.negative) {
            (true, _) => 0,
            (false, true) => -1,
            (false, false) => 1,
        }
    }
}

/// Java reads the exponent into an int: an optional sign and at least one digit.
fn parse_exponent(text: &str) -> Option<i64> {
    let (negative, digits) = match text.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, text.strip_prefix('+').unwrap_or(text)),
    };
    if digits.is_empty() {
        return None;
    }
    let mut value: i64 = 0;
    for character in digits.chars() {
        value = value * 10 + i64::from(chars::char_digit(character)?);
        if value > i64::from(i32::MAX) + 1 {
            return None;
        }
    }
    let value = if negative { -value } else { value };
    (value >= i64::from(i32::MIN) && value <= i64::from(i32::MAX)).then_some(value)
}

impl Ord for Decimal {
    fn cmp(&self, other: &Decimal) -> Ordering {
        let sign = self.signum().cmp(&other.signum());
        if sign != Ordering::Equal || self.signum() == 0 {
            return sign;
        }
        let magnitude = self
            .point
            .cmp(&other.point)
            .then_with(|| self.digits.cmp(&other.digits));
        if self.negative { magnitude.reverse() } else { magnitude }
    }
}

impl PartialOrd for Decimal {
    fn partial_cmp(&self, other: &Decimal) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn number(text: &str) -> Decimal {
        Decimal::parse(text).unwrap_or_else(|| panic!("{text} is a number"))
    }

    #[test]
    fn equal_values_are_equal_whatever_their_scale() {
        assert_eq!(number("2"), number("2.00"));
        assert_eq!(number("-0.0"), number("0"));
        assert_eq!(number("1E+3"), number("1000"));
        assert_eq!(number("0012.50"), number("12.5"));
        assert_eq!(number("\u{0661}\u{0662}.\u{0665}"), number("12.5"));
    }

    #[test]
    fn orders_by_value() {
        let ordered = [
            "-1000", "-2.5", "-2", "-0.001", "0", "0.001", "0.1", "0.12", "2", "2.5", "10", "1e3",
        ];
        for pair in ordered.windows(2) {
            assert!(number(pair[0]) < number(pair[1]), "{} < {}", pair[0], pair[1]);
        }
        assert!(number("12345678901234567890.123") > number("12345678901234567890.12"));
    }

    #[test]
    fn refuses_what_big_decimal_refuses() {
        for text in [
            "",
            "-",
            ".",
            "1e",
            "1e+",
            "1.2.3",
            "1x",
            "--1",
            "1e2147483648",
            "NaN",
            "Infinity",
        ] {
            assert_eq!(Decimal::parse(text), None, "{text}");
        }
        assert!(Decimal::parse("1e2147483647").is_some());
        assert!(
            Decimal::parse("1e-2147483648").is_none(),
            "the scale would not fit an int"
        );
    }
}
