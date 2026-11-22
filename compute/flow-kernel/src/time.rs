//! Instants with the offset they are written in, as Java's `OffsetDateTime` keeps them, read and written as
//! `DateTimeFormatter.ISO_OFFSET_DATE_TIME` does. No time zone rules are needed: the server works out working time in
//! the project's time zone and hands over stretches with their offsets (docs/architecture/adr/ADR-041-try-it-time.md).

const NANOS_PER_SECOND: i128 = 1_000_000_000;
const SECONDS_PER_DAY: i128 = 86_400;

/// An instant (nanoseconds since 1970-01-01T00:00Z) and the offset, in seconds east of UTC, it is shown in.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DateTime {
    nanos: i128,
    offset: i32,
}

impl DateTime {
    pub fn instant(&self) -> i128 {
        self.nanos
    }

    pub fn offset(&self) -> i32 {
        self.offset
    }

    pub fn from_instant(nanos: i128, offset: i32) -> DateTime {
        DateTime { nanos, offset }
    }

    /// The same instant shown in another offset (`atOffset`, `withOffsetSameInstant`).
    pub fn at_offset(&self, offset: i32) -> DateTime {
        DateTime {
            nanos: self.nanos,
            offset,
        }
    }

    pub fn plus_nanos(&self, nanos: i128) -> DateTime {
        DateTime {
            nanos: self.nanos + nanos,
            offset: self.offset,
        }
    }

    /// `truncatedTo(ChronoUnit.MICROS)`: the local nano-of-second is cut, which with whole-second offsets is the
    /// instant floored to a microsecond.
    pub fn truncated_to_micros(&self) -> DateTime {
        DateTime {
            nanos: self.nanos.div_euclid(1_000) * 1_000,
            offset: self.offset,
        }
    }

    /// `2026-12-21T09:00:00+09:00`, `…T09:00:00.5Z`, as `OffsetDateTime.parse` reads it: seconds and fraction optional,
    /// `Z` or `±HH:MM` (with optional `:SS`). None for anything else.
    pub fn parse(text: &str) -> Option<DateTime> {
        let bytes = text.as_bytes();
        let number = |from: usize, to: usize| -> Option<i64> {
            let part = text.get(from..to)?;
            if part.is_empty() || !part.bytes().all(|byte| byte.is_ascii_digit()) {
                return None;
            }
            part.parse().ok()
        };
        if bytes.len() < 16 || bytes[4] != b'-' || bytes[7] != b'-' || bytes[10] != b'T' || bytes[13] != b':' {
            return None;
        }
        let (year, month, day) = (number(0, 4)?, number(5, 7)?, number(8, 10)?);
        let (hour, minute) = (number(11, 13)?, number(14, 16)?);
        let mut at = 16;
        let mut second = 0;
        let mut nano: i64 = 0;
        if bytes.get(at) == Some(&b':') {
            second = number(at + 1, at + 3)?;
            at += 3;
            if bytes.get(at) == Some(&b'.') {
                let digits = bytes[at + 1..].iter().take_while(|byte| byte.is_ascii_digit()).count();
                if digits == 0 || digits > 9 {
                    return None;
                }
                nano = number(at + 1, at + 1 + digits)? * 10_i64.pow((9 - digits) as u32);
                at += 1 + digits;
            }
        }
        let offset = match bytes.get(at)? {
            b'Z' if at + 1 == bytes.len() => 0,
            sign @ (b'+' | b'-') => {
                let hours = number(at + 1, at + 3)?;
                if bytes.get(at + 3) != Some(&b':') {
                    return None;
                }
                let minutes = number(at + 4, at + 6)?;
                let mut seconds = 0;
                if at + 6 < bytes.len() {
                    if bytes[at + 6] != b':' || at + 9 != bytes.len() {
                        return None;
                    }
                    seconds = number(at + 7, at + 9)?;
                } else if at + 6 != bytes.len() {
                    return None;
                }
                let total = (hours * 3600 + minutes * 60 + seconds) as i32;
                if *sign == b'-' { -total } else { total }
            }
            _ => return None,
        };
        if !(1..=12).contains(&month)
            || day < 1
            || day > days_in_month(year, month)
            || hour > 23
            || minute > 59
            || second > 59
        {
            return None;
        }
        let local = (days_from_civil(year, month, day) as i128 * SECONDS_PER_DAY
            + (hour * 3600 + minute * 60 + second) as i128)
            * NANOS_PER_SECOND
            + nano as i128;
        Some(DateTime {
            nanos: local - offset as i128 * NANOS_PER_SECOND,
            offset,
        })
    }

