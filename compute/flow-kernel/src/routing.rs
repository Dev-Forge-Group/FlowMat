//! Routing a published revision: the backend's `PublishedFlowRunGraphReader`, `FlowRunGraph` and
//! `FlowRunPortContracts`, decision for decision: which outgoing connections a step's output goes along, with what
//! quantity and loss, or why it cannot go on. Java writes the answers this must match (`fixtures/routing.json`).

use std::collections::{HashMap, HashSet, VecDeque};

use flowmat_condition::{Condition, Values};
use serde_json::{Map, Value as Json, json};

use crate::decimal::Decimal;
use crate::graph;
use crate::schema::PortSchema;

/// Why a revision cannot be read or a step cannot go on: the backend's error code and message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refusal {
    pub code: &'static str,
    pub message: String,
}

impl Refusal {
    fn conflict(message: impl Into<String>) -> Refusal {
        Refusal {
            code: "CONFLICT",
            message: message.into(),
        }
    }

    pub fn to_json(&self) -> Json {
        json!({"code": self.code, "message": self.message})
    }
}

#[derive(Debug, Clone)]
struct Edge {
    id: String,
    target: String,
    from_io_id: Option<String>,
    to_io_id: Option<String>,
    condition: Option<Condition>,
    capacity: Option<Decimal>,
    unit: Option<String>,
    priority: i64,
    default_route: bool,
    order: usize,
    split_kind: Option<String>,
    split_value: Option<Decimal>,
    loss_rate: Option<Decimal>,
    delay_seconds: Option<Decimal>,
    working_delay: bool,
}

/// A published revision ready to route.
#[derive(Debug, Clone)]
pub struct RunGraph {
    roots: Vec<String>,
    outgoing: HashMap<String, Vec<Edge>>,
    by_id: HashMap<String, Edge>,
    incoming: HashMap<String, Vec<String>>,
    port_rules: HashMap<String, Condition>,
    port_schemas: HashMap<String, PortSchema>,
    exclusive: HashSet<String>,
    joins: HashSet<String>,
    splits: HashSet<String>,
}

/// What a step's output does on one outgoing connection (`FlowRunGraph.Decision`).
#[derive(Debug, Clone)]
pub struct Decision {
    pub connection_id: String,
    pub target_node_id: String,
    pub will_route: bool,
    pub reason: Option<&'static str>,
    pub quantity: Option<Decimal>,
    pub loss: Option<Loss>,
}

/// `sent` = `arrived` + `lost`.
#[derive(Debug, Clone)]
pub struct Loss {
    pub sent: Decimal,
    pub rate: Decimal,
    pub lost: Decimal,
    pub arrived: Decimal,
}

impl Decision {
    pub fn to_json(&self) -> Json {
        json!({
            "connectionId": self.connection_id,
            "targetNodeId": self.target_node_id,
            "willRoute": self.will_route,
            "reason": self.reason,
            "quantity": self.quantity.as_ref().map(Decimal::to_json),
            "loss": self.loss.as_ref().map(|loss| json!({
                "sent": loss.sent.to_json(),
                "rate": loss.rate.to_json(),
                "lost": loss.lost.to_json(),
                "arrived": loss.arrived.to_json(),
            })),
        })
    }
}

const POLICIES: [&str; 3] = ["stop", "skip", "retry"];

/// `textOrNull`: text that is not blank, else none.
fn text_or_null(node: Option<&Json>) -> Option<String> {
    node.and_then(Json::as_str)
        .filter(|value| !graph::is_blank(value))
        .map(str::to_string)
}

fn required_text(node: &Json, field: &str) -> Result<String, Refusal> {
    text_or_null(node.get(field))
        .ok_or_else(|| Refusal::conflict(format!("Published revision has an invalid {field}.")))
}

fn compile_published(expression: &str, field: &str) -> Result<Condition, Refusal> {
    Condition::compile(expression).map_err(|_| Refusal::conflict(format!("Published revision has invalid {field}.")))
}

fn number(node: Option<&Json>) -> Option<Decimal> {
    match node? {
        Json::Number(value) => Decimal::parse(&value.to_string()),
        _ => None,
    }
}

fn node_type(process: &Json, kind: &str) -> bool {
    text_or_null(process.get("nodeType")).is_some_and(|value| graph::equals_ignore_case(&value, kind))
}

