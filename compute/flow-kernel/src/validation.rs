//! The draft check: the backend's `WorkflowValidationService.check` and the connection contract it applies
//! (`ConnectionContract`), issue for issue: the same codes, targets, messages, evidence, fixes, hidden warnings and
//! order. Java writes the answers this must match (`fixtures/validation.json`).

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};

use flowmat_condition::Condition;
use serde_json::{Map, Value as Json, json};

use crate::decimal::Decimal;
use crate::graph::{self, Connection, Graph, Port, Process};
use crate::ports;
use crate::schema::PortSchema;

/// The units the workflow may name, each with its type (`unit_master`); anything else is unknown. As the catalog looks a
/// code up (`CatalogQueryImpl`): ignoring case, and for whether it is known, ignoring surrounding spaces too.
#[derive(Debug, Clone, Default)]
pub struct Units {
    entries: Vec<(String, String)>,
}

impl Units {
    /// From `{code: type}`.
    pub fn from_json(known: &Json) -> Units {
        let entries = known
            .as_object()
            .map(|entries| {
                entries
                    .iter()
                    .filter_map(|(code, kind)| kind.as_str().map(|kind| (code.clone(), kind.to_string())))
                    .collect()
            })
            .unwrap_or_default();
        Units { entries }
    }

    fn known(&self, code: &str) -> bool {
        self.kind(graph::trim(code)).is_some()
    }

    fn kind(&self, code: &str) -> Option<&str> {
        self.entries
            .iter()
            .find(|(known, _)| graph::equals_ignore_case(known, code))
            .map(|(_, kind)| kind.as_str())
    }
}

const ROOT_KINDS: [&str; 2] = ["input", "storage"];
const TERMINAL_KINDS: [&str; 2] = ["output", "storage"];
const GATEWAYS: [&str; 3] = ["exclusive", "join", "split"];

