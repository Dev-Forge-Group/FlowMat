"""A fixed-seed set of sequence snapshots for the evaluation (ADR-039 decision 3).

Each case is one equipment's week, shaped like the backend's /equipment-load/sequence-snapshot answer without its
baselines: a calendar (one shift, two shifts, round the clock, or a shift with a stop), 2 to 6 items with an asymmetric
changeover matrix, 4 to 20 jobs in planned order with priorities, and sometimes a running job first. Planned ends are the
plan's own ends moved earlier or later, so some plans are already late. Run as a module to rewrite fixtures/snapshots.json:

    python -m sequence_optimizer.generate
"""
from __future__ import annotations

import json
import random
from datetime import datetime, timedelta
from pathlib import Path

from . import cost

SEED = 20261121
CASES = 40
FROM = datetime.fromisoformat("2030-01-07T00:00:00+09:00")  # a Monday
HORIZON_DAYS = 28
WEIGHTS = {"changeoverMinute": 1, "lateMinute": {"urgent": 4, "high": 2, "normal": 1, "low": 0.5}}
CHANGEOVER_MINUTES = [0, 15, 30, 45, 60, 90, 120]
JOB_COUNTS = [4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 20]
PRIORITIES = ["urgent"] * 2 + ["high"] * 4 + ["normal"] * 11 + ["low"] * 3
END_SHIFTS_HOURS = [-6, -3, -1, 0, 0, 2, 8, 24]


def iso(moment: datetime) -> str:
    return moment.isoformat()


def calendar(rng: random.Random) -> tuple[str, list[dict]]:
    kind = rng.choice(["one shift", "two shifts", "round the clock", "one shift with a stop"])
    if kind == "round the clock":
        return kind, [{"start": iso(FROM), "end": iso(FROM + timedelta(days=HORIZON_DAYS))}]
    hours = (6, 22) if kind == "two shifts" else (9, 17)
    stop_day = rng.randrange(5)
    found = []
    for day in range(HORIZON_DAYS):
        moment = FROM + timedelta(days=day)
        if moment.weekday() >= 5:
            continue
        start, end = moment.replace(hour=hours[0]), moment.replace(hour=hours[1])
        if kind == "one shift with a stop" and day == stop_day:
            found.append({"start": iso(start), "end": iso(moment.replace(hour=12))})
            found.append({"start": iso(moment.replace(hour=15)), "end": iso(end)})
        else:
            found.append({"start": iso(start), "end": iso(end)})
    return kind, found


def case(rng: random.Random, number: int) -> dict:
    kind, available = calendar(rng)
    items = [f"I{index + 1}" for index in range(rng.randint(2, 6))]
    before = rng.choice(items) if rng.random() < 0.8 else None
    changeovers = {a: {b: 0 if a == b else rng.choice(CHANGEOVER_MINUTES[1:]) for b in items} for a in items}
    count = rng.choice(JOB_COUNTS)
    running = rng.random() < 0.25
    jobs = []
    for index in range(count):
        hours = rng.randint(1, 10)
        seconds = hours * 3600 + rng.choice([0, 900, 1800, 2700])
        jobs.append({
            "workOrderId": f"wo-{number:02d}-{index + 1:02d}", "workOrderNumber": f"WO-{number:02d}-{index + 1:02d}",
            "item": rng.choice(items), "status": "in_progress" if running and index == 0 else rng.choice(["approved", "draft"]),
            "fixed": running and index == 0, "remaining": hours * 10, "productionSeconds": seconds,
            "plannedStart": None, "plannedEnd": None, "priority": rng.choice(PRIORITIES),
        })
    snapshot = {
        "schemaVersion": 1, "projectId": "evaluation", "equipmentId": f"case-{number:02d}", "from": iso(FROM),
        "to": iso(FROM + timedelta(days=7)), "horizonEnd": iso(FROM + timedelta(days=HORIZON_DAYS)), "itemBefore": before,
        "available": available, "jobs": jobs, "changeovers": changeovers, "weights": WEIGHTS, "leftOut": [],
        "plan": None, "currentRule": None,
    }
    # Planned times: the plan's own times, with each end moved by a few hours either way.
    planned = cost.of({**snapshot, "jobs": [{**job, "plannedEnd": iso(FROM)} for job in jobs]},
                      [job["workOrderNumber"] for job in jobs])
    for job, placement in zip(jobs, planned.placements):
        start = placement.start or FROM
        end = placement.end or FROM + timedelta(days=7)
        job["plannedStart"] = iso(start)
        job["plannedEnd"] = iso(max(start + timedelta(hours=1), end + timedelta(hours=rng.choice(END_SHIFTS_HOURS))))
    return {"id": f"case-{number:02d}", "calendar": kind, "snapshot": snapshot}


def generate(seed: int = SEED, cases: int = CASES) -> list[dict]:
    rng = random.Random(seed)
    return [case(rng, number) for number in range(1, cases + 1)]


def main() -> None:
    path = Path(__file__).resolve().parent.parent / "fixtures" / "snapshots.json"
    path.write_text(json.dumps({"seed": SEED, "cases": generate()}, indent=1) + "\n")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
