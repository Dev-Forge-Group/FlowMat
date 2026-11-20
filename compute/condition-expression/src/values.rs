//! What a condition reads: an output snapshot's `quantity`, `unit`, `item` and `attrs`.

use std::collections::HashMap;

use serde_json::Value as Json;

use crate::Decimal;

/// One value a condition can compare. `Null` stands for everything Java turns into `null`: a JSON null, an array, an
/// object, or a number Java could not read.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Number(Decimal),
    Text(String),
    Bool(bool),
    Null,
}

impl Value {
    /// The value Java's run graph takes from one JSON field: a number as an exact decimal, text, a boolean, or null.
    pub fn from_json(json: &Json) -> Value {
        match json {
            Json::Number(number) => Decimal::parse(&number.to_string()).map_or(Value::Null, Value::Number),
            Json::String(text) => Value::Text(text.clone()),
            Json::Bool(flag) => Value::Bool(*flag),
            Json::Null | Json::Array(_) | Json::Object(_) => Value::Null,
        }
    }
}

/// The fields of one snapshot. A field that is absent and a field that is `Null` both make every comparison false.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Values {
    pub quantity: Option<Value>,
    pub unit: Option<Value>,
    pub item: Option<Value>,
    /// `None` when the snapshot has no `attrs` object.
    pub attrs: Option<HashMap<String, Value>>,
}

impl Values {
    /// The values Java's `FlowRunGraph.values` takes from an output snapshot: `quantity`, `unit` and `item` where present,
    /// and `attrs` when it is an object. Anything else in the snapshot is not visible to a condition.
    pub fn from_snapshot(snapshot: &Json) -> Values {
        let field = |name: &str| snapshot.get(name).map(Value::from_json);
        Values {
            quantity: field("quantity"),
            unit: field("unit"),
            item: field("item"),
            attrs: snapshot.get("attrs").and_then(Json::as_object).map(|attrs| {
                attrs
                    .iter()
                    .map(|(name, value)| (name.clone(), Value::from_json(value)))
                    .collect()
            }),
        }
    }
}
