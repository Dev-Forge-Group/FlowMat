//! A workflow graph as the kernel takes it: `{processes, ports, connections}` in the server's response shape
//! (`ProcessResponse`, `ProcessIoResponse`, `ProcessConnectionResponse`), the JSON the editor holds. Fields are read as
//! Jackson binds them: a missing field or `null` is none, a scalar given for text is its text.

use serde_json::Value as Json;

use crate::decimal::Decimal;

#[derive(Debug, Clone)]
pub struct Process {
    pub id: Option<String>,
    pub name: Option<String>,
    pub node_type: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Port {
    pub id: Option<String>,
    pub process_id: Option<String>,
    pub direction: Option<String>,
    pub io_name: Option<String>,
    pub io_type: Option<String>,
    pub item_id: Option<String>,
    pub quantity: Option<Decimal>,
    pub unit: Option<String>,
    pub required_yn: Option<String>,
    pub allow_shortage_yn: Option<String>,
    pub resource_type: Option<String>,
    /// As published: the JSON value, `None` when missing or `null`.
    pub schema: Option<Json>,
    pub validation_rule: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Connection {
    pub id: Option<String>,
    pub from_process_id: Option<String>,
    pub to_process_id: Option<String>,
    pub from_io_id: Option<String>,
    pub to_io_id: Option<String>,
    pub item_id: Option<String>,
    pub condition_expr: Option<String>,
    pub unit: Option<String>,
    pub delay_time_sec: Option<Decimal>,
    pub loss_rate: Option<Decimal>,
    pub priority: Option<i64>,
    pub default_route: bool,
    pub split_kind: Option<String>,
    pub split_value: Option<Decimal>,
}

#[derive(Debug, Clone)]
pub struct Graph {
    pub processes: Vec<Process>,
    pub ports: Vec<Port>,
    pub connections: Vec<Connection>,
}

impl Graph {
    pub fn from_json(graph: &Json) -> Graph {
        let items = |name: &str| graph.get(name).and_then(Json::as_array).cloned().unwrap_or_default();
        Graph {
            processes: items("processes")
                .iter()
                .map(|node| Process {
                    id: text(node, "processId"),
                    name: text(node, "processName"),
                    node_type: text(node, "nodeType"),
                })
                .collect(),
            ports: items("ports")
                .iter()
                .map(|node| Port {
                    id: text(node, "processIoId"),
                    process_id: text(node, "processId"),
                    direction: text(node, "direction"),
                    io_name: text(node, "ioName"),
                    io_type: text(node, "ioType"),
                    item_id: text(node, "itemId"),
                    quantity: decimal(node, "quantity"),
                    unit: text(node, "unit"),
                    required_yn: text(node, "requiredYn"),
                    allow_shortage_yn: text(node, "allowShortageYn"),
                    resource_type: text(node, "resourceType"),
                    schema: node.get("schemaJson").filter(|value| !value.is_null()).cloned(),
                    validation_rule: text(node, "validationRule"),
                })
                .collect(),
            connections: items("connections")
                .iter()
                .map(|node| Connection {
                    id: text(node, "connectionId"),
                    from_process_id: text(node, "fromProcessId"),
                    to_process_id: text(node, "toProcessId"),
                    from_io_id: text(node, "fromIoId"),
                    to_io_id: text(node, "toIoId"),
                    item_id: text(node, "itemId"),
                    condition_expr: text(node, "conditionExpr"),
                    unit: text(node, "unit"),
                    delay_time_sec: decimal(node, "delayTimeSec"),
                    loss_rate: decimal(node, "lossRate"),
                    priority: integer(node, "priority"),
                    default_route: node.get("defaultRoute").is_some_and(as_boolean),
                    split_kind: text(node, "splitKind"),
                    split_value: decimal(node, "splitValue"),
                })
                .collect(),
        }
    }
}

/// A text field: Jackson binds a number or boolean given for it as its text.
pub fn text(node: &Json, name: &str) -> Option<String> {
    match node.get(name)? {
        Json::String(value) => Some(value.clone()),
        Json::Number(value) => Some(value.to_string()),
        Json::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}

pub fn decimal(node: &Json, name: &str) -> Option<Decimal> {
    match node.get(name)? {
        Json::Number(value) => Decimal::parse(&value.to_string()),
        Json::String(value) => Decimal::parse(value.trim()),
        _ => None,
    }
}

fn integer(node: &Json, name: &str) -> Option<i64> {
    match node.get(name)? {
        Json::Number(value) => value.as_i64().or_else(|| value.as_f64().map(|number| number as i64)),
        Json::String(value) => value.trim().parse().ok(),
        _ => None,
    }
}

/// Jackson's `asBoolean`: true, a non-zero number or the text `true`.
pub fn as_boolean(value: &Json) -> bool {
    match value {
        Json::Bool(value) => *value,
        Json::Number(value) => value.as_f64().is_some_and(|number| number != 0.0),
        Json::String(value) => value.trim() == "true",
        _ => false,
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Java's string rules, which differ from Rust's at the edges.

/// `String.isBlank`: every character is `Character.isWhitespace`.
pub fn is_blank(value: &str) -> bool {
    value.encode_utf16().all(flowmat_condition::java_whitespace)
}

/// `value != null && !value.isBlank()`.
pub fn has_text(value: Option<&str>) -> bool {
    value.is_some_and(|value| !is_blank(value))
}

/// `value != null && !value.trim().isBlank()`: `trim` also drops control characters below the space.
pub fn has_trimmed_text(value: Option<&str>) -> bool {
    value.is_some_and(|value| !is_blank(trim(value)))
}

/// `String.trim`: drops characters up to U+0020 at both ends.
pub fn trim(value: &str) -> &str {
    value.trim_matches(|character: char| character <= ' ')
}

/// `toLowerCase(Locale.ROOT)`.
pub fn lower(value: &str) -> String {
    value.to_lowercase()
}

/// `toUpperCase(Locale.ROOT)`.
pub fn upper(value: &str) -> String {
    value.to_uppercase()
}

/// `String.equalsIgnoreCase`: the same length in UTF-16 and each pair of characters equal, or equal in upper case, or
/// equal in lower case after that.
pub fn equals_ignore_case(left: &str, right: &str) -> bool {
    let (left, right): (Vec<char>, Vec<char>) = (left.chars().collect(), right.chars().collect());
    if left.iter().map(|character| character.len_utf16()).sum::<usize>()
        != right.iter().map(|character| character.len_utf16()).sum::<usize>()
        || left.len() != right.len()
    {
        return false;
    }
    left.iter().zip(&right).all(|(a, b)| {
        if a == b {
            return true;
        }
        let (upper_a, upper_b) = (single_upper(*a), single_upper(*b));
        upper_a == upper_b || single_lower(upper_a) == single_lower(upper_b)
    })
}

/// `Character.toUpperCase`: one character to one; where Rust's mapping gives several, Java keeps the character.
fn single_upper(character: char) -> char {
    let mut mapped = character.to_uppercase();
    match (mapped.next(), mapped.next()) {
        (Some(upper), None) => upper,
        _ => character,
    }
}

fn single_lower(character: char) -> char {
    let mut mapped = character.to_lowercase();
    match (mapped.next(), mapped.next()) {
        (Some(lower), None) => lower,
        _ => character,
    }
}

/// `WorkflowText.requireStorable`: PostgreSQL cannot store NUL. (A JSON string cannot hold a lone surrogate here.)
pub fn storable(value: &str) -> bool {
    !value.contains('\0')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn follows_java_at_the_edges() {
        assert!(is_blank(" \u{2003}\t") && !is_blank("\u{1}"));
        assert!(has_text(Some("\u{1}")) && !has_trimmed_text(Some("\u{1}")));
        assert!(!has_text(Some(" ")) && !has_text(None));
        assert_eq!(trim("\u{1} a \u{20}"), "a");
        assert!(equals_ignore_case("INPUT", "input") && equals_ignore_case("ınput", "input"));
        assert!(!equals_ignore_case("input", "inputs"));
        assert_eq!(upper("y"), "Y");
    }
}