impl RunGraph {
    /// `PublishedFlowRunGraphReader.read`: the revision's graph, or why it cannot run. Node policies and bindings are
    /// not part of routing and are not read.
    pub fn read(snapshot: &Json) -> Result<RunGraph, Refusal> {
        let processes = snapshot.get("processes").and_then(Json::as_array);
        let connections = snapshot.get("connections").and_then(Json::as_array);
        let (Some(processes), Some(connections)) = (processes, connections) else {
            return Err(Refusal::conflict("Published revision has no executable graph."));
        };
        if processes.is_empty() {
            return Err(Refusal::conflict("Published revision has no executable graph."));
        }
        let mut order: Vec<String> = Vec::new();
        let mut indegree: HashMap<String, usize> = HashMap::new();
        let (mut exclusive, mut joins, mut splits) = (HashSet::new(), HashSet::new(), HashSet::new());
        for process in processes {
            let id = required_text(process, "processId")?;
            if indegree.insert(id.clone(), 0).is_some() {
                return Err(Refusal::conflict("Published revision has duplicate process IDs."));
            }
            order.push(id.clone());
            if node_type(process, "exclusive") {
                exclusive.insert(id.clone());
            }
            if node_type(process, "join") {
                joins.insert(id.clone());
            }
            if node_type(process, "split") {
                splits.insert(id.clone());
            }
        }
        let mut port_units: HashMap<String, String> = HashMap::new();
        let mut port_refs: HashMap<String, (Option<String>, Option<String>)> = HashMap::new();
        let mut port_rules = HashMap::new();
        let mut port_schemas = HashMap::new();
        match snapshot.get("processIos") {
            None => {}
            Some(Json::Array(ports)) => {
                for port in ports {
                    let id = text_or_null(port.get("processIoId"));
                    let Some(id) = id.filter(|id| !port_refs.contains_key(id)) else {
                        return Err(Refusal::conflict(
                            "Published revision has an invalid or duplicate processIoId.",
                        ));
                    };
                    port_refs.insert(
                        id.clone(),
                        (text_or_null(port.get("processId")), text_or_null(port.get("direction"))),
                    );
                    if let Some(unit) = text_or_null(port.get("unit")) {
                        port_units.insert(id.clone(), unit);
                    }
                    if let Some(rule) = text_or_null(port.get("validationRule")) {
                        port_rules.insert(
                            id.clone(),
                            compile_published(&rule, &format!("port {id} validationRule"))?,
                        );
                    }
                    if let Some(schema) = port.get("schemaJson").filter(|schema| !schema.is_null()) {
                        match PortSchema::parse(Some(schema)) {
                            Ok(Some(schema)) => {
                                port_schemas.insert(id.clone(), schema);
                            }
                            Ok(None) => {}
                            Err(_) => {
                                return Err(Refusal::conflict(format!(
                                    "Published revision port {id} has invalid schemaJson."
                                )));
                            }
                        }
                    }
                }
            }
            Some(_) => return Err(Refusal::conflict("Published revision has invalid processIos.")),
        }
        let mut outgoing: HashMap<String, Vec<Edge>> = HashMap::new();
        let mut by_id: HashMap<String, Edge> = HashMap::new();
        let mut incoming: HashMap<String, Vec<String>> = HashMap::new();
        // Before ADR-009 (schemaVersion 1) loss rates do not apply, and before ADR-010 (schemaVersion 2) delays.
        let schema_version = snapshot.get("schemaVersion").and_then(Json::as_i64).unwrap_or(1);
        let (loss_applies, delay_applies) = (schema_version >= 2, schema_version >= 3);
        for connection in connections {
            let id = required_text(connection, "connectionId")?;
            let source = required_text(connection, "fromProcessId")?;
            let target = required_text(connection, "toProcessId")?;
            if !indegree.contains_key(&source) || !indegree.contains_key(&target) {
                return Err(Refusal::conflict(format!(
                    "Published revision connection refers to a missing process: {id}"
                )));
            }
            let condition = match text_or_null(connection.get("conditionExpr")) {
                Some(expression) => Some(compile_published(
                    &expression,
                    &format!("connection {id} conditionExpr"),
                )?),
                None => None,
            };
            let capacity = match connection.get("capacity") {
                None | Some(Json::Null) => None,
                Some(value) => match number(Some(value)).filter(|capacity| capacity.signum() >= 0) {
                    Some(capacity) => Some(capacity),
                    None => {
                        return Err(Refusal::conflict(format!(
                            "Published revision has invalid capacity: {id}"
                        )));
                    }
                },
            };
            let policy = text_or_null(connection.get("failurePolicy")).unwrap_or_else(|| "stop".into());
            if !POLICIES.contains(&policy.as_str()) {
                return Err(Refusal::conflict(format!(
                    "Published revision has invalid failure policy: {id}"
                )));
            }
            let from_io_id = text_or_null(connection.get("fromIoId"));
            let to_io_id = text_or_null(connection.get("toIoId"));
            port_reference(&port_refs, &from_io_id, &source, "output", &id)?;
            port_reference(&port_refs, &to_io_id, &target, "input", &id)?;
            let unit = text_or_null(connection.get("unit"))
                .or_else(|| from_io_id.as_ref().and_then(|port| port_units.get(port).cloned()));
            let priority = connection
                .get("priority")
                .and_then(Json::as_i64)
                .filter(|value| i32::try_from(*value).is_ok())
                .unwrap_or(0);
            let default_route = connection.get("defaultRoute").is_some_and(as_boolean_or_false);
            let split_kind = text_or_null(connection.get("splitKind"));
            let split_value = number(connection.get("splitValue"));
            let mut loss_rate = None;
            if let Some(rate) = number(connection.get("lossRate")).filter(|rate| loss_applies && rate.signum() != 0) {
                if rate.signum() < 0 || rate.compare(&Decimal::from_int(1)).is_ge() {
                    return Err(Refusal::conflict(format!(
                        "Published revision has an invalid lossRate: {id}"
                    )));
                }
                loss_rate = Some(rate);
            }
            let mut delay_seconds = None;
            if let Some(delay) =
                number(connection.get("delayTimeSec")).filter(|delay| delay_applies && delay.signum() != 0)
            {
                if delay.signum() < 0 {
                    return Err(Refusal::conflict(format!(
                        "Published revision has an invalid delayTimeSec: {id}"
                    )));
                }
                delay_seconds = Some(delay);
            }
            let working_delay = text_or_null(connection.get("delayBasis")).as_deref() == Some("working");
            let edge = Edge {
                id: id.clone(),
                target: target.clone(),
                from_io_id,
                to_io_id,
                condition,
                capacity,
                unit,
                priority,
                default_route,
                order: by_id.len(),
                split_kind,
                split_value,
                loss_rate,
                delay_seconds,
                working_delay,
            };
            if by_id.contains_key(&id) {
                return Err(Refusal::conflict("Published revision has duplicate connection IDs."));
            }
            by_id.insert(id.clone(), edge.clone());
            outgoing.entry(source).or_default().push(edge);
            incoming.entry(target.clone()).or_default().push(id);
            *indegree.get_mut(&target).expect("a known process") += 1;
        }
        // Publishing refuses these (JOIN_WITHOUT_INPUT, SPLIT_WITHOUT_INPUT, SPLIT_REST_DUPLICATE), in the order drawn.
        for join in order.iter().filter(|id| joins.contains(*id)) {
            if indegree[join] == 0 {
                return Err(Refusal::conflict(format!(
                    "Published revision Join {join} has no incoming connection."
                )));
            }
        }
        for split in order.iter().filter(|id| splits.contains(*id)) {
            if indegree[split] == 0 {
                return Err(Refusal::conflict(format!(
                    "Published revision Split {split} has no incoming connection."
                )));
            }
            let rests = outgoing.get(split).map_or(0, |edges| {
                edges
                    .iter()
                    .filter(|edge| edge.split_kind.as_deref() == Some("rest"))
                    .count()
            });
            if rests > 1 {
                return Err(Refusal::conflict(format!(
                    "Published revision Split {split} has {rests} rest connections."
                )));
            }
        }
        let roots: Vec<String> = order.iter().filter(|id| indegree[*id] == 0).cloned().collect();
        let mut remaining = indegree.clone();
        let mut queue: VecDeque<String> = roots.iter().cloned().collect();
        let mut visited = 0;
        while let Some(node) = queue.pop_front() {
            visited += 1;
            for edge in outgoing.get(&node).map(Vec::as_slice).unwrap_or_default() {
                let count = remaining.get_mut(&edge.target).expect("a known process");
                *count -= 1;
                if *count == 0 {
                    queue.push_back(edge.target.clone());
                }
            }
        }
        if visited != indegree.len() {
            return Err(Refusal::conflict("Graph revisions with cycles cannot execute."));
        }
        Ok(RunGraph {
            roots,
            outgoing,
            by_id,
            incoming,
            port_rules,
            port_schemas,
            exclusive,
            joins,
            splits,
        })
    }