/// The check's answer, `{errors, warnings, issues}`, as the server sends it.
pub fn validate(graph: &Graph, units: &Units) -> Json {
    let mut process_by_id: Vec<(Option<String>, &Process)> = Vec::new();
    for process in &graph.processes {
        // A LinkedHashMap: a repeated id keeps its first place and takes the later process.
        match process_by_id.iter_mut().find(|(id, _)| *id == process.id) {
            Some(entry) => entry.1 = process,
            None => process_by_id.push((process.id.clone(), process)),
        }
    }
    let process_ids: Vec<Option<String>> = process_by_id.iter().map(|(id, _)| id.clone()).collect();
    let known_process: HashSet<Option<String>> = process_ids.iter().cloned().collect();
    let names = Names {
        processes: process_by_id
            .iter()
            .map(|(id, process)| (id.clone(), *process))
            .collect(),
    };
    let mut port_by_id: HashMap<Option<String>, &Port> = HashMap::new();
    for port in &graph.ports {
        port_by_id.insert(port.id.clone(), port);
    }
    let mut issues: Vec<Draft> = Vec::new();
    let mut connected_ports: HashSet<Option<String>> = HashSet::new();
    let mut links = Links::default();
    let mut usable: Vec<&Connection> = Vec::new();

    for port in &graph.ports {
        if let Err(message) = ports::check_values(port) {
            let draft = Draft::new(
                "error",
                "PORT_INVALID",
                port.process_id.clone(),
                port.id.clone(),
                None,
                message,
            )
            .with_text("direction", port.direction.as_deref())
            .with_decimal("quantity", port.quantity.as_ref())
            .with_text("unit", port.unit.as_deref())
            .with_text("requiredYn", port.required_yn.as_deref())
            .with_text("allowShortageYn", port.allow_shortage_yn.as_deref());
            issues.push(draft);
            continue;
        }
        let schema = match stored_schema(port) {
            Ok(schema) => schema,
            Err(message) => {
                issues.push(
                    Draft::new(
                        "error",
                        "PORT_SCHEMA_INVALID",
                        port.process_id.clone(),
                        port.id.clone(),
                        None,
                        message,
                    )
                    .with("field", json!("schemaJson")),
                );
                continue;
            }
        };
        if graph::has_text(port.validation_rule.as_deref()) {
            let rule = port.validation_rule.as_deref().unwrap_or_default();
            if let Err(message) = compile_declared(rule, schema.as_ref()) {
                issues.push(
                    Draft::new(
                        "error",
                        "EXPRESSION_INVALID",
                        port.process_id.clone(),
                        port.id.clone(),
                        None,
                        message,
                    )
                    .with("field", json!("validationRule"))
                    .with("expression", json!(rule))
                    .with_option("declaredAttributes", attributes(schema.as_ref())),
                );
            }
        }
        if unknown_unit(units, port.unit.as_deref()) {
            issues.push(
                Draft::new(
                    "warning",
                    "UNIT_UNKNOWN",
                    port.process_id.clone(),
                    port.id.clone(),
                    None,
                    format!("Port unit '{}' is not in unit_master.", or_null(port.unit.as_deref())),
                )
                .with("field", json!("unit"))
                .with_text("unit", port.unit.as_deref()),
            );
        }
        if !ports::is_standard(port.resource_type.as_deref()) {
            issues.push(
                Draft::new(
                    "warning",
                    "RESOURCE_TYPE_UNKNOWN",
                    port.process_id.clone(),
                    port.id.clone(),
                    None,
                    format!(
                        "Port resourceType '{}' is not a standard resource type.",
                        or_null(port.resource_type.as_deref())
                    ),
                )
                .with("field", json!("resourceType"))
                .with_text("resourceType", port.resource_type.as_deref())
                .with("standardTypes", json!(ports::STANDARD_TYPES)),
            );
        }
    }

    for connection in &graph.connections {
        let id = or_null(connection.id.as_deref());
        let source = port_by_id.get(&connection.from_io_id).copied();
        let target = port_by_id.get(&connection.to_io_id).copied();
        let mut missing = Vec::new();
        if !known_process.contains(&connection.from_process_id) {
            missing.push("fromProcess");
        }
        if !known_process.contains(&connection.to_process_id) {
            missing.push("toProcess");
        }
        if connection.from_io_id.is_some() && source.is_none() {
            missing.push("fromPort");
        }
        if connection.to_io_id.is_some() && target.is_none() {
            missing.push("toPort");
        }
        if !missing.is_empty() {
            let draft = Draft::new(
                "error",
                "CONNECTION_ORPHAN",
                None,
                None,
                connection.id.clone(),
                format!("Connection {id} points to a deleted process or port."),
            )
            .with("missing", json!(missing))
            .ends(connection)
            .fix(format!("delete {}", names.connection(connection)));
            links.broken(connection, push(&mut issues, draft));
            continue;
        }
        if graph::has_text(connection.condition_expr.as_deref()) {
            let expression = connection.condition_expr.as_deref().unwrap_or_default();
            let mut schema = None;
            let checked = Condition::compile(expression)
                .map_err(|error| error.to_string())
                .and_then(|condition| {
                    schema = match source {
                        None => None,
                        Some(source) => stored_schema(source)?,
                    };
                    declared(&condition, schema.as_ref())
                });
            if let Err(message) = checked {
                let draft = Draft::new(
                    "error",
                    "EXPRESSION_INVALID",
                    None,
                    None,
                    connection.id.clone(),
                    message,
                )
                .with("field", json!("conditionExpr"))
                .with("expression", json!(expression))
                .with_option("sourceAttributes", attributes(schema.as_ref()))
                .ends(connection);
                links.broken(connection, push(&mut issues, draft));
                continue;
            }
        }
        if let Err(message) = connection_contract(connection, &graph.connections, &port_by_id, units) {
            let draft = Draft::new(
                "error",
                "CONNECTION_INCOMPATIBLE",
                None,
                None,
                connection.id.clone(),
                message,
            )
            .ends(connection);
            links.broken(connection, push(&mut issues, draft));
            continue;
        }
        usable.push(connection);
        links.usable(connection);
        if connection.from_io_id.is_some() {
            connected_ports.insert(connection.from_io_id.clone());
        }
        if connection.to_io_id.is_some() {
            connected_ports.insert(connection.to_io_id.clone());
        }
        if let (Some(source), Some(target)) = (source, target)
            && (source.schema.is_none() || target.schema.is_none())
        {
            issues.push(
                Draft::new(
                    "warning",
                    "SCHEMA_UNVERIFIED",
                    None,
                    None,
                    connection.id.clone(),
                    format!("Connection {id} has a port without schemaJson."),
                )
                .with("sourceHasSchema", json!(source.schema.is_some()))
                .with("targetHasSchema", json!(target.schema.is_some()))
                .ends(connection),
            );
        }
        if unknown_unit(units, connection.unit.as_deref()) {
            issues.push(
                Draft::new(
                    "warning",
                    "UNIT_UNKNOWN",
                    None,
                    None,
                    connection.id.clone(),
                    format!(
                        "Connection unit '{}' is not in unit_master.",
                        or_null(connection.unit.as_deref())
                    ),
                )
                .with("field", json!("unit"))
                .with_text("unit", connection.unit.as_deref()),
            );
        }
    }

    for port in &graph.ports {
        let input = port
            .direction
            .as_deref()
            .is_some_and(|value| graph::equals_ignore_case(value, "input"));
        let required = port
            .required_yn
            .as_deref()
            .is_some_and(|value| graph::equals_ignore_case(value, "Y"));
        if input && required && !connected_ports.contains(&port.id) {
            issues.push(
                Draft::new(
                    "error",
                    "REQUIRED_INPUT_UNCONNECTED",
                    port.process_id.clone(),
                    port.id.clone(),
                    None,
                    format!("Required input port {} has no connection.", or_null(port.id.as_deref())),
                )
                .with_text("requiredYn", port.required_yn.as_deref())
                .with("usableConnections", json!(0))
                .fix(format!("connect an output to input {}", names.port(port)))
                .fix(format!("make input {} optional", names.port(port))),
            );
        }
    }
    let mut isolated: HashMap<Option<String>, usize> = HashMap::new();
    for process in &graph.processes {
        let id = &process.id;
        if links.incoming(id) + links.outgoing(id) > 0 {
            continue;
        }
        if let Some(cause) = links.first_broken(id) {
            issues[cause].suppress("PROCESS_ISOLATED");
            continue;
        }
        let draft = Draft::new(
            "warning",
            "PROCESS_ISOLATED",
            id.clone(),
            None,
            None,
            format!("Process {} has no connection.", or_null(id.as_deref())),
        )
        .with("incoming", json!(0))
        .with("outgoing", json!(0))
        .fix(format!("connect {} to the step before or after it", names.process(id)));
        isolated.insert(id.clone(), push(&mut issues, draft));
    }
    endpoint_issues(&graph.processes, &links, &isolated, &names, &mut issues);
    gateway_issues(&graph.processes, &usable, &links, &names, &mut issues);
    let cycle = cycle_members(&process_ids, &usable);
    if !cycle.is_empty() {
        issues.push(
            Draft::new(
                "warning",
                "CYCLE",
                None,
                None,
                None,
                "Workflow contains a directed cycle; automatic execution must define loop semantics.".into(),
            )
            .with("processIds", json!(cycle)),
        );
    }
    // A stable sort: errors first, each kind in the order found.
    let (errors, warnings): (Vec<Draft>, Vec<Draft>) = issues.into_iter().partition(|issue| issue.severity == "error");
    let error_count = errors.len();
    let warning_count = warnings.len();
    let issues: Vec<Json> = errors.into_iter().chain(warnings).map(Draft::build).collect();
    json!({"errors": error_count, "warnings": warning_count, "issues": issues})
}

