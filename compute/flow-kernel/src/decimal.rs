//! An exact decimal number with a scale, as Java's `BigDecimal` keeps one: `2.50` is 250 at scale 2 and prints as
//! `2.50`. Only what the flow's meaning uses is here: comparing, adding, subtracting, multiplying, rounding half up to
//! a scale, dropping trailing zeros and printing without an exponent.

use std::cmp::Ordering;

/// `±unscaled × 10^-scale`. `digits` are the unscaled value's decimal digits, most significant first, with no leading
/// zero; zero has no digits and is never negative.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decimal {
    negative: bool,
    digits: Vec<u8>,
    scale: i64,
}

impl Decimal {
    pub fn zero() -> Decimal {
        Decimal::new(false, Vec::new(), 0)
    }

    pub fn from_int(value: i64) -> Decimal {
        let digits = value
            .unsigned_abs()
            .to_string()
            .bytes()
            .map(|byte| byte - b'0')
            .collect();
        Decimal::new(value < 0, digits, 0)
    }

    fn new(negative: bool, mut digits: Vec<u8>, scale: i64) -> Decimal {
        let leading = digits.iter().take_while(|digit| **digit == 0).count();
        digits.drain(..leading);
        Decimal {
            negative: negative && !digits.is_empty(),
            digits,
            scale,
        }
    }

    /// A JSON number's text, as Jackson reads it into a `BigDecimal`: `10` has scale 0, `2.50` scale 2, `1E+2` scale -2.
    pub fn parse(text: &str) -> Option<Decimal> {
        let (negative, rest) = match text.strip_prefix('-') {
            Some(rest) => (true, rest),
            None => (false, text.strip_prefix('+').unwrap_or(text)),
        };
        let (mantissa, exponent) = match rest.find(['e', 'E']) {
            Some(at) => (&rest[..at], rest[at + 1..].parse::<i64>().ok()?),
            None => (rest, 0),
        };
        let (whole, fraction) = match mantissa.split_once('.') {
            Some((whole, fraction)) => (whole, fraction),
            None => (mantissa, ""),
        };
        if whole.is_empty() && fraction.is_empty() {
            return None;
        }
        let mut digits = Vec::with_capacity(whole.len() + fraction.len());
        for byte in whole.bytes().chain(fraction.bytes()) {
            if !byte.is_ascii_digit() {
                return None;
            }
            digits.push(byte - b'0');
        }
        Some(Decimal::new(negative, digits, fraction.len() as i64 - exponent))
    }

    pub fn signum(&self) -> i8 {
        match (self.digits.is_empty(), self.negative) {
            (true, _) => 0,
            (false, true) => -1,
            (false, false) => 1,
        }
    }

    pub fn scale(&self) -> i64 {
        self.scale
    }

    pub fn negate(&self) -> Decimal {
        Decimal::new(!self.negative, self.digits.clone(), self.scale)
    }

    /// The unscaled digits at a larger or equal scale.
    fn digits_at(&self, scale: i64) -> Vec<u8> {
        let mut digits = self.digits.clone();
        digits.resize(digits.len() + (scale - self.scale) as usize, 0);
        digits
    }

    pub fn add(&self, other: &Decimal) -> Decimal {
        let scale = self.scale.max(other.scale);
        let (left, right) = (self.digits_at(scale), other.digits_at(scale));
        if self.negative == other.negative {
            return Decimal::new(self.negative, add_digits(&left, &right), scale);
        }
        match compare_digits(&left, &right) {
            Ordering::Equal => Decimal::new(false, Vec::new(), scale),
            Ordering::Greater => Decimal::new(self.negative, subtract_digits(&left, &right), scale),
            Ordering::Less => Decimal::new(other.negative, subtract_digits(&right, &left), scale),
        }
    }

    pub fn subtract(&self, other: &Decimal) -> Decimal {
        self.add(&other.negate())
    }

    pub fn multiply(&self, other: &Decimal) -> Decimal {
        if self.digits.is_empty() || other.digits.is_empty() {
            return Decimal::new(false, Vec::new(), self.scale + other.scale);
        }
        let mut product = vec![0u32; self.digits.len() + other.digits.len()];
        for (left_at, left) in self.digits.iter().rev().enumerate() {
            for (right_at, right) in other.digits.iter().rev().enumerate() {
                product[left_at + right_at] += u32::from(*left) * u32::from(*right);
            }
        }
        let mut carry = 0;
        for place in product.iter_mut() {
            let total = *place + carry;
            *place = total % 10;
            carry = total / 10;
        }
        let digits = product.iter().rev().map(|digit| *digit as u8).collect();
        Decimal::new(self.negative != other.negative, digits, self.scale + other.scale)
    }