    /// The nodes nothing leads into, in the order drawn.
    pub fn roots(&self) -> &[String] {
        &self.roots
    }

    pub fn is_join(&self, node: &str) -> bool {
        self.joins.contains(node)
    }

    /// The connection's delay in seconds as published, 0 without one.
    pub fn delay_seconds(&self, connection: &str) -> Decimal {
        self.by_id
            .get(connection)
            .and_then(|edge| edge.delay_seconds.clone())
            .unwrap_or_else(Decimal::zero)
    }

    /// Whether the delay counts only working time (ADR-022).
    pub fn working_delay(&self, connection: &str) -> bool {
        self.by_id.get(connection).is_some_and(|edge| edge.working_delay)
    }

    /// How many connections into the Join a step at one of `live` nodes can still route along, now or further on.
    pub fn still_coming(&self, join: &str, live: &[String]) -> usize {
        let mut reachable: HashSet<&str> = HashSet::new();
        let mut seen: HashSet<&str> = HashSet::new();
        let mut queue: VecDeque<&str> = live.iter().map(String::as_str).collect();
        while let Some(node) = queue.pop_front() {
            if !seen.insert(node) {
                continue;
            }
            for edge in self.outgoing.get(node).map(Vec::as_slice).unwrap_or_default() {
                reachable.insert(&edge.id);
                queue.push_back(&edge.target);
            }
        }
        self.incoming
            .get(join)
            .map_or(0, |ids| ids.iter().filter(|id| reachable.contains(id.as_str())).count())
    }