    /// As `DateTimeFormatter.ISO_OFFSET_DATE_TIME` writes it: seconds always, a fraction only when there is one and
    /// without trailing zeros, `Z` for UTC.
    pub fn format(&self) -> String {
        let local = self.nanos + self.offset as i128 * NANOS_PER_SECOND;
        let seconds = local.div_euclid(NANOS_PER_SECOND);
        let nano = local.rem_euclid(NANOS_PER_SECOND);
        let days = seconds.div_euclid(SECONDS_PER_DAY);
        let of_day = seconds.rem_euclid(SECONDS_PER_DAY);
        let (year, month, day) = civil_from_days(days as i64);
        let mut text = format!(
            "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}",
            of_day / 3600,
            of_day % 3600 / 60,
            of_day % 60
        );
        if nano != 0 {
            let digits = format!("{nano:09}");
            text.push('.');
            text.push_str(digits.trim_end_matches('0'));
        }
        if self.offset == 0 {
            text.push('Z');
        } else {
            let total = self.offset.unsigned_abs();
            text.push(if self.offset < 0 { '-' } else { '+' });
            text.push_str(&format!("{:02}:{:02}", total / 3600, total % 3600 / 60));
            if !total.is_multiple_of(60) {
                text.push_str(&format!(":{:02}", total % 60));
            }
        }
        text
    }
}

fn is_leap(year: i64) -> bool {
    year.rem_euclid(4) == 0 && (year.rem_euclid(100) != 0 || year.rem_euclid(400) == 0)
}

fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        2 if is_leap(year) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's `days_from_civil`).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let of_era = year - era * 400;
    let of_year = (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day - 1;
    let of_era_day = of_era * 365 + of_era / 4 - of_era / 100 + of_year;
    era * 146_097 + of_era_day - 719_468
}

/// The date `days` after 1970-01-01 (H. Hinnant's `civil_from_days`).
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let days = days + 719_468;
    let era = days.div_euclid(146_097);
    let of_era = days - era * 146_097;
    let year_of_era = (of_era - of_era / 1460 + of_era / 36_524 - of_era / 146_096) / 365;
    let of_year = of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_index = (5 * of_year + 2) / 153;
    let day = of_year - (153 * month_index + 2) / 5 + 1;
    let month = if month_index < 10 {
        month_index + 3
    } else {
        month_index - 9
    };
    let year = year_of_era + era * 400 + if month <= 2 { 1 } else { 0 };
    (year, month, day)
}

#[cfg(test)]
mod tests {
    use super::DateTime;

    #[test]
    fn reads_and_writes_as_java_does() {
        for (text, written) in [
            ("2026-12-21T09:00:00+09:00", "2026-12-21T09:00:00+09:00"),
            ("2026-12-21T09:00+09:00", "2026-12-21T09:00:00+09:00"),
            ("2026-12-21T00:00:00.508078000Z", "2026-12-21T00:00:00.508078Z"),
            ("2027-03-14T03:00:00-04:00", "2027-03-14T03:00:00-04:00"),
            ("2024-02-29T23:59:59.000000001+00:00", "2024-02-29T23:59:59.000000001Z"),
            ("1969-12-31T23:59:59.5-05:30", "1969-12-31T23:59:59.5-05:30"),
        ] {
            assert_eq!(
                DateTime::parse(text).map(|time| time.format()).as_deref(),
                Some(written),
                "{text}"
            );
        }
        for text in [
            "2026-02-30T09:00Z",
            "2026-12-21 09:00Z",
            "2026-12-21T09:00",
            "2026-12-21T09:00+9:00",
        ] {
            assert_eq!(DateTime::parse(text), None, "{text}");
        }
    }

    #[test]
    fn offsets_show_one_instant() {
        let tokyo = DateTime::parse("2026-12-21T09:00:00+09:00").unwrap();
        assert_eq!(tokyo.at_offset(0).format(), "2026-12-21T00:00:00Z");
        assert_eq!(
            tokyo.instant(),
            DateTime::parse("2026-12-20T19:00:00-05:00").unwrap().instant()
        );
    }

    #[test]
    fn cuts_to_microseconds() {
        let time = DateTime::parse("2026-12-21T09:00:00.000001999Z").unwrap();
        assert_eq!(time.truncated_to_micros().format(), "2026-12-21T09:00:00.000001Z");
        let before = DateTime::parse("1969-12-31T23:59:59.9999995Z").unwrap();
        assert_eq!(before.truncated_to_micros().format(), "1969-12-31T23:59:59.999999Z");
    }
}