    /// `setScale(scale, RoundingMode.HALF_UP)`: away from zero when the first dropped digit is 5 or more.
    pub fn set_scale_half_up(&self, scale: i64) -> Decimal {
        if scale >= self.scale {
            return Decimal::new(self.negative, self.digits_at(scale), scale);
        }
        let dropped = (self.scale - scale) as usize;
        if dropped > self.digits.len() {
            return Decimal::new(false, Vec::new(), scale);
        }
        let kept = &self.digits[..self.digits.len() - dropped];
        let round_up = self.digits[self.digits.len() - dropped] >= 5;
        let digits = if round_up {
            add_digits(kept, &[1])
        } else {
            kept.to_vec()
        };
        Decimal::new(self.negative, digits, scale)
    }

    /// `x.divide(divisor, scale, HALF_UP)` for a divisor of 100: two places left, then rounded.
    pub fn percent_of(&self, scale: i64) -> Decimal {
        Decimal::new(self.negative, self.digits.clone(), self.scale + 2).set_scale_half_up(scale)
    }

    /// `movePointLeft(places)`: the scale grows by `places`, and a negative scale becomes 0.
    pub fn move_point_left(&self, places: i64) -> Decimal {
        let moved = Decimal::new(self.negative, self.digits.clone(), self.scale + places);
        if moved.scale < 0 {
            moved.set_scale_half_up(0)
        } else {
            moved
        }
    }

    /// `movePointRight(places).longValue()`: the whole part after moving the point, toward zero, as a connection's delay
    /// in seconds becomes nanoseconds (`Duration.ofNanos`). Saturates far beyond any delay.
    pub fn whole_after_moving_right(&self, places: i64) -> i128 {
        let shift = places - self.scale;
        let mut digits: Vec<u8> = self.digits.clone();
        if shift >= 0 {
            digits.extend(std::iter::repeat_n(0, shift as usize));
        } else {
            digits.truncate(digits.len().saturating_sub((-shift) as usize));
        }
        let mut whole: i128 = 0;
        for digit in digits {
            whole = whole.saturating_mul(10).saturating_add(digit as i128);
        }
        if self.negative { -whole } else { whole }
    }

    /// `stripTrailingZeros()`: zero becomes plain 0, as on Java 8 and later.
    pub fn strip_trailing_zeros(&self) -> Decimal {
        if self.digits.is_empty() {
            return Decimal::zero();
        }
        let trailing = self.digits.iter().rev().take_while(|digit| **digit == 0).count();
        Decimal::new(
            self.negative,
            self.digits[..self.digits.len() - trailing].to_vec(),
            self.scale - trailing as i64,
        )
    }

    /// `toPlainString()`: no exponent; a positive scale keeps its trailing zeros.
    pub fn to_plain_string(&self) -> String {
        let mut text = String::new();
        if self.negative {
            text.push('-');
        }
        let digits: String = if self.digits.is_empty() {
            "0".to_string()
        } else {
            self.digits.iter().map(|digit| char::from(b'0' + digit)).collect()
        };
        if self.scale <= 0 {
            text.push_str(&digits);
            if !self.digits.is_empty() {
                text.extend(std::iter::repeat_n('0', (-self.scale) as usize));
            }
            return text;
        }
        let scale = self.scale as usize;
        if digits.len() > scale {
            text.push_str(&digits[..digits.len() - scale]);
            text.push('.');
            text.push_str(&digits[digits.len() - scale..]);
        } else {
            text.push_str("0.");
            text.extend(std::iter::repeat_n('0', scale - digits.len()));
            text.push_str(&digits);
        }
        text
    }

    /// `stripTrailingZeros().toPlainString()`, as messages print numbers.
    pub fn plain(&self) -> String {
        self.strip_trailing_zeros().to_plain_string()
    }

    /// `compareTo`: by value, whatever the scale. (Equality, `==`, is `equals`: value and scale.)
    pub fn compare(&self, other: &Decimal) -> Ordering {
        let sign = self.signum().cmp(&other.signum());
        if sign != Ordering::Equal || self.signum() == 0 {
            return sign;
        }
        let scale = self.scale.max(other.scale);
        let magnitude = compare_digits(&self.digits_at(scale), &other.digits_at(scale));
        if self.negative { magnitude.reverse() } else { magnitude }
    }

    /// The number as Jackson writes a `BigDecimal` with `WRITE_BIGDECIMAL_AS_PLAIN`.
    pub fn to_json(&self) -> serde_json::Value {
        serde_json::from_str(&self.to_plain_string()).expect("a plain decimal is a JSON number")
    }
}