    /// `FlowRunGraph.preview`: what the output does on each of the node's outgoing connections.
    pub fn preview(&self, node: &str, output: &Json) -> Result<Vec<Decision>, Refusal> {
        if !output.is_object() {
            return Err(Refusal {
                code: "BAD_REQUEST",
                message: "Graph step outputSnapshot must be an object.".into(),
            });
        }
        if self.exclusive.contains(node) {
            return self.exclusive_preview(node, output);
        }
        if self.splits.contains(node) {
            return self.split_preview(node, output);
        }
        let values = Values::from_snapshot(output);
        let mut decisions = Vec::new();
        for edge in self.outgoing.get(node).map(Vec::as_slice).unwrap_or_default() {
            if edge
                .condition
                .as_ref()
                .is_some_and(|condition| !condition.evaluate(&values))
            {
                decisions.push(decision(edge, false, None, None, None));
                continue;
            }
            decisions.push(self.transfer(edge, output, None, None)?);
        }
        Ok(decisions)
    }

    /// By priority, lowest first and then as drawn: the first whose condition holds; else the default; else none.
    fn exclusive_preview(&self, node: &str, output: &Json) -> Result<Vec<Decision>, Refusal> {
        let values = Values::from_snapshot(output);
        let mut edges: Vec<&Edge> = self
            .outgoing
            .get(node)
            .map(|edges| edges.iter().collect())
            .unwrap_or_default();
        edges.sort_by_key(|edge| (edge.priority, edge.order));
        let holds = |edge: &Edge| {
            edge.condition
                .as_ref()
                .is_none_or(|condition| condition.evaluate(&values))
        };
        let mut chosen = edges.iter().position(|edge| !edge.default_route && holds(edge));
        let mut reason = "first_match";
        if chosen.is_none() {
            chosen = edges.iter().position(|edge| edge.default_route);
            reason = "default";
        }
        let routed = match chosen {
            Some(index) => Some(self.transfer(edges[index], output, Some(reason), None)?),
            None => None,
        };
        let mut decisions = Vec::new();
        for (index, edge) in edges.iter().enumerate() {
            if Some(index) == chosen {
                decisions.push(routed.clone().expect("the chosen route"));
            } else if edge.default_route {
                decisions.push(decision(edge, false, Some("default_not_needed"), None, None));
            } else if !holds(edge) {
                decisions.push(decision(edge, false, Some("condition_false"), None, None));
            } else {
                decisions.push(decision(edge, false, Some("exclusive_not_chosen"), None, None));
            }
        }
        Ok(decisions)
    }