/// The port's schema as saving stored it: `None` without one.
fn stored_schema(port: &Port) -> Result<Option<PortSchema>, String> {
    PortSchema::parse(port.schema.as_ref())
}

fn compile_declared(source: &str, schema: Option<&PortSchema>) -> Result<(), String> {
    let condition = Condition::compile(source).map_err(|error| error.to_string())?;
    declared(&condition, schema)
}

/// `requireDeclaredAttributes`: without a schema, nothing to check against.
fn declared(condition: &Condition, schema: Option<&PortSchema>) -> Result<(), String> {
    match schema {
        None => Ok(()),
        Some(schema) => condition
            .require_declared_attributes(&schema.names())
            .map_err(|error| error.to_string()),
    }
}

/// The declared names in name order, or none without a schema.
fn attributes(schema: Option<&PortSchema>) -> Option<Json> {
    schema.map(|schema| json!(schema.properties.keys().collect::<Vec<_>>()))
}

fn unknown_unit(units: &Units, unit: Option<&str>) -> bool {
    graph::has_text(unit) && !units.known(unit.unwrap_or_default())
}

/// Java's string concatenation of a possibly null value.
fn or_null(value: Option<&str>) -> &str {
    value.unwrap_or("null")
}

/// `ConnectionContract.check` with nothing inferred, the connection itself left out of the duplicate check.
fn connection_contract(
    connection: &Connection,
    all: &[Connection],
    ports: &HashMap<Option<String>, &Port>,
    units: &Units,
) -> Result<(), String> {
    let mut condition = None;
    if graph::has_text(connection.condition_expr.as_deref()) {
        let source = connection.condition_expr.as_deref().unwrap_or_default();
        condition = Some(Condition::compile(source).map_err(|error| error.to_string())?);
    }
    if connection.from_process_id == connection.to_process_id {
        return Err("A connection cannot link a process to itself.".into());
    }
    if let (Some(from), Some(to)) = (&connection.from_io_id, &connection.to_io_id) {
        let duplicate = all.iter().any(|existing| {
            existing.id != connection.id
                && existing.from_io_id.as_ref() == Some(from)
                && existing.to_io_id.as_ref() == Some(to)
        });
        if duplicate {
            return Err("Ports are already connected.".into());
        }
    }
    let source = connection
        .from_io_id
        .as_ref()
        .and_then(|id| ports.get(&Some(id.clone())).copied());
    if connection.from_io_id.is_some() && source.is_none() {
        return Err("Connection fromIoId points to a deleted port.".into());
    }
    let target = connection
        .to_io_id
        .as_ref()
        .and_then(|id| ports.get(&Some(id.clone())).copied());
    if connection.to_io_id.is_some() && target.is_none() {
        return Err("Connection toIoId points to a deleted port.".into());
    }
    let is = |value: &Option<String>, direction: &str| {
        value
            .as_deref()
            .is_some_and(|value| graph::equals_ignore_case(value, direction))
    };
    if let Some(source) = source
        && (source.process_id != connection.from_process_id || !is(&source.direction, "output"))
    {
        return Err("Connection fromIoId must be an output port of fromProcessId.".into());
    }
    if let Some(target) = target
        && (target.process_id != connection.to_process_id || !is(&target.direction, "input"))
    {
        return Err("Connection toIoId must be an input port of toProcessId.".into());
    }
    let source_schema = match source {
        None => None,
        Some(source) => stored_schema(source)?,
    };
    if let Some(condition) = &condition {
        declared(condition, source_schema.as_ref())?;
    }
    let source_item = source.and_then(|port| port.item_id.as_ref());
    let target_item = target.and_then(|port| port.item_id.as_ref());
    if let (Some(source_item), Some(target_item)) = (source_item, target_item)
        && source_item != target_item
    {
        return Err("Connected ports itemId must match.".into());
    }
    if let Some(selected) = &connection.item_id
        && (source_item.is_some_and(|item| item != selected) || target_item.is_some_and(|item| item != selected))
    {
        return Err("Connection itemId must match the ports.".into());
    }
    if let (Some(source), Some(target)) = (source, target) {
        let source_type = resource_kind(source);
        let target_type = resource_kind(target);
        if source_type != target_type {
            return Err("Connected ports resourceType must match.".into());
        }
        let target_schema = stored_schema(target)?;
        PortSchema::require_compatible(source_schema.as_ref(), target_schema.as_ref())?;
    }
    let mut kinds = HashSet::new();
    for unit in [
        source.and_then(|port| port.unit.as_deref()),
        target.and_then(|port| port.unit.as_deref()),
        connection.unit.as_deref(),
    ]
    .into_iter()
    .flatten()
    {
        if !graph::is_blank(unit)
            && let Some(kind) = units.kind(unit)
        {
            kinds.insert(kind);
        }
    }
    if kinds.len() > 1 {
        return Err("Connection unit type must match port units.".into());
    }
    Ok(())
}

