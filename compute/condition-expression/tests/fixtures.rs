//! The shared fixtures: Rust must answer every case as Java recorded it in `fixtures/`.

use std::collections::{BTreeSet, HashSet};
use std::fs;
use std::path::Path;

use flowmat_condition::{Condition, DIGIT_ZEROS, Values, java_digit, java_whitespace};
use serde_json::Value as Json;

fn read(name: &str) -> Json {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures").join(name);
    let text = fs::read_to_string(&path).unwrap_or_else(|error| panic!("{}: {error}", path.display()));
    serde_json::from_str(&text).unwrap_or_else(|error| panic!("{}: {error}", path.display()))
}

fn list<'a>(json: &'a Json, name: &str) -> &'a Vec<Json> {
    json[name].as_array().unwrap_or_else(|| panic!("{name} is a list"))
}

/// What this implementation answers, in the form Java's outcomes take.
fn outcome(source: &str, declared: Option<&HashSet<String>>, snapshots: &[Values]) -> Json {
    let mut outcome = serde_json::Map::new();
    outcome.insert("source".into(), source.into());
    let condition = match Condition::compile(source) {
        Ok(condition) => condition,
        Err(error) => {
            let message = format!("Invalid condition at position {}: {}", error.position, error.reason);
            assert_eq!(error.to_string(), message);
            outcome.insert(
                "error".into(),
                serde_json::json!({"position": error.position, "reason": error.reason}),
            );
            return Json::Object(outcome);
        }
    };
    outcome.insert(
        "attributes".into(),
        condition.attribute_references().into_iter().collect::<Vec<_>>().into(),
    );
    let results: String = snapshots
        .iter()
        .map(|values| if condition.evaluate(values) { '1' } else { '0' })
        .collect();
    outcome.insert("results".into(), results.into());
    if let Some(declared) = declared {
        let error = condition.require_declared_attributes(declared).err().map_or(
            Json::Null,
            |error| serde_json::json!({"position": error.position, "reason": error.reason}),
        );
        outcome.insert("declaredError".into(), error);
    }
    Json::Object(outcome)
}

fn compare(name: &str, recorded: &[Json], answered: &[Json]) {
    assert_eq!(recorded.len(), answered.len(), "{name}: entries");
    let differences: Vec<String> = recorded
        .iter()
        .zip(answered)
        .enumerate()
        .filter(|(_, (recorded, answered))| recorded != answered)
        .map(|(index, (recorded, answered))| format!("#{index}\n  Java {recorded}\n  Rust {answered}"))
        .collect();
    assert!(
        differences.is_empty(),
        "{name}: {} of {} cases differ from Java\n{}",
        differences.len(),
        recorded.len(),
        differences.join("\n")
    );
}

#[test]
fn answers_the_hand_picked_cases_as_java_does() {
    let input = read("cases.json");
    let expected = read("cases.expected.json");
    let snapshots: Vec<Values> = list(&input, "snapshots")
        .iter()
        .map(|entry| Values::from_snapshot(&entry["snapshot"]))
        .collect();
    let answered: Vec<Json> = list(&input, "cases")
        .iter()
        .map(|entry| {
            let declared: Option<HashSet<String>> = entry.get("declared").map(|names| {
                names
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|name| name.as_str().unwrap().to_owned())
                    .collect()
            });
            outcome(entry["source"].as_str().unwrap(), declared.as_ref(), &snapshots)
        })
        .collect();
    compare("cases.expected.json", list(&expected, "outcomes"), &answered);
}

#[test]
fn answers_the_generated_cases_as_java_does() {
    let corpus = read("generated.json");
    let snapshots: Vec<Values> = list(&corpus, "snapshots").iter().map(Values::from_snapshot).collect();
    let recorded = list(&corpus, "cases");
    let answered: Vec<Json> = recorded
        .iter()
        .map(|entry| outcome(entry["source"].as_str().unwrap(), None, &snapshots))
        .collect();
    compare("generated.json", recorded, &answered);
}

#[test]
fn uses_the_character_classes_the_jdk_recorded() {
    let expected = read("cases.expected.json");
    let classes = &expected["characterClasses"];
    let numbers = |name: &str| -> BTreeSet<u16> {
        list(classes, name)
            .iter()
            .map(|unit| u16::try_from(unit.as_u64().unwrap()).unwrap())
            .collect()
    };
    let zeros = numbers("digitZeros");
    assert_eq!(DIGIT_ZEROS.iter().copied().collect::<BTreeSet<_>>(), zeros);
    let whitespace = numbers("whitespace");
    for unit in 0..=u16::MAX {
        assert_eq!(
            java_whitespace(unit),
            whitespace.contains(&unit),
            "whitespace U+{unit:04X}"
        );
        let digit = zeros
            .range(..=unit)
            .next_back()
            .map(|zero| unit - zero)
            .filter(|value| *value < 10);
        assert_eq!(java_digit(unit).map(u16::from), digit, "digit U+{unit:04X}");
    }
}