    /// Fixed quantities, percents of the whole rounded to 4 places, and the rest; the rounding difference goes to the
    /// rest, else to the last percent drawn. A share of 0 does not go on.
    fn split_preview(&self, node: &str, output: &Json) -> Result<Vec<Decision>, Refusal> {
        let Some(total) = output.get("quantity").and_then(|quantity| number(Some(quantity))) else {
            return Err(Refusal::conflict(format!(
                "Split {node} needs outputSnapshot.quantity to divide."
            )));
        };
        if total.signum() < 0 {
            return Err(Refusal::conflict(format!(
                "Split {node} cannot divide a negative quantity."
            )));
        }
        let edges = self.outgoing.get(node).map(Vec::as_slice).unwrap_or_default();
        if edges.is_empty() {
            return Ok(Vec::new());
        }
        let mut amounts: HashMap<&str, Decimal> = HashMap::new();
        let mut assigned = Decimal::zero();
        let mut rest = None;
        let mut last_share = None;
        for edge in edges {
            let amount = match (edge.split_kind.as_deref(), &edge.split_value) {
                (Some("rest"), _) => {
                    rest = Some(edge);
                    continue;
                }
                (Some("share"), Some(value)) => {
                    last_share = Some(edge);
                    total.multiply(value).percent_of(4)
                }
                (Some("fixed"), Some(value)) => value.clone(),
                _ => {
                    return Err(Refusal::conflict(format!(
                        "Published revision Split {node} connection {} has no share.",
                        edge.id
                    )));
                }
            };
            assigned = assigned.add(&amount);
            amounts.insert(&edge.id, amount);
        }
        let left = total.subtract(&assigned);
        if let Some(rest) = rest {
            amounts.insert(&rest.id, left);
        } else if let Some(last) = last_share {
            let topped = amounts[last.id.as_str()].add(&left);
            amounts.insert(&last.id, topped);
        } else if left.signum() != 0 {
            return Err(Refusal::conflict(format!(
                "Split {node} leaves {} undivided.",
                left.plain()
            )));
        }
        if amounts.values().any(|amount| amount.signum() < 0) {
            return Err(Refusal::conflict(format!(
                "Split {node} gives out {} but has {}.",
                assigned.plain(),
                total.plain()
            )));
        }
        let mut decisions = Vec::new();
        for edge in edges {
            let amount = amounts[edge.id.as_str()].clone();
            if amount.signum() == 0 {
                decisions.push(decision(edge, false, Some("split_empty"), Some(amount), None));
                continue;
            }
            let share = with_quantity(output, &amount);
            decisions.push(self.transfer(edge, &share, Some("split"), Some(amount))?);
        }
        Ok(decisions)
    }

    /// A connection's transfer and loss: capacity, unit and the output port see what is sent, the input port what
    /// arrives. A positive quantity all lost to rounding does not go on.
    fn transfer(
        &self,
        edge: &Edge,
        sent: &Json,
        reason: Option<&'static str>,
        share: Option<Decimal>,
    ) -> Result<Decision, Refusal> {
        let loss = loss(edge, sent);
        if let Some(loss) = &loss
            && loss.arrived.signum() == 0
        {
            self.require_transfer(edge, sent, None)?;
            return Ok(decision(
                edge,
                false,
                Some("loss_empty"),
                Some(loss.arrived.clone()),
                Some(loss.clone()),
            ));
        }
        let arriving = match &loss {
            None => sent.clone(),
            Some(loss) => with_quantity(sent, &loss.arrived),
        };
        self.require_transfer(edge, sent, Some(&arriving))?;
        let quantity = match &loss {
            None => share,
            Some(loss) => Some(loss.arrived.clone()),
        };
        Ok(decision(edge, true, reason, quantity, loss))
    }

    fn require_transfer(&self, edge: &Edge, output: &Json, arriving: Option<&Json>) -> Result<(), Refusal> {
        if let Some(capacity) = &edge.capacity {
            let Some(amount) = number(output.get("quantity")) else {
                return Err(Refusal::conflict(format!(
                    "Connection {} requires outputSnapshot.quantity for capacity.",
                    edge.id
                )));
            };
            if amount.compare(capacity).is_gt() {
                return Err(Refusal::conflict(format!("Connection {} capacity exceeded.", edge.id)));
            }
        }
        if let (Some(unit), Some(actual)) = (&edge.unit, output.get("unit").and_then(scalar)) {
            // A number or true/false is never equal to the connection's unit text.
            if actual.as_str() != Some(unit.as_str()) {
                return Err(Refusal::conflict(format!(
                    "Connection {} unit does not match outputSnapshot.unit.",
                    edge.id
                )));
            }
        }
        self.require_schema(edge.from_io_id.as_deref(), output)?;
        if let Some(arriving) = arriving {
            self.require_schema(edge.to_io_id.as_deref(), arriving)?;
        }
        self.require_rule(edge.from_io_id.as_deref(), output)?;
        if let Some(arriving) = arriving {
            self.require_rule(edge.to_io_id.as_deref(), arriving)?;
        }
        Ok(())
    }