/// As saving reads a resource type: trimmed and lower case, the old `ioType` when blank.
fn resource_kind(port: &Port) -> Option<String> {
    match port.resource_type.as_deref() {
        Some(value) if graph::has_text(Some(value)) => Some(graph::lower(graph::trim(value))),
        _ => port.io_type.clone(),
    }
}

/// The stored node type as the palette reads it: blank is a process and the old "decision" is an output.
fn kind(process: &Process) -> String {
    match process.node_type.as_deref() {
        Some(value) if graph::has_text(Some(value)) => {
            let lower = graph::lower(graph::trim(value));
            if lower == "decision" { "output".into() } else { lower }
        }
        _ => "process".into(),
    }
}

/// Starts and ends where the workflow does not expect them.
fn endpoint_issues(
    processes: &[Process],
    links: &Links,
    isolated: &HashMap<Option<String>, usize>,
    names: &Names,
    issues: &mut Vec<Draft>,
) {
    let inputs = processes.iter().filter(|process| kind(process) == "input").count();
    let outputs = processes.iter().filter(|process| kind(process) == "output").count();
    for process in processes {
        let id = &process.id;
        let kind = kind(process);
        if inputs > 0
            && links.incoming(id) == 0
            && !ROOT_KINDS.contains(&kind.as_str())
            && !GATEWAYS.contains(&kind.as_str())
        {
            match links.first_broken_in(id).or_else(|| isolated.get(id).copied()) {
                Some(cause) => issues[cause].suppress("UNEXPECTED_ROOT"),
                None => issues.push(
                    Draft::new(
                        "warning",
                        "UNEXPECTED_ROOT",
                        id.clone(),
                        None,
                        None,
                        format!(
                            "Process {} has no incoming connection, but this workflow marks where material enters with Input nodes.",
                            or_null(id.as_deref())
                        ),
                    )
                    .with("nodeType", json!(kind))
                    .with("incoming", json!(0))
                    .with("inputNodes", json!(inputs))
                    .with("allowedRootKinds", json!(ROOT_KINDS))
                    .fix(format!("connect an earlier step into {}", names.process(id)))
                    .fix(format!(
                        "change {} to an Input node if material enters the workflow there",
                        names.process(id)
                    )),
                ),
            }
        }
        if outputs > 0
            && links.outgoing(id) == 0
            && !TERMINAL_KINDS.contains(&kind.as_str())
            && kind != "exclusive"
            && kind != "split"
        {
            match links.first_broken_out(id).or_else(|| isolated.get(id).copied()) {
                Some(cause) => issues[cause].suppress("UNEXPECTED_TERMINAL"),
                None => issues.push(
                    Draft::new(
                        "warning",
                        "UNEXPECTED_TERMINAL",
                        id.clone(),
                        None,
                        None,
                        format!(
                            "Process {} has no outgoing connection, but this workflow marks where results leave with Output nodes.",
                            or_null(id.as_deref())
                        ),
                    )
                    .with("nodeType", json!(kind))
                    .with("outgoing", json!(0))
                    .with("outputNodes", json!(outputs))
                    .with("allowedTerminalKinds", json!(TERMINAL_KINDS))
                    .fix(format!("connect {} to a later step", names.process(id)))
                    .fix(format!(
                        "change {} to an Output node if results leave the workflow there",
                        names.process(id)
                    )),
                ),
            }
        }
    }
}

