//! A port's `schemaJson`: the small subset the backend's `PortSchema` reads, with the same messages. Names are kept in
//! name order, as Java now keeps them, so the first mismatch named is always the same.

use std::collections::{BTreeMap, BTreeSet, HashSet};

use serde_json::Value as Json;

use crate::graph;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PortSchema {
    pub properties: BTreeMap<String, String>,
    pub required: BTreeSet<String>,
}

const TYPES: [&str; 3] = ["string", "number", "boolean"];

impl PortSchema {
    /// `PortSchema.parse`: `None` for no schema, else the schema or why it is not one.
    pub fn parse(json: Option<&Json>) -> Result<Option<PortSchema>, String> {
        let Some(json) = json.filter(|json| !json.is_null()) else {
            return Ok(None);
        };
        if json.get("type").and_then(Json::as_str) != Some("object") || !json.is_object() {
            return Err("schemaJson.type must be object.".into());
        }
        if !storable(json) {
            return Err("schemaJson contains a character PostgreSQL cannot store.".into());
        }
        let mut properties = BTreeMap::new();
        match json.get("properties") {
            None => {}
            Some(Json::Object(nodes)) => {
                for (name, property) in nodes {
                    if !valid_name(name) {
                        return Err(format!("schemaJson.properties has invalid name '{name}'."));
                    }
                    let kind = property.get("type").and_then(Json::as_str).unwrap_or("");
                    if !property.is_object() || !TYPES.contains(&kind) {
                        return Err(format!(
                            "schemaJson.properties.{name}.type must be string, number, or boolean."
                        ));
                    }
                    properties.insert(name.clone(), kind.to_string());
                }
            }
            Some(_) => return Err("schemaJson.properties must be an object.".into()),
        }
        let mut required = BTreeSet::new();
        match json.get("required") {
            None => {}
            Some(Json::Array(names)) => {
                for name in names {
                    match name.as_str() {
                        Some(name) if properties.contains_key(name) => {
                            required.insert(name.to_string());
                        }
                        _ => return Err("schemaJson.required must name a property in schemaJson.properties.".into()),
                    }
                }
            }
            Some(_) => return Err("schemaJson.required must be an array.".into()),
        }
        Ok(Some(PortSchema { properties, required }))
    }

    /// The declared names, for a condition's `requireDeclaredAttributes`.
    pub fn names(&self) -> HashSet<String> {
        self.properties.keys().cloned().collect()
    }

    /// `PortSchema.requireCompatible`: an input's required properties must have the same type in the output.
    pub fn require_compatible(output: Option<&PortSchema>, input: Option<&PortSchema>) -> Result<(), String> {
        let (Some(output), Some(input)) = (output, input) else {
            return Ok(());
        };
        for name in &input.required {
            let target = &input.properties[name];
            if output.properties.get(name) != Some(target) {
                return Err(format!("Target port needs {name} ({target})."));
            }
        }
        Ok(())
    }
}

/// `[A-Za-z_][A-Za-z0-9_]*`.
fn valid_name(name: &str) -> bool {
    let mut characters = name.chars();
    characters
        .next()
        .is_some_and(|first| first.is_ascii_alphabetic() || first == '_')
        && characters.all(|character| character.is_ascii_alphanumeric() || character == '_')
}

/// Every key and text in the schema can be stored in jsonb.
fn storable(json: &Json) -> bool {
    match json {
        Json::String(text) => graph::storable(text),
        Json::Array(items) => items.iter().all(storable),
        Json::Object(entries) => entries
            .iter()
            .all(|(key, value)| graph::storable(key) && storable(value)),
        _ => true,
    }
}
