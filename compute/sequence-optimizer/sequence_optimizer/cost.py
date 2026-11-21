"""The cost of an order of a snapshot's jobs: a mirror of the backend's SequenceCost.

Jobs run one after another from the window's start, each its changeover from the item before and then its production,
filling the equipment's available time. A job is late by how far it ends after its planned end, rounded up to the minute.
The cost is changeover minutes plus minutes late weighted by priority. The numbers must be the backend's to the second:
tests/test_cost.py checks them against fixtures/baselines.json, which the backend writes.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


@dataclass(frozen=True)
class Placement:
    number: str
    start: datetime | None
    end: datetime | None
    changeover_minutes: int
    late_minutes: int | None


@dataclass(frozen=True)
class Cost:
    order: list[str]
    changeover_minutes: int
    late_minutes: int
    weighted_late_minutes: Decimal
    objective: Decimal | None
    unplaced: int
    placements: list[Placement]


def changeover(snapshot: dict, from_item: str | None, to_item: str) -> int:
    if from_item is None:
        return 0
    return int(snapshot["changeovers"].get(from_item, {}).get(to_item, 0))


def weight(snapshot: dict, priority: str | None) -> Decimal:
    late = snapshot["weights"]["lateMinute"]
    value = late.get(priority or "normal", late.get("normal", 1))
    return Decimal(str(value))


def slots(snapshot: dict) -> list[tuple[datetime, datetime]]:
    return [(parse_time(slot["start"]), parse_time(slot["end"])) for slot in snapshot["available"]]


def fill(available: list[tuple[datetime, datetime]], start_from: datetime, seconds: int):
    """Where `seconds` of available time from `start_from` begin and end; None when the slots do not hold them."""
    start = None
    left = seconds
    for slot_start, slot_end in available:
        if slot_end <= start_from:
            continue
        begin = max(slot_start, start_from)
        if start is None:
            start = begin
        room = int((slot_end - begin).total_seconds())
        if left <= room:
            return start, begin + timedelta(seconds=left)
        left -= room
    return None


def ceil_minutes(seconds: int) -> int:
    return (seconds + 59) // 60


def of(snapshot: dict, order: list[str]) -> Cost:
    jobs = {job["workOrderNumber"]: job for job in snapshot["jobs"]}
    if len(order) != len(jobs) or set(order) != set(jobs) or len(set(order)) != len(order):
        raise ValueError("An order names each of the snapshot's jobs once")
    fixed = sum(1 for job in snapshot["jobs"] if job["fixed"])
    if any(not jobs[number]["fixed"] for number in order[:fixed]):
        raise ValueError("Running jobs stay first")
    available = slots(snapshot)
    cursor: datetime | None = parse_time(snapshot["from"])
    previous = snapshot.get("itemBefore")
    changeovers = 0
    late_total = 0
    weighted = Decimal(0)
    unplaced = 0
    placements = []
    for number in order:
        job = jobs[number]
        minutes = changeover(snapshot, previous, job["item"])
        previous = job["item"]
        changeovers += minutes
        span = None if cursor is None else fill(available, cursor, minutes * 60 + int(job["productionSeconds"]))
        if span is None:
            unplaced += 1
            cursor = None
            placements.append(Placement(number, None, None, minutes, None))
            continue
        cursor = span[1]
        late = max(0, ceil_minutes(int((span[1] - parse_time(job["plannedEnd"])).total_seconds())))
        late_total += late
        weighted += weight(snapshot, job.get("priority")) * late
        placements.append(Placement(number, span[0], span[1], minutes, late))
    change_weight = Decimal(str(snapshot["weights"]["changeoverMinute"]))
    objective = None if unplaced else change_weight * changeovers + weighted
    return Cost(list(order), changeovers, late_total, weighted, objective, unplaced, placements)