/// Exclusive gateways, Splits and Joins, and the loss rate and delay of each connection.
fn gateway_issues(
    processes: &[Process],
    connections: &[&Connection],
    links: &Links,
    names: &Names,
    issues: &mut Vec<Draft>,
) {
    let mut gateways: Vec<Option<String>> = Vec::new();
    let mut splits: Vec<Option<String>> = Vec::new();
    for process in processes {
        let kind = kind(process);
        if kind == "exclusive" && !gateways.contains(&process.id) {
            gateways.push(process.id.clone());
        }
        if kind == "split" && !splits.contains(&process.id) {
            splits.push(process.id.clone());
        }
    }
    let mut outgoing: HashMap<Option<String>, Vec<&Connection>> = HashMap::new();
    for connection in connections {
        let id = or_null(connection.id.as_deref());
        let from = &connection.from_process_id;
        outgoing.entry(from.clone()).or_default().push(connection);
        if connection.default_route && !gateways.contains(from) {
            issues.push(
                Draft::new(
                    "error",
                    "DEFAULT_ROUTE_OUTSIDE_GATEWAY",
                    None,
                    None,
                    connection.id.clone(),
                    format!("Connection {id} is a default connection, but only an Exclusive gateway has one."),
                )
                .with("field", json!("defaultRouteYn"))
                .with_option("fromNodeType", names.kind(from).map(Json::from))
                .ends(connection)
                .fix(format!("clear \"default\" on {}", names.connection(connection))),
            );
        }
        if let Some(split_kind) = &connection.split_kind
            && !splits.contains(from)
        {
            issues.push(
                Draft::new(
                    "error",
                    "SPLIT_OUTSIDE_GATEWAY",
                    None,
                    None,
                    connection.id.clone(),
                    format!("Connection {id} has a split share, but only a Split gateway divides its output."),
                )
                .with("field", json!("splitKind"))
                .with("splitKind", json!(split_kind))
                .with_option("fromNodeType", names.kind(from).map(Json::from))
                .ends(connection)
                .fix(format!("clear the split share on {}", names.connection(connection))),
            );
        }
        if let Some(rate) = &connection.loss_rate
            && (rate.signum() < 0 || rate.compare(&Decimal::from_int(1)).is_ge())
        {
            let mut draft = Draft::new(
                "error",
                "LOSS_RATE_RANGE",
                None,
                None,
                connection.id.clone(),
                format!(
                    "Connection {id} has loss rate {}; a loss rate is a fraction of at least 0 and below 1 (ADR-009).",
                    rate.plain()
                ),
            )
            .with("field", json!("lossRate"))
            .with("lossRate", rate.to_json())
            .with("atLeast", json!(0))
            .with("below", json!(1));
            if let Some(fraction) = loss_fraction(rate) {
                let percent = if rate.signum() > 0 {
                    format!(" ({}% as a fraction)", rate.plain())
                } else {
                    String::new()
                };
                draft = draft.fix(format!(
                    "set the loss rate on {} to {}{percent}",
                    names.connection(connection),
                    fraction.plain()
                ));
            }
            issues.push(draft);
        }
        if let Some(delay) = &connection.delay_time_sec
            && delay.signum() < 0
        {
            issues.push(
                Draft::new(
                    "error",
                    "DELAY_RANGE",
                    None,
                    None,
                    connection.id.clone(),
                    format!(
                        "Connection {id} has delay {} s; a delay is 0 or more seconds (ADR-010).",
                        delay.plain()
                    ),
                )
                .with("field", json!("delayTimeSec"))
                .with("delaySeconds", delay.to_json())
                .with("atLeast", json!(0))
                .fix(format!(
                    "set the delay on {} to {} s",
                    names.connection(connection),
                    delay.negate().plain()
                )),
            );
        }
    }
    split_issues(&splits, &outgoing, links, names, issues);
    for process in processes {
        if kind(process) != "join" {
            continue;
        }
        let join = &process.id;
        let count = links.incoming(join);
        if count == 0 {
            issues.push(
                Draft::new(
                    "error",
                    "JOIN_WITHOUT_INPUT",
                    join.clone(),
                    None,
                    None,
                    format!(
                        "Join {} has no incoming connection; it waits for the branches that lead into it.",
                        or_null(join.as_deref())
                    ),
                )
                .with("incoming", json!(0))
                .fix(format!("connect the branches that lead into {}", names.process(join))),
            );
        } else if count == 1 {
            let cause = if count + links.broken_in(join) >= 2 {
                links.first_broken_in(join)
            } else {
                None
            };
            match cause {
                Some(cause) => issues[cause].suppress("JOIN_SINGLE_INPUT"),
                None => issues.push(
                    Draft::new(
                        "warning",
                        "JOIN_SINGLE_INPUT",
                        join.clone(),
                        None,
                        None,
                        format!(
                            "Join {} has one incoming connection, so it has nothing to wait for.",
                            or_null(join.as_deref())
                        ),
                    )
                    .with("incoming", json!(1))
                    .fix(format!("connect another branch into {}", names.process(join))),
                ),
            }
        }
    }
    for gateway in &gateways {
        let label = or_null(gateway.as_deref());
        let out = outgoing.get(gateway).cloned().unwrap_or_default();
        let defaults: Vec<&str> = out
            .iter()
            .filter(|connection| connection.default_route)
            .map(|connection| or_null(connection.id.as_deref()))
            .collect();
        if defaults.len() > 1 {
            issues.push(
                Draft::new(
                    "error",
                    "GATEWAY_DEFAULTS",
                    gateway.clone(),
                    None,
                    None,
                    format!(
                        "Exclusive gateway {label} has {} default connections; keep at most one.",
                        defaults.len()
                    ),
                )
                .with("defaultConnections", json!(defaults)),
            );
        }
        if links.incoming(gateway) == 0 {
            issues.push(
                Draft::new(
                    "error",
                    "GATEWAY_WITHOUT_INPUT",
                    gateway.clone(),
                    None,
                    None,
                    format!(
                        "Exclusive gateway {label} has no incoming connection; it passes on what an earlier step gives it."
                    ),
                )
                .with("incoming", json!(0))
                .fix(format!("connect an earlier step into {}", names.process(gateway))),
            );
        }
        if out.len() < 2 {
            let more = if out.is_empty() {
                " to two later steps"
            } else {
                " to one more later step"
            };
            single_route(
                issues,
                links,
                gateway,
                out.len(),
                "GATEWAY_SINGLE_ROUTE",
                format!(
                    "Exclusive gateway {label} has fewer than two outgoing connections, so it has nothing to choose."
                ),
                format!("connect {}{more}", names.process(gateway)),
            );
        }
        let mut by_priority: BTreeMap<i64, Vec<&str>> = BTreeMap::new();
        for connection in &out {
            if connection.default_route {
                if graph::has_text(connection.condition_expr.as_deref()) {
                    let connection_id = or_null(connection.id.as_deref());
                    issues.push(
                        Draft::new(
                            "warning",
                            "DEFAULT_ROUTE_CONDITION_IGNORED",
                            None,
                            None,
                            connection.id.clone(),
                            format!(
                                "Default connection {connection_id} is taken only when no other connection's condition holds, so its own condition is not used."
                            ),
                        )
                        .with("field", json!("conditionExpr"))
                        .with_text("expression", connection.condition_expr.as_deref())
                        .fix(format!("clear the condition on {}", names.connection(connection))),
                    );
                }
                continue;
            }
            by_priority
                .entry(connection.priority.unwrap_or(0))
                .or_default()
                .push(or_null(connection.id.as_deref()));
        }
        for (priority, tied) in by_priority {
            if tied.len() < 2 {
                continue;
            }
            issues.push(
                Draft::new(
                    "warning",
                    "GATEWAY_PRIORITY_TIE",
                    gateway.clone(),
                    None,
                    None,
                    format!(
                        "Exclusive gateway {label} has more than one connection with priority {priority}; the one drawn first is tried first."
                    ),
                )
                .with("priority", json!(priority))
                .with("connections", json!(tied)),
            );
        }
    }
}

