//! FlowMat's condition language in Rust: the same results, error messages and error positions as the backend's Java
//! `ConditionExpression` (`flowmat_backend/modules/workflow/.../domain/expression/ConditionExpression.java`).
//!
//! The language is small and data-only: comparisons of `quantity`, `unit`, `item`, `attrs.<name>` and literals with
//! `= != < <= > >=`, joined by `and`, `or`, `not` and parentheses. The shared fixtures in `fixtures/` hold each source
//! with what Java answers, and both test suites check their implementation against them.
//!
//! Positions count UTF-16 code units from 1, as Java's do, which is also how a JavaScript editor counts.

mod chars;
mod decimal;
mod values;
#[cfg(target_arch = "wasm32")]
mod wasm;

use std::collections::{BTreeSet, HashSet};
use std::fmt;

pub use chars::{DIGIT_ZEROS, digit as java_digit, is_whitespace as java_whitespace};
pub use decimal::Decimal;
pub use values::{Value, Values};

/// The longest source, in UTF-16 code units.
pub const MAX_LENGTH: usize = 500;

/// Why a source is not a condition, and where: Java's `Invalid condition at position N: reason`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConditionError {
    /// 1-based, in UTF-16 code units.
    pub position: usize,
    pub reason: String,
}

impl fmt::Display for ConditionError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "Invalid condition at position {}: {}",
            self.position, self.reason
        )
    }
}

impl std::error::Error for ConditionError {}

fn invalid(position: usize, reason: impl Into<String>) -> ConditionError {
    ConditionError {
        position,
        reason: reason.into(),
    }
}

/// A compiled condition.
#[derive(Debug, Clone)]
pub struct Condition {
    root: Node,
    attributes: Vec<AttributeReference>,
}

#[derive(Debug, Clone)]
struct AttributeReference {
    name: String,
    position: usize,
}

#[derive(Debug, Clone)]
enum Node {
    Or(Box<Node>, Box<Node>),
    And(Box<Node>, Box<Node>),
    Not(Box<Node>),
    Compare(Operand, Operator, Operand),
}