fn trim(digits: &[u8]) -> &[u8] {
    let leading = digits.iter().take_while(|digit| **digit == 0).count();
    &digits[leading..]
}

fn compare_digits(left: &[u8], right: &[u8]) -> Ordering {
    let (left, right) = (trim(left), trim(right));
    left.len().cmp(&right.len()).then_with(|| left.cmp(right))
}

fn add_digits(left: &[u8], right: &[u8]) -> Vec<u8> {
    let mut sum = Vec::with_capacity(left.len().max(right.len()) + 1);
    let mut carry = 0;
    let (mut left, mut right) = (left.iter().rev(), right.iter().rev());
    loop {
        let (a, b) = (left.next(), right.next());
        if a.is_none() && b.is_none() {
            break;
        }
        let total = a.copied().unwrap_or(0) + b.copied().unwrap_or(0) + carry;
        sum.push(total % 10);
        carry = total / 10;
    }
    if carry > 0 {
        sum.push(carry);
    }
    sum.reverse();
    sum
}

/// `left - right` for `left >= right`.
fn subtract_digits(left: &[u8], right: &[u8]) -> Vec<u8> {
    let mut difference = Vec::with_capacity(left.len());
    let mut borrow = 0i8;
    let mut right = right.iter().rev();
    for digit in left.iter().rev() {
        let mut value = *digit as i8 - borrow - right.next().copied().unwrap_or(0) as i8;
        borrow = 0;
        if value < 0 {
            value += 10;
            borrow = 1;
        }
        difference.push(value as u8);
    }
    difference.reverse();
    difference
}

#[cfg(test)]
mod tests {
    use super::Decimal;

    #[test]
    fn delay_seconds_become_nanoseconds_as_java_takes_them() {
        let nanos = |text: &str| Decimal::parse(text).unwrap().whole_after_moving_right(9);
        assert_eq!(nanos("1.2345678919"), 1_234_567_891);
        assert_eq!(nanos("0.0000000001"), 0);
        assert_eq!(nanos("3600"), 3_600_000_000_000);
        assert_eq!(nanos("0.5"), 500_000_000);
        assert_eq!(nanos("-1.0000000009"), -1_000_000_000);
    }

    fn d(text: &str) -> Decimal {
        Decimal::parse(text).unwrap()
    }

    #[test]
    fn keeps_and_prints_the_scale_as_java_does() {
        assert_eq!(d("2.50").to_plain_string(), "2.50");
        assert_eq!(d("0.0000").to_plain_string(), "0.0000");
        assert_eq!(d("1E+2").to_plain_string(), "100");
        assert_eq!(d("1E+2").scale(), -2);
        assert_eq!(d("-0.5").to_plain_string(), "-0.5");
        assert_eq!(d("-0").to_plain_string(), "0");
        assert_eq!(d("12.3400").plain(), "12.34");
        assert_eq!(d("0.000").plain(), "0");
        assert_eq!(d("100").plain(), "100");
        assert_eq!(d("100").strip_trailing_zeros().scale(), -2);
    }

    #[test]
    fn computes_as_big_decimal_does() {
        assert_eq!(
            d("12.34567")
                .multiply(&d("1").subtract(&d("0.75")))
                .set_scale_half_up(4)
                .to_plain_string(),
            "3.0864"
        );
        assert_eq!(
            d("0.0001").multiply(&d("0.25")).set_scale_half_up(4).to_plain_string(),
            "0.0000"
        );
        assert_eq!(d("12.34567").subtract(&d("3.0864")).to_plain_string(), "9.25927");
        assert_eq!(
            d("33.3333").multiply(&d("33.3333")).percent_of(4).to_plain_string(),
            "11.1111"
        );
        assert_eq!(d("10").multiply(&d("60")).percent_of(4).to_plain_string(), "6.0000");
        assert_eq!(d("-2.5").set_scale_half_up(0).to_plain_string(), "-3");
        assert_eq!(d("150").move_point_left(2).to_plain_string(), "1.50");
        assert_eq!(d("1E+3").move_point_left(2).to_plain_string(), "10");
        assert_eq!(d("0.2").negate().to_plain_string(), "-0.2");
        assert_eq!(d("5").add(&d("-7.5")).to_plain_string(), "-2.5");
        assert!(d("2.0") == d("2.0") && d("2.0").compare(&d("2")).is_eq() && d("2.0") != d("2"));
        assert!(d("-3").compare(&d("-2.5")).is_lt() && d("0.99995").compare(&d("1")).is_lt());
    }
}