fn split_issues(
    splits: &[Option<String>],
    outgoing: &HashMap<Option<String>, Vec<&Connection>>,
    links: &Links,
    names: &Names,
    issues: &mut Vec<Draft>,
) {
    let hundred = Decimal::from_int(100);
    for split in splits {
        let label = or_null(split.as_deref());
        let out = outgoing.get(split).cloned().unwrap_or_default();
        if links.incoming(split) == 0 {
            issues.push(
                Draft::new(
                    "error",
                    "SPLIT_WITHOUT_INPUT",
                    split.clone(),
                    None,
                    None,
                    format!(
                        "Split gateway {label} has no incoming connection; it divides what an earlier step gives it."
                    ),
                )
                .with("incoming", json!(0))
                .fix(format!("connect an earlier step into {}", names.process(split))),
            );
        }
        if out.len() < 2 {
            let more = if out.is_empty() {
                " to two later steps"
            } else {
                " to one more later step"
            };
            single_route(
                issues,
                links,
                split,
                out.len(),
                "SPLIT_SINGLE_ROUTE",
                format!(
                    "Split gateway {label} has fewer than two outgoing connections, so it has nothing to divide between."
                ),
                format!("connect {}{more} with a share", names.process(split)),
            );
        }
        let mut rests = Vec::new();
        let mut missing = Vec::new();
        let mut fixed = false;
        let mut shares = Decimal::zero();
        for connection in &out {
            match connection.split_kind.as_deref() {
                None => missing.push(or_null(connection.id.as_deref())),
                Some("rest") => rests.push(or_null(connection.id.as_deref())),
                Some("fixed") => fixed = true,
                Some(_) => {
                    if let Some(value) = &connection.split_value {
                        shares = shares.add(value);
                    }
                }
            }
        }
        for connection in &out {
            let connection_id = or_null(connection.id.as_deref());
            if connection.split_kind.is_none() {
                let mut draft = Draft::new(
                    "error",
                    "SPLIT_SHARE_MISSING",
                    None,
                    None,
                    connection.id.clone(),
                    format!(
                        "Connection {connection_id} leaves Split gateway {label} without a share; give it a percent, a fixed quantity or the rest."
                    ),
                )
                .with("field", json!("splitKind"))
                .with_text("splitProcessId", split.as_deref());
                if rests.is_empty() && missing.len() == 1 {
                    draft = draft.fix(format!("make {} the rest", names.connection(connection)));
                }
                issues.push(draft);
            }
            if graph::has_text(connection.condition_expr.as_deref()) {
                issues.push(
                    Draft::new(
                        "error",
                        "SPLIT_CONDITION",
                        None,
                        None,
                        connection.id.clone(),
                        format!(
                            "Connection {connection_id} leaves Split gateway {label} with a condition; a Split divides what it gets without conditions."
                        ),
                    )
                    .with("field", json!("conditionExpr"))
                    .with_text("expression", connection.condition_expr.as_deref())
                    .fix(format!("clear the condition on {}", names.connection(connection))),
                );
            }
        }
        if rests.len() > 1 {
            issues.push(
                Draft::new(
                    "error",
                    "SPLIT_REST_DUPLICATE",
                    split.clone(),
                    None,
                    None,
                    format!(
                        "Split gateway {label} has {} rest connections; keep at most one.",
                        rests.len()
                    ),
                )
                .with("restConnections", json!(rests)),
            );
        }
        if shares.compare(&hundred).is_gt() {
            issues.push(
                Draft::new(
                    "error",
                    "SPLIT_SHARES_OVER",
                    split.clone(),
                    None,
                    None,
                    format!("Split gateway {label} shares out {}%, more than 100%.", shares.plain()),
                )
                .with("percentTotal", shares.to_json()),
            );
        } else if rests.is_empty()
            && missing.is_empty()
            && !out.is_empty()
            && (fixed || !shares.compare(&hundred).is_eq())
        {
            issues.push(
                Draft::new(
                    "error",
                    "SPLIT_NO_REST",
                    split.clone(),
                    None,
                    None,
                    format!(
                        "Split gateway {label} needs a rest connection: without one, its percents must add up to exactly 100 and it cannot have fixed quantities."
                    ),
                )
                .with("percentTotal", shares.to_json())
                .with("fixedShares", json!(fixed)),
            );
        }
    }
}

