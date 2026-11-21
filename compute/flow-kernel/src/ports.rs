//! What a port's own values must be (`ProcessIoServiceImpl.validatePortValues`) and the standard resource types
//! (`ResourceTypes`), with the backend's messages.

use crate::decimal::Decimal;
use crate::graph::{self, Port};

/// `ResourceTypes.STANDARD`, in name order as the warning lists them.
pub const STANDARD_TYPES: [&str; 11] = [
    "api",
    "data",
    "energy",
    "file",
    "generic",
    "material",
    "parameter",
    "product",
    "signal",
    "waste",
    "water",
];

/// `ResourceTypes.isStandard`: blank means nothing to warn about; otherwise ignoring case and surrounding spaces.
pub fn is_standard(resource_type: Option<&str>) -> bool {
    match resource_type {
        None => true,
        Some(value) if graph::is_blank(value) => true,
        Some(value) => STANDARD_TYPES.contains(&graph::lower(graph::trim(value)).as_str()),
    }
}

/// `validatePortValues`: the first thing wrong with the port's direction, flags or measure.
pub fn check_values(port: &Port) -> Result<(), String> {
    let direction = port.direction.as_deref();
    if direction.is_some_and(|value| !graph::storable(value)) {
        return Err("direction contains a character PostgreSQL cannot store.".into());
    }
    let direction = direction
        .map(|value| graph::lower(graph::trim(value)))
        .unwrap_or_default();
    if direction != "input" && direction != "output" {
        return Err("direction must be input or output.".into());
    }
    flag(port.required_yn.as_deref(), "requiredYn")?;
    flag(port.allow_shortage_yn.as_deref(), "allowShortageYn")?;
    if let Some(quantity) = &port.quantity {
        if quantity.signum() < 0 {
            return Err("quantity must be zero or greater.".into());
        }
        let largest = Decimal::parse("9999999999.9999").expect("a number");
        if quantity.compare(&largest).is_gt() || quantity.strip_trailing_zeros().scale() > 4 {
            return Err("quantity must fit numeric(14,4).".into());
        }
    }
    let unit = graph::has_trimmed_text(port.unit.as_deref());
    if needs_measure(port) && (port.quantity.is_none() || !unit) {
        return Err("Material and product ports need a quantity and unit.".into());
    }
    if port.quantity.is_some() && !unit {
        return Err("A port quantity needs a unit.".into());
    }
    Ok(())
}

/// `normalizeYn`: none takes the default; otherwise Y or N in any case, with surrounding spaces.
fn flag(value: Option<&str>, field: &str) -> Result<(), String> {
    let Some(value) = value else {
        return Ok(());
    };
    if !graph::storable(value) {
        return Err(format!("{field} contains a character PostgreSQL cannot store."));
    }
    let normalized = graph::upper(graph::trim(value));
    if normalized != "Y" && normalized != "N" {
        return Err(format!("{field} must be Y or N."));
    }
    Ok(())
}

/// Material and product ports, and any port bound to an item (docs/domain/port-measurement.md PM2).
fn needs_measure(port: &Port) -> bool {
    let io_type = default_if_blank(port.io_type.as_deref(), "material");
    let kind = default_if_blank(port.resource_type.as_deref(), &io_type);
    kind == "material" || kind == "product" || graph::has_trimmed_text(port.item_id.as_deref())
}

/// `ProcessIoServiceImpl.defaultIfBlank`: trimmed and lower case, or the default.
fn default_if_blank(value: Option<&str>, default: &str) -> String {
    match value {
        Some(value) if graph::has_trimmed_text(Some(value)) => graph::lower(graph::trim(value)),
        _ => default.to_string(),
    }
}
