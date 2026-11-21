//! When each step of a tried flow is ready and scheduled (docs/architecture/adr/ADR-041-try-it-time.md), by the rules
//! the server's router follows (`FlowRunTiming`): a connection's delay counts from the step's end, or from what a gateway
//! received; a delay that counts working time adds up the stretches the work order's equipment and crew work together; a
//! step ready outside them waits for the next one; a Join is ready when its latest arrival is. Along the walk a step ends
//! as soon as it may start, a gateway passes at once and keeps the time it received, and a Join passes on its last arrival.
//! `fixtures/time.json` holds Java's answers.

use serde_json::{Map, Value as Json, json};

use crate::routing::RunGraph;
use crate::time::DateTime;

/// The stretches `[start, end)` (instants in nanoseconds) a work order's equipment and crew work together, earliest
/// first and not overlapping, and who they are. No stretches: every delay counts all time and nothing waits.
pub struct Team {
    pub stretches: Vec<(i128, i128)>,
    pub equipment_id: Option<String>,
    pub crew: Vec<String>,
}

impl Team {
    /// `{stretches: [[from, until], ...], equipmentId, crew}`; None when a time cannot be read.
    pub fn from_json(time: &Json) -> Option<Team> {
        let mut stretches = Vec::new();
        for pair in time
            .get("stretches")
            .and_then(Json::as_array)
            .map(Vec::as_slice)
            .unwrap_or(&[])
        {
            let start = DateTime::parse(pair.get(0)?.as_str()?)?.instant();
            let end = DateTime::parse(pair.get(1)?.as_str()?)?.instant();
            stretches.push((start, end));
        }
        Some(Team {
            stretches,
            equipment_id: time.get("equipmentId").and_then(Json::as_str).map(str::to_string),
            crew: time
                .get("crew")
                .and_then(Json::as_array)
                .map(|crew| {
                    crew.iter()
                        .filter_map(|person| person.as_str().map(str::to_string))
                        .collect()
                })
                .unwrap_or_default(),
        })
    }

    /// `WorkingStretches.firstStart`: `from` inside a stretch, else the next one's start.
    fn first_start(&self, from: i128) -> Option<i128> {
        self.stretches
            .iter()
            .find(|(_, end)| *end > from)
            .map(|(start, _)| (*start).max(from))
    }

    /// `WorkingStretches.afterWorking`: when `working` nanoseconds of working time have passed since `from`; none when
    /// the stretches hold less.
    fn after_working(&self, from: i128, working: i128) -> Option<i128> {
        let mut left = working;
        for (start, end) in &self.stretches {
            if *end <= from {
                continue;
            }
            let start = (*start).max(from);
            let length = end - start;
            if length >= left {
                return Some(start + left);
            }
            left -= length;
        }
        None
    }

    /// `FlowRunTiming.stretches(...).workingStart`: the next working start when `ready` falls outside working time.
    fn working_start(&self, ready: DateTime) -> Option<DateTime> {
        let start = self.first_start(ready.instant())?;
        (start != ready.instant()).then(|| DateTime::from_instant(start, ready.offset()))
    }
}

/// `FlowRunTiming.readyAt`.
fn ready_at(
    ended: Option<DateTime>,
    carried: Option<DateTime>,
    delay: Option<i128>,
    working: bool,
    team: &Team,
) -> Option<DateTime> {
    let base = carried.or(ended)?;
    let Some(delay) = delay else {
        return carried.map(|time| time.truncated_to_micros());
    };
    let worked = if working {
        team.after_working(base.instant(), delay)
            .map(|end| DateTime::from_instant(end, base.offset()))
    } else {
        None
    };
    Some(worked.unwrap_or_else(|| base.plus_nanos(delay)).truncated_to_micros())
}

fn later(one: Option<DateTime>, other: Option<DateTime>) -> Option<DateTime> {
    match (one, other) {
        (Some(one), Some(other)) => Some(if other.instant() > one.instant() { other } else { one }),
        (one, None) => one,
        (None, other) => other,
    }
}