/// A gateway with fewer than two routes, unless a broken outgoing connection is why: then that error hides it.
fn single_route(
    issues: &mut Vec<Draft>,
    links: &Links,
    gateway: &Option<String>,
    routes: usize,
    code: &str,
    message: String,
    fix: String,
) {
    if routes + links.broken_out(gateway) >= 2
        && let Some(cause) = links.first_broken_out(gateway)
    {
        issues[cause].suppress(code);
        return;
    }
    issues.push(
        Draft::new("warning", code, gateway.clone(), None, None, message)
            .with("outgoing", json!(routes))
            .fix(fix),
    );
}

/// A loss rate typed as a percent (1 to below 100) or with a stray minus sign, as the fraction it would be; else none.
fn loss_fraction(rate: &Decimal) -> Option<Decimal> {
    let one = Decimal::from_int(1);
    if rate.signum() < 0 && rate.negate().compare(&one).is_lt() {
        return Some(rate.negate());
    }
    if rate.compare(&one).is_ge() && rate.compare(&Decimal::from_int(100)).is_lt() {
        return Some(rate.move_point_left(2));
    }
    None
}

/// The processes on a directed cycle, in creation order; empty when there is none.
fn cycle_members(process_ids: &[Option<String>], connections: &[&Connection]) -> Vec<String> {
    let mut indegrees: HashMap<&Option<String>, i64> = process_ids.iter().map(|id| (id, 0)).collect();
    let mut outgoing: HashMap<&Option<String>, Vec<&Option<String>>> = HashMap::new();
    for connection in connections {
        outgoing
            .entry(&connection.from_process_id)
            .or_default()
            .push(&connection.to_process_id);
        if let Some(degree) = indegrees.get_mut(&connection.to_process_id) {
            *degree += 1;
        }
    }
    let mut ready: VecDeque<&Option<String>> = process_ids.iter().filter(|id| indegrees.get(id) == Some(&0)).collect();
    let mut placed: HashSet<&Option<String>> = HashSet::new();
    while let Some(current) = ready.pop_front() {
        placed.insert(current);
        for next in outgoing.get(current).cloned().unwrap_or_default() {
            if let Some(degree) = indegrees.get_mut(next) {
                *degree -= 1;
                if *degree == 0 {
                    ready.push_back(next);
                }
            }
        }
    }
    if placed.len() == process_ids.len() {
        return Vec::new();
    }
    process_ids
        .iter()
        .filter(|id| !placed.contains(id) && reaches_itself(&outgoing, id))
        .map(|id| or_null(id.as_deref()).to_string())
        .collect()
}

fn reaches_itself(outgoing: &HashMap<&Option<String>, Vec<&Option<String>>>, start: &Option<String>) -> bool {
    let mut seen: HashSet<&Option<String>> = HashSet::new();
    let mut pending: VecDeque<&Option<String>> = outgoing.get(start).cloned().unwrap_or_default().into();
    while let Some(current) = pending.pop_front() {
        if current == start {
            return true;
        }
        if seen.insert(current) {
            pending.extend(outgoing.get(current).cloned().unwrap_or_default());
        }
    }
    false
}

fn push(issues: &mut Vec<Draft>, draft: Draft) -> usize {
    issues.push(draft);
    issues.len() - 1
}

/// How a fix names what to edit: by the names people gave, falling back to IDs.
struct Names<'a> {
    processes: HashMap<Option<String>, &'a Process>,
}