    fn require_rule(&self, port: Option<&str>, output: &Json) -> Result<(), Refusal> {
        let Some(port) = port else { return Ok(()) };
        match self.port_rules.get(port) {
            Some(rule) if !rule.evaluate(&Values::from_snapshot(output)) => {
                Err(Refusal::conflict(format!("Port {port} validationRule did not pass.")))
            }
            _ => Ok(()),
        }
    }

    fn require_schema(&self, port: Option<&str>, output: &Json) -> Result<(), Refusal> {
        let Some(port) = port else { return Ok(()) };
        let Some(schema) = self.port_schemas.get(port) else {
            return Ok(());
        };
        let attrs = output.get("attrs");
        let empty = Map::new();
        let attrs = match attrs {
            None => &empty,
            Some(Json::Object(attrs)) => attrs,
            Some(_) => {
                return Err(Refusal::conflict(format!(
                    "Port {port} outputSnapshot.attrs must be an object."
                )));
            }
        };
        for name in &schema.required {
            if attrs.get(name).is_none_or(Json::is_null) {
                return Err(Refusal::conflict(format!(
                    "Port {port} requires outputSnapshot.attrs.{name}."
                )));
            }
        }
        for (name, kind) in &schema.properties {
            let Some(value) = attrs.get(name) else { continue };
            let valid = match kind.as_str() {
                "string" => value.is_string(),
                "number" => value.is_number(),
                "boolean" => value.is_boolean(),
                _ => false,
            };
            if !valid {
                return Err(Refusal::conflict(format!(
                    "Port {port} outputSnapshot.attrs.{name} must be {kind}."
                )));
            }
        }
        Ok(())
    }
}

fn decision(
    edge: &Edge,
    will_route: bool,
    reason: Option<&'static str>,
    quantity: Option<Decimal>,
    loss: Option<Loss>,
) -> Decision {
    Decision {
        connection_id: edge.id.clone(),
        target_node_id: edge.target.clone(),
        will_route,
        reason,
        quantity,
        loss,
    }
}

/// A connection's loss on a positive quantity: what arrives, rounded to 4 places half up, never more than was sent.
fn loss(edge: &Edge, values: &Json) -> Option<Loss> {
    let rate = edge.loss_rate.as_ref()?;
    let sent = number(values.get("quantity")).filter(|sent| sent.signum() > 0)?;
    let mut arrived = sent.multiply(&Decimal::from_int(1).subtract(rate)).set_scale_half_up(4);
    if arrived.compare(&sent).is_gt() {
        arrived = sent.clone();
    }
    Some(Loss {
        lost: sent.subtract(&arrived),
        sent,
        rate: rate.clone(),
        arrived,
    })
}

/// The output with its quantity replaced.
fn with_quantity(output: &Json, quantity: &Decimal) -> Json {
    let mut copy = output.clone();
    if let Some(object) = copy.as_object_mut() {
        object.insert("quantity".into(), quantity.to_json());
    }
    copy
}

/// A value as the check reads one: a number, text or true/false; anything else is no value.
fn scalar(value: &Json) -> Option<&Json> {
    match value {
        Json::String(_) | Json::Number(_) | Json::Bool(_) => Some(value),
        _ => None,
    }
}

/// `path("defaultRoute").asBoolean(false)`.
fn as_boolean_or_false(value: &Json) -> bool {
    graph::as_boolean(value)
}

/// `requirePortReference`: a connection's port must be the right direction of the right process.
fn port_reference(
    refs: &HashMap<String, (Option<String>, Option<String>)>,
    port: &Option<String>,
    process: &str,
    direction: &str,
    connection: &str,
) -> Result<(), Refusal> {
    let Some(port) = port else { return Ok(()) };
    match refs.get(port) {
        Some((Some(owner), Some(way))) if owner == process && way == direction => Ok(()),
        _ => Err(Refusal::conflict(format!(
            "Published revision connection {connection} has an invalid {}.",
            if direction == "output" { "fromIoId" } else { "toIoId" }
        ))),
    }
}