#[derive(Debug, Clone)]
enum Operand {
    Number(Decimal),
    Text(String),
    Bool(bool),
    Quantity,
    Unit,
    Item,
    Attribute(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Operator {
    Equal,
    NotEqual,
    Less,
    LessOrEqual,
    Greater,
    GreaterOrEqual,
}

impl Operator {
    fn parse(text: &str) -> Option<Operator> {
        Some(match text {
            "=" => Operator::Equal,
            "!=" => Operator::NotEqual,
            "<" => Operator::Less,
            "<=" => Operator::LessOrEqual,
            ">" => Operator::Greater,
            ">=" => Operator::GreaterOrEqual,
            _ => return None,
        })
    }
}

impl Condition {
    /// Compiles a source, or says where and why it is not a condition.
    pub fn compile(source: &str) -> Result<Condition, ConditionError> {
        let units: Vec<u16> = source.encode_utf16().collect();
        if units.iter().all(|unit| chars::is_whitespace(*unit)) {
            return Err(invalid(1, "expression is empty"));
        }
        if units.len() > MAX_LENGTH {
            return Err(invalid(MAX_LENGTH + 1, "expression exceeds 500 characters"));
        }
        let mut parser = Parser {
            tokens: tokenize(&units)?,
            cursor: 0,
            attributes: Vec::new(),
        };
        let root = parser.expression()?;
        parser.expect(Kind::Eof)?;
        Ok(Condition {
            root,
            attributes: parser.attributes,
        })
    }

    /// Whether the condition holds for these values.
    pub fn evaluate(&self, values: &Values) -> bool {
        self.root.evaluate(values)
    }

    /// The `attrs.<name>` names the condition reads.
    pub fn attribute_references(&self) -> BTreeSet<String> {
        self.attributes.iter().map(|attribute| attribute.name.clone()).collect()
    }

    /// Fails at the first `attrs.<name>`, left to right, that the port's schema does not declare.
    pub fn require_declared_attributes(&self, declared: &HashSet<String>) -> Result<(), ConditionError> {
        match self
            .attributes
            .iter()
            .find(|attribute| !declared.contains(&attribute.name))
        {
            Some(attribute) => Err(invalid(
                attribute.position,
                format!("attrs.{} is not declared in schemaJson", attribute.name),
            )),
            None => Ok(()),
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Tokens

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Text,
    Number,
    Ident,
    Or,
    And,
    Not,
    True,
    False,
    Open,
    Close,
    Compare,
    Eof,
}

impl Kind {
    /// The name Java's `expected …` message uses.
    fn name(self) -> &'static str {
        match self {
            Kind::Text => "STRING",
            Kind::Number => "NUMBER",
            Kind::Ident => "IDENT",
            Kind::Or => "or",
            Kind::And => "and",
            Kind::Not => "not",
            Kind::True => "true",
            Kind::False => "false",
            Kind::Open => "(",
            Kind::Close => ")",
            Kind::Compare => "COMPARE",
            Kind::Eof => "EOF",
        }
    }
}

#[derive(Debug, Clone)]
struct Token {
    kind: Kind,
    value: String,
    position: usize,
}

const QUOTE: u16 = b'\'' as u16;
const BACKSLASH: u16 = b'\\' as u16;
const MINUS: u16 = b'-' as u16;
const DOT: u16 = b'.' as u16;

fn ascii(unit: u16) -> Option<u8> {
    u8::try_from(unit).ok().filter(u8::is_ascii)
}

fn is_identifier_start(unit: u16) -> bool {
    ascii(unit).is_some_and(|byte| byte.is_ascii_alphabetic() || byte == b'_')
}

fn is_identifier_part(unit: u16) -> bool {
    ascii(unit).is_some_and(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

/// The text of code units that came from a valid string and were cut at ASCII characters, so it is whole.
fn text(units: &[u16]) -> String {
    String::from_utf16_lossy(units)
}

fn tokenize(units: &[u16]) -> Result<Vec<Token>, ConditionError> {
    let length = units.len();
    let mut tokens = Vec::new();
    let mut index = 0;
    while index < length {
        let unit = units[index];
        if chars::is_whitespace(unit) {
            index += 1;
            continue;
        }
        let start = index;
        if unit == QUOTE {
            let mut value = Vec::new();
            index += 1;
            let mut closed = false;
            while index < length {
                let current = units[index];
                index += 1;
                if current == QUOTE {
                    closed = true;
                    break;
                }
                if current == BACKSLASH && index < length {
                    let escaped = units[index];
                    index += 1;
                    if escaped != QUOTE && escaped != BACKSLASH {
                        return Err(invalid(index, "invalid string escape"));
                    }
                    value.push(escaped);
                } else {
                    value.push(current);
                }
            }
            if !closed {
                return Err(invalid(start + 1, "unterminated string"));
            }
            tokens.push(Token {
                kind: Kind::Text,
                value: text(&value),
                position: start + 1,
            });
            continue;
        }
        if chars::is_digit(unit) || (unit == MINUS && index + 1 < length && chars::is_digit(units[index + 1])) {
            index += 1;
            while index < length && chars::is_digit(units[index]) {
                index += 1;
            }
            if index < length && units[index] == DOT {
                index += 1;
                if index >= length || !chars::is_digit(units[index]) {
                    return Err(invalid(index + 1, "number needs digits after decimal point"));
                }
                while index < length && chars::is_digit(units[index]) {
                    index += 1;
                }
            }
            tokens.push(Token {
                kind: Kind::Number,
                value: text(&units[start..index]),
                position: start + 1,
            });
            continue;
        }
        if is_identifier_start(unit) {
            index += 1;
            while index < length && is_identifier_part(units[index]) {
                index += 1;
            }
            if text(&units[start..index]) == "attrs" && index < length && units[index] == DOT {
                index += 1;
                if index >= length || !is_identifier_start(units[index]) {
                    return Err(invalid(index + 1, "attrs needs a property name"));
                }
                index += 1;
                while index < length && is_identifier_part(units[index]) {
                    index += 1;
                }
            }
            let word = text(&units[start..index]);
            let kind = match word.as_str() {
                "or" => Kind::Or,
                "and" => Kind::And,
                "not" => Kind::Not,
                "true" => Kind::True,
                "false" => Kind::False,
                _ => Kind::Ident,
            };
            tokens.push(Token {
                kind,
                value: word,
                position: start + 1,
            });
            continue;
        }
        match ascii(unit) {
            Some(b'(') | Some(b')') => {
                let kind = if unit == u16::from(b'(') {
                    Kind::Open
                } else {
                    Kind::Close
                };
                tokens.push(Token {
                    kind,
                    value: text(&units[start..=start]),
                    position: start + 1,
                });
                index += 1;
                continue;
            }
            Some(b'=') | Some(b'!') | Some(b'<') | Some(b'>') => {
                index += 1;
                if index < length && units[index] == u16::from(b'=') {
                    index += 1;
                }
                let operator = text(&units[start..index]);
                if Operator::parse(&operator).is_none() {
                    return Err(invalid(
                        start + 1,
                        format!("unsupported comparison operator '{operator}'"),
                    ));
                }
                tokens.push(Token {
                    kind: Kind::Compare,
                    value: operator,
                    position: start + 1,
                });
                continue;
            }
            _ => {}
        }
        // The whole character, also when it takes two code units.
        let character = char::decode_utf16(units[start..].iter().copied())
            .next()
            .map_or(char::REPLACEMENT_CHARACTER, |decoded| {
                decoded.unwrap_or(char::REPLACEMENT_CHARACTER)
            });
        return Err(invalid(start + 1, format!("unexpected character '{character}'")));
    }
    tokens.push(Token {
        kind: Kind::Eof,
        value: String::new(),
        position: length + 1,
    });
    Ok(tokens)
}

// ---------------------------------------------------------------------------------------------------------------------
// Parser

struct Parser {
    tokens: Vec<Token>,
    cursor: usize,
    attributes: Vec<AttributeReference>,
}

impl Parser {
    fn current(&self) -> &Token {
        &self.tokens[self.cursor]
    }

    fn take(&mut self, kind: Kind) -> bool {
        if self.current().kind != kind {
            return false;
        }
        self.cursor += 1;
        true
    }

    fn expect(&mut self, kind: Kind) -> Result<Token, ConditionError> {
        let token = self.current().clone();
        if !self.take(kind) {
            return Err(invalid(token.position, format!("expected {}", kind.name())));
        }
        Ok(token)
    }

    fn expression(&mut self) -> Result<Node, ConditionError> {
        let mut left = self.conjunction()?;
        while self.take(Kind::Or) {
            let right = self.conjunction()?;
            left = Node::Or(Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn conjunction(&mut self) -> Result<Node, ConditionError> {
        let mut left = self.negation()?;
        while self.take(Kind::And) {
            let right = self.negation()?;
            left = Node::And(Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn negation(&mut self) -> Result<Node, ConditionError> {
        if self.take(Kind::Not) {
            return Ok(Node::Not(Box::new(self.negation()?)));
        }
        if self.take(Kind::Open) {
            let grouped = self.expression()?;
            self.expect(Kind::Close)?;
            return Ok(grouped);
        }
        let left = self.operand()?;
        let operator = self.expect(Kind::Compare)?;
        let right = self.operand()?;
        let operator = Operator::parse(&operator.value).expect("the tokenizer only makes the six operators");
        Ok(Node::Compare(left, operator, right))
    }

    fn operand(&mut self) -> Result<Operand, ConditionError> {
        let token = self.current().clone();
        let operand = match token.kind {
            Kind::Number => {
                Operand::Number(Decimal::parse(&token.value).expect("a number token is digits with an optional point"))
            }
            Kind::Text => Operand::Text(token.value),
            Kind::True => Operand::Bool(true),
            Kind::False => Operand::Bool(false),
            Kind::Ident => match token.value.strip_prefix("attrs.") {
                Some(property) => {
                    self.attributes.push(AttributeReference {
                        name: property.to_owned(),
                        position: token.position,
                    });
                    Operand::Attribute(property.to_owned())
                }
                None => match token.value.as_str() {
                    "quantity" => Operand::Quantity,
                    "unit" => Operand::Unit,
                    "item" => Operand::Item,
                    name => {
                        return Err(invalid(token.position, format!("unknown operand '{name}'")));
                    }
                },
            },
            _ => return Err(invalid(token.position, "expected operand")),
        };
        self.cursor += 1;
        Ok(operand)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Evaluation

impl Node {
    fn evaluate(&self, values: &Values) -> bool {
        match self {
            Node::Or(left, right) => left.evaluate(values) || right.evaluate(values),
            Node::And(left, right) => left.evaluate(values) && right.evaluate(values),
            Node::Not(child) => !child.evaluate(values),
            Node::Compare(left, operator, right) => compare(left.resolve(values), right.resolve(values), *operator),
        }
    }
}

/// An operand's value for one evaluation. `Absent` is Java's MISSING or null.
enum Resolved<'a> {
    Number(&'a Decimal),
    Text(&'a str),
    Bool(bool),
    Absent,
}

impl Operand {
    fn resolve<'a>(&'a self, values: &'a Values) -> Resolved<'a> {
        let field = |value: Option<&'a Value>| match value {
            Some(Value::Number(number)) => Resolved::Number(number),
            Some(Value::Text(text)) => Resolved::Text(text),
            Some(Value::Bool(flag)) => Resolved::Bool(*flag),
            Some(Value::Null) | None => Resolved::Absent,
        };
        match self {
            Operand::Number(number) => Resolved::Number(number),
            Operand::Text(text) => Resolved::Text(text),
            Operand::Bool(flag) => Resolved::Bool(*flag),
            Operand::Quantity => field(values.quantity.as_ref()),
            Operand::Unit => field(values.unit.as_ref()),
            Operand::Item => field(values.item.as_ref()),
            Operand::Attribute(name) => field(values.attrs.as_ref().and_then(|attrs| attrs.get(name))),
        }
    }
}

fn compare(left: Resolved<'_>, right: Resolved<'_>, operator: Operator) -> bool {
    use std::cmp::Ordering;
    let order: Ordering = match (left, right) {
        (Resolved::Number(first), Resolved::Number(second)) => first.cmp(second),
        // Java's String.compareTo orders by UTF-16 code unit, which differs from code point order above U+FFFF.
        (Resolved::Text(first), Resolved::Text(second)) => first.encode_utf16().cmp(second.encode_utf16()),
        (Resolved::Bool(first), Resolved::Bool(second)) => {
            if !matches!(operator, Operator::Equal | Operator::NotEqual) {
                return false;
            }
            first.cmp(&second)
        }
        _ => return false,
    };
    match operator {
        Operator::Equal => order.is_eq(),
        Operator::NotEqual => order.is_ne(),
        Operator::Less => order.is_lt(),
        Operator::LessOrEqual => order.is_le(),
        Operator::Greater => order.is_gt(),
        Operator::GreaterOrEqual => order.is_ge(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn values(snapshot: serde_json::Value) -> Values {
        Values::from_snapshot(&snapshot)
    }

    fn error(source: &str) -> String {
        Condition::compile(source).expect_err(source).to_string()
    }

    #[test]
    fn evaluates_boolean_logic_numbers_strings_and_attributes() {
        let condition =
            Condition::compile("quantity >= 2 and (unit = 'kg' or attrs.approved = true) and not item = 'blocked'")
                .unwrap();
        assert!(condition.evaluate(&values(
            json!({"quantity": 2, "unit": "ea", "item": "allowed", "attrs": {"approved": true}})
        )));
        assert!(!condition.evaluate(&values(json!({"quantity": 1, "unit": "kg", "item": "allowed"}))));
        assert!(
            !Condition::compile("attrs.missing = 1")
                .unwrap()
                .evaluate(&values(json!({"attrs": {"present": 1}})))
        );
        assert!(
            !Condition::compile("quantity > '2'")
                .unwrap()
                .evaluate(&values(json!({"quantity": 3})))
        );
        assert_eq!(
            condition.attribute_references(),
            BTreeSet::from(["approved".to_owned()])
        );
    }

    #[test]
    fn reports_javas_messages_and_positions() {
        assert_eq!(error("  "), "Invalid condition at position 1: expression is empty");
        assert_eq!(
            error(&"a".repeat(501)),
            "Invalid condition at position 501: expression exceeds 500 characters"
        );
        assert_eq!(
            error("quantity == 2"),
            "Invalid condition at position 10: unsupported comparison operator '=='"
        );
        assert_eq!(
            error("quantity >"),
            "Invalid condition at position 11: expected operand"
        );
        assert_eq!(
            error("sum(quantity) > 2"),
            "Invalid condition at position 1: unknown operand 'sum'"
        );
        assert_eq!(
            error("unit = '😀' and #"),
            "Invalid condition at position 17: unexpected character '#'"
        );
        assert_eq!(
            error("unit = 😀"),
            "Invalid condition at position 8: unexpected character '😀'"
        );
    }

    #[test]
    fn requires_declared_attributes_left_to_right() {
        let condition = Condition::compile("attrs.a = 1 or attrs.b = 2").unwrap();
        let declared = HashSet::from(["a".to_owned()]);
        assert_eq!(
            condition
                .require_declared_attributes(&declared)
                .unwrap_err()
                .to_string(),
            "Invalid condition at position 16: attrs.b is not declared in schemaJson"
        );
    }
}