impl Names<'_> {
    fn process(&self, id: &Option<String>) -> String {
        let name = self
            .processes
            .get(id)
            .and_then(|process| process.name.as_deref())
            .filter(|name| graph::has_text(Some(name)));
        format!("\"{}\"", name.unwrap_or(or_null(id.as_deref())))
    }

    fn kind(&self, id: &Option<String>) -> Option<String> {
        self.processes.get(id).map(|process| kind(process))
    }

    fn port(&self, port: &Port) -> String {
        let label = [&port.io_name, &port.item_id]
            .into_iter()
            .find_map(|value| value.as_deref().filter(|value| graph::has_text(Some(value))))
            .unwrap_or(or_null(port.id.as_deref()));
        format!("port \"{label}\" of {}", self.process(&port.process_id))
    }

    fn connection(&self, connection: &Connection) -> String {
        format!(
            "the connection from {} to {}",
            self.process(&connection.from_process_id),
            self.process(&connection.to_process_id)
        )
    }
}

/// Per process, the connections that count and the broken ones, whose errors explain what a missing connection causes.
#[derive(Default)]
struct Links {
    incoming: HashMap<Option<String>, usize>,
    outgoing: HashMap<Option<String>, usize>,
    broken_incoming: HashMap<Option<String>, Vec<usize>>,
    broken_outgoing: HashMap<Option<String>, Vec<usize>>,
}

impl Links {
    fn usable(&mut self, connection: &Connection) {
        *self.outgoing.entry(connection.from_process_id.clone()).or_default() += 1;
        *self.incoming.entry(connection.to_process_id.clone()).or_default() += 1;
    }

    fn broken(&mut self, connection: &Connection, cause: usize) {
        self.broken_outgoing
            .entry(connection.from_process_id.clone())
            .or_default()
            .push(cause);
        self.broken_incoming
            .entry(connection.to_process_id.clone())
            .or_default()
            .push(cause);
    }

    fn incoming(&self, id: &Option<String>) -> usize {
        self.incoming.get(id).copied().unwrap_or(0)
    }

    fn outgoing(&self, id: &Option<String>) -> usize {
        self.outgoing.get(id).copied().unwrap_or(0)
    }

    fn broken_in(&self, id: &Option<String>) -> usize {
        self.broken_incoming.get(id).map_or(0, Vec::len)
    }

    fn broken_out(&self, id: &Option<String>) -> usize {
        self.broken_outgoing.get(id).map_or(0, Vec::len)
    }

    fn first_broken_in(&self, id: &Option<String>) -> Option<usize> {
        self.broken_incoming.get(id).and_then(|causes| causes.first().copied())
    }

    fn first_broken_out(&self, id: &Option<String>) -> Option<usize> {
        self.broken_outgoing.get(id).and_then(|causes| causes.first().copied())
    }

    fn first_broken(&self, id: &Option<String>) -> Option<usize> {
        self.first_broken_in(id).or_else(|| self.first_broken_out(id))
    }
}

/// An issue while the check runs: a cause found later can still add the warnings it hides.
struct Draft {
    severity: &'static str,
    code: String,
    process_id: Option<String>,
    io_id: Option<String>,
    connection_id: Option<String>,
    message: String,
    evidence: Map<String, Json>,
    fixes: Vec<String>,
    hidden: Vec<String>,
}

impl Draft {
    fn new(
        severity: &'static str,
        code: &str,
        process_id: Option<String>,
        io_id: Option<String>,
        connection_id: Option<String>,
        message: String,
    ) -> Draft {
        Draft {
            severity,
            code: code.to_string(),
            process_id,
            io_id,
            connection_id,
            message,
            evidence: Map::new(),
            fixes: Vec::new(),
            hidden: Vec::new(),
        }
    }

    fn with(mut self, key: &str, value: Json) -> Draft {
        self.evidence.insert(key.to_string(), value);
        self
    }

    /// Evidence is left out when its value is null.
    fn with_option(self, key: &str, value: Option<Json>) -> Draft {
        match value {
            Some(value) => self.with(key, value),
            None => self,
        }
    }

    fn with_text(self, key: &str, value: Option<&str>) -> Draft {
        self.with_option(key, value.map(Json::from))
    }

    fn with_decimal(self, key: &str, value: Option<&Decimal>) -> Draft {
        self.with_option(key, value.map(Decimal::to_json))
    }

    fn ends(self, connection: &Connection) -> Draft {
        self.with_text("fromProcessId", connection.from_process_id.as_deref())
            .with_text("toProcessId", connection.to_process_id.as_deref())
            .with_text("fromIoId", connection.from_io_id.as_deref())
            .with_text("toIoId", connection.to_io_id.as_deref())
    }

    fn fix(mut self, edit: String) -> Draft {
        self.fixes.push(edit);
        self
    }

    fn suppress(&mut self, code: &str) {
        if !self.hidden.iter().any(|hidden| hidden == code) {
            self.hidden.push(code.to_string());
        }
    }

    fn build(self) -> Json {
        json!({
            "severity": self.severity,
            "code": self.code,
            "processId": self.process_id,
            "ioId": self.io_id,
            "connectionId": self.connection_id,
            "message": self.message,
            "evidence": self.evidence,
            "supportedFixes": self.fixes,
            "suppresses": self.hidden,
        })
    }
}