/// The steps' times along a walk (`walk::walk`'s answer), in its order: `{nodeId, readyAt?, scheduledAt?, endedAt?,
/// held?}` each, with times as `ISO_OFFSET_DATE_TIME`.
pub fn schedule(runtime: &RunGraph, walk: &Json, start: DateTime, team: &Team) -> Json {
    let steps = walk
        .get("steps")
        .and_then(Json::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    let gateway = |node: &str| runtime.is_join(node) || runtime.is_exclusive(node) || runtime.is_split(node);
    let delay = |connection: &str| {
        let seconds = runtime.delay_seconds(connection);
        (seconds.signum() != 0).then(|| seconds.whole_after_moving_right(9))
    };
    let node_of = |index: usize| {
        steps
            .get(index)
            .and_then(|step| step.get("nodeId"))
            .and_then(Json::as_str)
            .unwrap_or("")
    };
    let mut ended: Vec<Option<DateTime>> = Vec::with_capacity(steps.len());
    let mut scheduled: Vec<Option<DateTime>> = Vec::with_capacity(steps.len());
    let arrive = |from: usize, connection: &str, ended: &[Option<DateTime>], scheduled: &[Option<DateTime>]| {
        let carried = if gateway(node_of(from)) {
            scheduled.get(from).copied().flatten()
        } else {
            None
        };
        ready_at(
            ended.get(from).copied().flatten(),
            carried,
            delay(connection),
            runtime.working_delay(connection),
            team,
        )
    };
    let mut answer = Vec::with_capacity(steps.len());
    for step in steps {
        let node = step.get("nodeId").and_then(Json::as_str).unwrap_or("");
        let completed = step.get("status").and_then(Json::as_str) == Some("completed");
        let source = step.get("source").and_then(Json::as_u64).map(|index| index as usize);
        let mut ready = None;
        let mut held = None;
        let mut this_scheduled = None;
        let this_created;
        let mut this_ended = None;
        match source {
            None => this_created = Some(start),
            Some(from) if runtime.is_join(node) => {
                let mut latest: Option<DateTime> = None;
                let mut delayed = false;
                let mut last = None;
                for arrival in step
                    .pointer("/input/arrivals")
                    .and_then(Json::as_array)
                    .map(Vec::as_slice)
                    .unwrap_or(&[])
                {
                    let Some(at) = arrival
                        .get("sourceStepId")
                        .and_then(Json::as_u64)
                        .map(|index| index as usize)
                    else {
                        continue;
                    };
                    let connection = arrival.get("connectionId").and_then(Json::as_str).unwrap_or("");
                    let arrived = arrive(at, connection, &ended, &scheduled);
                    let source_end = ended.get(at).copied().flatten();
                    delayed |= arrived.is_some();
                    latest = later(latest, arrived.or(source_end));
                    last = later(last, source_end);
                }
                this_created = ended.get(from).copied().flatten();
                this_scheduled = if delayed {
                    latest.map(|time| time.truncated_to_micros())
                } else {
                    None
                };
                if completed {
                    this_ended = last;
                }
            }
            Some(from) => {
                this_created = ended.get(from).copied().flatten();
                let connection = step.get("connectionId").and_then(Json::as_str).unwrap_or("");
                ready = arrive(from, connection, &ended, &scheduled);
                if let Some(at) = ready {
                    let waits = if gateway(node) { None } else { team.working_start(at) };
                    this_scheduled = Some(match waits {
                        Some(start) => {
                            held = Some(start);
                            start.at_offset(at.offset()).truncated_to_micros()
                        }
                        None => at,
                    });
                }
            }
        }
        if !runtime.is_join(node) && completed {
            this_ended = if gateway(node) {
                this_created
            } else {
                later(this_created, this_scheduled)
            };
        }
        let mut out = Map::new();
        out.insert("nodeId".into(), json!(node));
        if let Some(at) = ready {
            out.insert("readyAt".into(), json!(at.format()));
        }
        if let Some(at) = this_scheduled {
            out.insert("scheduledAt".into(), json!(at.format()));
        }
        if let Some(at) = this_ended {
            out.insert("endedAt".into(), json!(at.format()));
        }
        if held.is_some() {
            out.insert(
                "held".into(),
                json!({"equipmentId": team.equipment_id, "crew": team.crew}),
            );
        }
        answer.push(Json::Object(out));
        ended.push(this_ended);
        scheduled.push(this_scheduled);
    }
    json!({ "steps": answer })
}
